/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.main;

import java.util.List;
import java.util.Map;

import org.lealone.common.exceptions.ConfigException;
import org.lealone.common.logging.Logger;
import org.lealone.common.logging.LoggerFactory;
import org.lealone.common.util.ShutdownHookUtils;
import org.lealone.common.util.Utils;
import org.lealone.db.Constants;
import org.lealone.db.LealoneDatabase;
import org.lealone.db.PluggableEngine;
import org.lealone.db.SysProperties;
import org.lealone.p2p.config.Config;
import org.lealone.p2p.config.Config.PluggableEngineDef;
import org.lealone.p2p.config.ConfigLoader;
import org.lealone.p2p.config.YamlConfigLoader;
import org.lealone.p2p.server.ClusterMetaData;
import org.lealone.p2p.server.P2pServerEngine;
import org.lealone.server.ProtocolServer;
import org.lealone.server.ProtocolServerEngine;
import org.lealone.server.ProtocolServerEngineManager;
import org.lealone.sql.SQLEngine;
import org.lealone.sql.SQLEngineManager;
import org.lealone.storage.StorageEngine;
import org.lealone.storage.StorageEngineManager;
import org.lealone.transaction.TransactionEngine;
import org.lealone.transaction.TransactionEngineManager;

public class Lealone {
    private static final Logger logger = LoggerFactory.getLogger(Lealone.class);
    private static Config config;

    public static void main(String[] args) {
        logger.info("Lealone version: {}", Utils.getReleaseVersionString());

        try {
            long t = System.currentTimeMillis();

            loadConfig();

            long t1 = (System.currentTimeMillis() - t);
            t = System.currentTimeMillis();

            init();

            long t2 = (System.currentTimeMillis() - t);
            t = System.currentTimeMillis();

            start();

            long t3 = (System.currentTimeMillis() - t);
            logger.info("Total time: {} ms (Load config: {} ms, Init: {} ms, Start: {} ms)", (t1 + t2 + t3), t1, t2,
                    t3);
        } catch (Exception e) {
            logger.error("Fatal error: unable to start lealone. See log for stacktrace.", e);
            System.exit(1);
        }
    }

    private static void loadConfig() {
        ConfigLoader loader;
        String loaderClass = Config.getProperty("config.loader");
        if (loaderClass != null && Lealone.class.getResource("/" + loaderClass.replace('.', '/') + ".class") != null) {
            loader = Utils.<ConfigLoader> construct(loaderClass, "configuration loading");
        } else {
            loader = new YamlConfigLoader();
        }
        config = loader.loadConfig();
    }

    private static void init() {
        initBaseDir();
        initPluggableEngines();
        long t1 = System.currentTimeMillis();
        LealoneDatabase.getInstance(); // 提前触发对LealoneDatabase的初始化
        long t2 = System.currentTimeMillis();
        logger.info("Init lealone database: " + (t2 - t1) + "ms");
        if (config.protocol_server_engines != null) {
            for (PluggableEngineDef def : config.protocol_server_engines) {
                if (def.enabled && P2pServerEngine.NAME.equalsIgnoreCase(def.name)) {
                    ClusterMetaData.init(LealoneDatabase.getInstance().getInternalConnection());
                    break;
                }
            }
        }
    }

    private static void initBaseDir() {
        if (config.base_dir == null || config.base_dir.isEmpty())
            throw new ConfigException("base_dir must be specified and not empty");
        SysProperties.setBaseDir(config.base_dir);

        logger.info("Base dir: {}", config.base_dir);
    }

