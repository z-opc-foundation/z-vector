package com.zifang.z.vector.starter.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * z-vector 配置属性 — 通过 application.yml 配置。
 *
 * <h2>示例配置</h2>
 * <pre>{@code
 * zvector:
 *   storage-type: persistent       # persistent / in-memory
 *   data-dir: /data/zvector
 *   server:
 *     port: 6334                   # REST API 端口（0 禁用）
 *   default-index:
 *     type: HNSW
 *     params:
 *       M: 16
 *       efConstruction: 200
 * }</pre>
 */
@ConfigurationProperties(prefix = "zvector")
public class ZVectorProperties {

    /** 存储类型: persistent / in-memory */
    private String storageType = "in-memory";

    /** 持久化目录（仅 storage-type=persistent 生效） */
    private String dataDir = "/tmp/zvector";

    /** 默认索引类型 */
    private String defaultIndex = "HNSW";

    /** 默认索引参数 */
    private Map<String, Object> defaultIndexParams = new HashMap<>();

    /** 服务端配置 */
    private Server server = new Server();

    public static class Server {
        /** REST 服务端口（0 = 不启动） */
        private int port = 6334;
        /** 是否自动启动 */
        private boolean autoStart = true;

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public boolean isAutoStart() { return autoStart; }
        public void setAutoStart(boolean autoStart) { this.autoStart = autoStart; }
    }

    public String getStorageType() { return storageType; }
    public void setStorageType(String storageType) { this.storageType = storageType; }

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }

    public String getDefaultIndex() { return defaultIndex; }
    public void setDefaultIndex(String defaultIndex) { this.defaultIndex = defaultIndex; }

    public Map<String, Object> getDefaultIndexParams() { return defaultIndexParams; }
    public void setDefaultIndexParams(Map<String, Object> defaultIndexParams) {
        this.defaultIndexParams = defaultIndexParams;
    }

    public Server getServer() { return server; }
    public void setServer(Server server) { this.server = server; }
}