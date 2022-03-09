/*
 * Copyright (C) 2016-2022 ActionTech.
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
 */

package com.actiontech.dble.rwsplit;

import com.actiontech.dble.DbleServer;
import com.actiontech.dble.backend.datasource.PhysicalDbGroup;
import com.actiontech.dble.backend.datasource.PhysicalDbInstance;
import com.actiontech.dble.backend.mysql.ByteUtil;
import com.actiontech.dble.config.ErrorCode;
import com.actiontech.dble.config.model.SystemConfig;
import com.actiontech.dble.config.util.ConfigException;
import com.actiontech.dble.net.Session;
import com.actiontech.dble.net.connection.BackendConnection;
import com.actiontech.dble.net.connection.FrontendConnection;
import com.actiontech.dble.net.mysql.MySQLPacket;
import com.actiontech.dble.route.handler.HintDbInstanceHandler;
import com.actiontech.dble.route.handler.HintMasterDBHandler;
import com.actiontech.dble.route.parser.DbleHintParser;
import com.actiontech.dble.services.mysqlsharding.MySQLResponseService;
import com.actiontech.dble.services.rwsplit.Callback;
import com.actiontech.dble.services.rwsplit.RWSplitHandler;
import com.actiontech.dble.services.rwsplit.RWSplitService;
import com.actiontech.dble.services.rwsplit.handle.PSHandler;
import com.actiontech.dble.services.rwsplit.handle.PreparedStatementHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;

public class RWSplitNonBlockingSession extends Session {

    public static final Logger LOGGER = LoggerFactory.getLogger(RWSplitNonBlockingSession.class);

    private volatile BackendConnection conn;
    private final RWSplitService rwSplitService;
    private PhysicalDbGroup rwGroup;

    private volatile boolean preSendIsWrite = false; // Has the previous SQL been delivered to the write node?
    private volatile long preWriteResponseTime = 0; // Response time of the previous write node
    private int reSelectNum;

    public RWSplitNonBlockingSession(RWSplitService service) {
        this.rwSplitService = service;
    }

    @Override
    public FrontendConnection getSource() {
        return (FrontendConnection) rwSplitService.getConnection();
    }

    @Override
    public void stopFlowControl(int currentWritingSize) {

        synchronized (this) {
            if (rwSplitService.isFlowControlled()) {
                LOGGER.info("Session stop flow control " + this.getSource());
                rwSplitService.getConnection().setFrontWriteFlowControlled(false);
            }
            final BackendConnection con = this.conn;
            if (con == null) {
                return;
            }

            if (con.getService() instanceof MySQLResponseService) {
                int size = ((MySQLResponseService) (con.getService())).getReadSize();
                if (size <= con.getFlowLowLevel()) {
                    con.enableRead();
                } else {
                    LOGGER.debug("This front connection want to remove flow control, but mysql conn [{}]'s size [{}] is not lower the FlowLowLevel", con.getThreadId(), size);
                }
            } else {
                con.enableRead();
            }
        }
    }

    @Override
    public void startFlowControl(int currentWritingSize) {
        synchronized (this) {
            if (!rwSplitService.isFlowControlled()) {
                LOGGER.info("Session start flow control " + this.getSource());
            }
            rwSplitService.getConnection().setFrontWriteFlowControlled(true);
            this.conn.disableRead();
        }
    }

    @Override
    public void releaseConnectionFromFlowControlled(BackendConnection con) {
        synchronized (this) {
            con.getSocketWR().enableRead();
            rwSplitService.getConnection().setFrontWriteFlowControlled(false);
        }
    }

    public void execute(Boolean master, Callback callback) {
        execute(master, null, callback);
    }

    public void execute(Boolean master, Callback callback, boolean write) {
        execute(master, null, callback, write);
    }

    public void execute(Boolean master, byte[] originPacket, Callback callback) {
        try {
            RWSplitHandler handler = getRwSplitHandler(originPacket, callback);
            if (handler == null) return;
            getConnection(handler, master, null);
        } catch (SQLSyntaxErrorException | IOException se) {
            rwSplitService.writeErrMessage(ErrorCode.ER_UNKNOWN_ERROR, se.getMessage());
        }
    }