    // 初始化顺序: storage -> transaction -> sql -> protocol_server
    private static void initPluggableEngines() {
        long t1 = System.currentTimeMillis();
        List<PluggableEngineDef> pluggable_engines = config.storage_engines;
        if (pluggable_engines != null) {
            for (PluggableEngineDef def : pluggable_engines) {
                if (def.enabled) {
                    checkName("StorageEngine", def);
                    StorageEngine se = StorageEngineManager.getInstance().getEngine(def.name);
                    if (se == null) {
                        try {
                            Class<?> clz = Utils.loadUserClass(def.name);
                            se = (StorageEngine) clz.newInstance();
                            StorageEngineManager.getInstance().registerEngine(se);
                        } catch (Exception e) {
                            throw newConfigException("StorageEngine", def, e);
                        }
                    }

                    if (Config.getProperty("default.storage.engine") == null)
                        Config.setProperty("default.storage.engine", se.getName());

                    initPluggableEngine(se, def);
                }
            }
        }
        long t2 = System.currentTimeMillis();
        logger.info("Init storage engines: " + (t2 - t1) + "ms");

        pluggable_engines = config.transaction_engines;
        if (pluggable_engines != null) {
            for (PluggableEngineDef def : pluggable_engines) {
                if (def.enabled) {
                    checkName("TransactionEngine", def);
                    TransactionEngine te = TransactionEngineManager.getInstance().getEngine(def.name);
                    if (te == null) {
                        try {
                            Class<?> clz = Utils.loadUserClass(def.name);
                            te = (TransactionEngine) clz.newInstance();
                            TransactionEngineManager.getInstance().registerEngine(te);
                        } catch (Exception e) {
                            te = TransactionEngineManager.getInstance()
                                    .getEngine(Constants.DEFAULT_TRANSACTION_ENGINE_NAME);
                            if (te == null)
                                throw newConfigException("TransactionEngine", def, e);
                        }
                    }

                    if (Config.getProperty("default.transaction.engine") == null)
                        Config.setProperty("default.transaction.engine", te.getName());

                    initPluggableEngine(te, def);
                }
            }
        }
        long t3 = System.currentTimeMillis();
        logger.info("Init transaction engines: " + (t3 - t2) + "ms");

        pluggable_engines = config.sql_engines;
        if (pluggable_engines != null) {
            for (PluggableEngineDef def : pluggable_engines) {
                if (def.enabled) {
                    checkName("SQLEngine", def);
                    SQLEngine se = SQLEngineManager.getInstance().getEngine(def.name);
                    if (se == null) {
                        try {
                            Class<?> clz = Utils.loadUserClass(def.name);
                            se = (SQLEngine) clz.newInstance();
                            SQLEngineManager.getInstance().registerEngine(se);
                        } catch (Exception e) {
                            throw newConfigException("SQLEngine", def, e);
                        }
                    }

                    if (Config.getProperty("default.sql.engine") == null)
                        Config.setProperty("default.sql.engine", se.getName());
                    initPluggableEngine(se, def);
                }
            }
        }
        long t4 = System.currentTimeMillis();
        logger.info("Init sql engines: " + (t4 - t3) + "ms");

        pluggable_engines = config.protocol_server_engines;
        if (pluggable_engines != null) {
            for (PluggableEngineDef def : pluggable_engines) {
                if (def.enabled) {
                    checkName("ProtocolServerEngine", def);
                    ProtocolServerEngine pse = ProtocolServerEngineManager.getInstance().getEngine(def.name);
                    if (pse == null) {
                        try {
                            Class<?> clz = Utils.loadUserClass(def.name);
                            pse = (ProtocolServerEngine) clz.newInstance();
                            ProtocolServerEngineManager.getInstance().registerEngine(pse);
                        } catch (Exception e) {
                            throw newConfigException("ProtocolServerEngine", def, e);
                        }
                    }
                    // 如果ProtocolServer的配置参数中没有指定host，那么就取listen_address的值
                    if (!def.getParameters().containsKey("host") && config.listen_address != null)
                        def.getParameters().put("host", config.listen_address);
                    initPluggableEngine(pse, def);
                }
            }
        }
        long t5 = System.currentTimeMillis();
        logger.info("Init protocol server engines: " + (t5 - t4) + "ms");
    }

    private static void checkName(String engineName, PluggableEngineDef def) {
        if (def.name == null || def.name.trim().isEmpty())
            throw new ConfigException(engineName + ".name is missing.");
    }

    private static ConfigException newConfigException(String engineName, PluggableEngineDef def, Exception e) {
        return new ConfigException(engineName + " '" + def.name + "' can not found", e);
    }

    private static void initPluggableEngine(PluggableEngine pe, PluggableEngineDef def) {
        Map<String, String> parameters = def.getParameters();
        if (!parameters.containsKey("base_dir"))
            parameters.put("base_dir", config.base_dir);

        pe.init(parameters);
    }

    private static void start() throws Exception {
        startProtocolServers();
    }

    private static void startProtocolServers() throws Exception {
        List<PluggableEngineDef> protocol_server_engines = config.protocol_server_engines;
        if (protocol_server_engines != null) {
            for (PluggableEngineDef def : protocol_server_engines) {
                if (def.enabled) {
                    ProtocolServerEngine pse = ProtocolServerEngineManager.getInstance().getEngine(def.name);
                    ProtocolServer protocolServer = pse.getProtocolServer();
                    startProtocolServer(protocolServer, def.getParameters());
                }
            }
        }
    }

    private static void startProtocolServer(final ProtocolServer server, Map<String, String> parameters)
            throws Exception {
        server.setServerEncryptionOptions(config.server_encryption_options);
        server.start();
        final String name = server.getName();
        ShutdownHookUtils.addShutdownHook(server, () -> {
            server.stop();
            logger.info(name + " stopped");
        });
        logger.info(name + " started, host: {}, port: {}", server.getHost(), server.getPort());
    }
}
