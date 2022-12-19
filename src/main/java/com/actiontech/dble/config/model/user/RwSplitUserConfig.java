/*
 * Copyright (C) 2016-2021 ActionTech.
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
 */

package com.actiontech.dble.config.model.user;

import com.actiontech.dble.DbleServer;
import com.actiontech.dble.config.ErrorCode;
import com.actiontech.dble.services.mysqlauthenticate.MysqlDatabaseHandler;
import com.actiontech.dble.util.StringUtil;
import com.alibaba.druid.wall.WallProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;

public class RwSplitUserConfig extends ServerUserConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(RwSplitUserConfig.class);
    private final String dbGroup;

    public RwSplitUserConfig(UserConfig user, String tenant, WallProvider blacklist, String dbGroup) {
        super(user, tenant, blacklist);
        this.dbGroup = dbGroup;
    }

    public String getDbGroup() {
        return dbGroup;
    }


    public boolean equalsBaseInfo(RwSplitUserConfig rwSplitUserConfig) {
        return super.equalsBaseInfo(rwSplitUserConfig) &&
                StringUtil.equalsWithEmpty(this.dbGroup, rwSplitUserConfig.getDbGroup());
    }

    @Override
    public int checkSchema(String schema) {
        if (schema == null) {
            return 0;
        }
        boolean exist;
        LOGGER.info("start checkSchema");
        Set<String> schemas = new MysqlDatabaseHandler(DbleServer.getInstance().getConfig().getDbGroups()).execute(dbGroup);
        LOGGER.info("checkSchema schemas size is {}, schemas content is {}, current schema is {} ", schemas.size(), schemas, schema);
        if (DbleServer.getInstance().getSystemVariables().isLowerCaseTableNames()) {
            Optional<String> result = schemas.stream().filter(item -> StringUtil.equals(item.toLowerCase(), schema.toLowerCase())).findFirst();
            exist = result.isPresent();
        } else {
            exist = schemas.contains(schema);
        }
        if (!exist) {
            LOGGER.warn("current schemas size is {}, schemas content is {}, current schema is {} ", schemas.size(), schemas, schema);
            LOGGER.warn("dble lowerCase is {}", DbleServer.getInstance().getSystemVariables().isLowerCaseTableNames());
        }
        LOGGER.info("end checkSchema");
        return exist ? 0 : ErrorCode.ER_BAD_DB_ERROR;
    }

}