    public void execute(Boolean master, byte[] originPacket, Callback callback, boolean write) {
        try {
            RWSplitHandler handler = getRwSplitHandler(originPacket, callback);
            if (handler == null) return;
            getConnection(handler, master, isWrite(write));
        } catch (SQLSyntaxErrorException | IOException se) {
            rwSplitService.writeErrMessage(ErrorCode.ER_UNKNOWN_ERROR, se.getMessage());
        }
    }

    public void getConnection(RWSplitHandler handler, Boolean master, Boolean write) {
        try {
            Boolean isMaster = canRunOnMaster(master); //  first
            boolean firstValue = isMaster == null ? false : isMaster;
            long rwStickyTime = SystemConfig.getInstance().getRwStickyTime();
            if ((rwStickyTime > 0) && !firstValue) {
                if (this.getPreWriteResponseTime() > 0 && System.currentTimeMillis() - this.getPreWriteResponseTime() <= rwStickyTime) {
                    isMaster = true;
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("because in the sticky time range，so select write instance");
                    }
                } else {
                    resetLastSqlResponseTime();
                }
            }
            PhysicalDbInstance instance = reSelectRWDbGroup(rwGroup).rwSelect(isMaster, write); // second
            boolean isWrite = !instance.isReadInstance();
            this.setPreSendIsWrite(isWrite && firstValue); // ensure that the first and second results are write instances
            checkDest(isWrite);
            instance.getConnection(rwSplitService.getSchema(), handler, null, false);
        } catch (SQLSyntaxErrorException se) {
            rwGroup.unBindRwSplitSession(this);
            rwSplitService.writeErrMessage(ErrorCode.ER_UNKNOWN_ERROR, se.getMessage());
        } catch (IOException e) {
            LOGGER.warn("select conn error", e);
            rwGroup.unBindRwSplitSession(this);
            rwSplitService.writeErrMessage(ErrorCode.ER_UNKNOWN_ERROR, e.getMessage());
        } catch (Exception | Error e) {
            rwGroup.unBindRwSplitSession(this);
            throw e;
        }
    }

    @Nullable
    private RWSplitHandler getRwSplitHandler(byte[] originPacket, Callback callback) throws SQLSyntaxErrorException, IOException {
        RWSplitHandler handler = new RWSplitHandler(rwSplitService, originPacket, callback, false);
        if (conn != null && !conn.isClosed()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("select bind conn[id={}]", conn.getId());
            }
            // for ps needs to send master
            if ((originPacket != null && originPacket.length > 4 && originPacket[4] == MySQLPacket.COM_STMT_EXECUTE)) {
                long statementId = ByteUtil.readUB4(originPacket, 5);
                PreparedStatementHolder holder = rwSplitService.getPrepareStatement(statementId);
                if (holder.isMustMaster() && conn.getInstance().isReadInstance()) {
                    holder.setExecuteOrigin(originPacket);
                    PSHandler psHandler = new PSHandler(rwSplitService, holder);
                    psHandler.execute(rwGroup);
                    return null;
                }
            }
            checkDest(!conn.getInstance().isReadInstance());
            handler.execute(conn);
            return null;
        }
        return handler;
    }

    private boolean isWrite(boolean write) {
        if (!rwSplitService.isAutocommit() || rwSplitService.isTxStart() || rwSplitService.isUsingTmpTable()) {
            return true;
        }
        return write;
    }

    private Boolean canRunOnMaster(Boolean master) {
        if (!rwSplitService.isAutocommit() || rwSplitService.isTxStart() || rwSplitService.isUsingTmpTable()) {
            return true;
        }
        return master;
    }

    private void checkDest(boolean isMaster) throws SQLSyntaxErrorException {
        String dest = rwSplitService.getExpectedDest();
        if (dest == null) {
            return;
        }
        if (dest.equalsIgnoreCase("M") && isMaster) {
            return;
        }
        if (dest.equalsIgnoreCase("S") && !isMaster) {
            return;
        }
        throw new SQLSyntaxErrorException("unexpected dble_dest_expect,real[" + (isMaster ? "M" : "S") + "],expect[" + dest + "]");
    }

    public PhysicalDbGroup reSelectRWDbGroup(PhysicalDbGroup dbGroup) {
        dbGroup.bindRwSplitSession(this);
        if (dbGroup.isStop()) {
            dbGroup.unBindRwSplitSession(this);
            if (reSelectNum == 10) {
                reSelectNum = 0;
                LOGGER.warn("dbGroup`{}` is always invalid", rwSplitService.getUserConfig().getDbGroup());
                throw new ConfigException("the dbGroup`" + rwSplitService.getUserConfig().getDbGroup() + "` is always invalid, pls check reason");
            }
            PhysicalDbGroup newDbGroup = DbleServer.getInstance().getConfig().getDbGroups().get(rwSplitService.getUserConfig().getDbGroup());
            if (newDbGroup == null) {
                LOGGER.warn("dbGroup`{}` is invalid", rwSplitService.getUserConfig().getDbGroup());
                throw new ConfigException("the dbGroup`" + rwSplitService.getUserConfig().getDbGroup() + "` is invalid");
            } else {
                reSelectNum++;
                return reSelectRWDbGroup(newDbGroup);
            }
        } else {
            reSelectNum = 0;
            this.rwGroup = dbGroup;
        }
        return dbGroup;
    }

    public PhysicalDbGroup getRwGroup() {
        return rwGroup;
    }

    public void executeHint(DbleHintParser.HintInfo hintInfo, int sqlType, String sql, Callback callback) throws SQLException, IOException {
        RWSplitHandler handler = new RWSplitHandler(rwSplitService, null, callback, true);
        try {
            PhysicalDbInstance dbInstance = routeRwSplit(hintInfo, sqlType, rwSplitService);
            if (dbInstance == null) {
                return;
            }
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("route sql {} to {}", sql, dbInstance);
            }
            dbInstance.getConnection(rwSplitService.getSchema(), handler, null, false);
        } catch (Exception e) {
            rwSplitService.executeException(e, sql);
        }
    }

    private PhysicalDbInstance routeRwSplit(DbleHintParser.HintInfo hintInfo, int sqlType, RWSplitService service) throws SQLException {
        PhysicalDbInstance dbInstance = null;
        int type = hintInfo.getType();
        if (type == DbleHintParser.DB_INSTANCE_URL || type == DbleHintParser.UPROXY_DEST) {
            dbInstance = HintDbInstanceHandler.route(hintInfo.getRealSql(), service, hintInfo.getHintValue());
        } else if (type == DbleHintParser.UPROXY_MASTER || type == DbleHintParser.DB_TYPE) {
            dbInstance = HintMasterDBHandler.route(hintInfo.getHintValue(), sqlType, hintInfo.getRealSql(), service);
        }
        service.setExecuteSql(hintInfo.getRealSql());
        return dbInstance;
    }

    public void setRwGroup(PhysicalDbGroup rwGroup) {
        this.rwGroup = rwGroup;
    }

    public void bind(BackendConnection bindConn) {
        if (conn != null && conn != bindConn) {
            LOGGER.warn("last conn is remaining");
        }
        this.conn = bindConn;
    }

    public void unbindIfSafe() {
        if (this.conn != null && rwSplitService.isKeepBackendConn()) {
            if (rwSplitService.isFlowControlled()) {
                releaseConnectionFromFlowControlled(conn);
            }
            this.conn.release();
            this.conn = null;
        }
    }

    public void unbind() {
        this.conn = null;
    }

    public void close(String reason) {
        if (null != rwGroup) {
            rwGroup.unBindRwSplitSession(this);
        }
        if (conn != null) {
            conn.close(reason);
        }
    }

    public void setPreSendIsWrite(boolean preSendIsWrite) {
        this.preSendIsWrite = preSendIsWrite;
    }

    public long getPreWriteResponseTime() {
        return this.preWriteResponseTime;
    }

    public void resetLastSqlResponseTime() {
        this.preWriteResponseTime = 0;
    }

    public void recordLastSqlResponseTime() {
        if (SystemConfig.getInstance().getRwStickyTime() >= 0 && preSendIsWrite) {
            this.preWriteResponseTime = System.currentTimeMillis();
        }
    }

    public BackendConnection getConn() {
        return conn;
    }

    public boolean closed() {
        return rwSplitService.getConnection().isClosed();
    }

    public RWSplitService getService() {
        return rwSplitService;
    }
}
