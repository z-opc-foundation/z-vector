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
 *     auto-start: true             # false = 连 REST 生命周期 Bean 都不建
 *   default-index:
 *     type: HNSW                   # 留空 = 不覆盖，集合走 store 内置的 FLAT
 *     params:
 *       M: 16
 *       efConstruction: 200
 * }</pre>
 *
 * <h2>{@code default-index} 为什么是嵌套对象</h2>
 * 这一族此前是 {@code String defaultIndex} + 兄弟键 {@code default-index-params}，而 README 写的
 * 是 {@code default-index: {type, params}} 那种嵌套形状 —— 探针实测：按 README 写一个键都绑不上
 * （{@code ignoreUnknownFields} 默认开着，静默丢掉），按扁平写倒是能绑进属性对象，但<b>全仓没有
 * 一个读取方</b>，建出来的集合永远是 FLAT。和被忽略的 Bloom 配置、没人读的 {@code auto-start}
 * 是同族缺陷：广告出去的开关不兑现。现在形状对齐 README，并由
 * {@link ZVectorAutoConfiguration} 交给 store 兑现。
 */
@ConfigurationProperties(prefix = "zvector")
public class ZVectorProperties {

    /** 存储类型: persistent / in-memory */
    private String storageType = "in-memory";

    /** 持久化目录（仅 storage-type=persistent 生效） */
    private String dataDir = "/tmp/zvector";

    /** 默认索引：3 参 createCollection 未显式指定索引时采用 */
    private DefaultIndex defaultIndex = new DefaultIndex();

    /** 服务端配置 */
    private Server server = new Server();

    public static class DefaultIndex {
        /** 索引类型：FLAT / HNSW / IVF；留空表示不覆盖 store 的内置默认 */
        private String type;
        /**
         * 传给该索引的参数。HNSW 认 {@code M} / {@code efConstruction} / {@code efSearch}，
         * IVF 认 {@code nlist} / {@code nprobe} / {@code maxIter}（见 IndexFactory）。
         */
        private Map<String, Object> params = new HashMap<>();

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public Map<String, Object> getParams() { return params; }

        /**
         * 注意：Spring 绑定 {@code params.*} 这样的 Map 子键时走的是 {@link #getParams()} 拿到的活
         * map 往里 put，**不会调这个 setter**（变异实测：把它摘成空方法，绑定契约一条都不会红）。
         * 所以它只对编程式赋值有意义；要验配置是否真落进来，钉的是 getParams() 那条路径。
         */
        public void setParams(Map<String, Object> params) {
            this.params = params == null ? new HashMap<String, Object>() : params;
        }
    }

    public static class Server {
        /** REST 服务端口（0 = 不启动；要"内核帮我挑一个"请直接用 {@code QdrantRestServer}，装配层只认 0=关） */
        private int port = 6334;
        /** 是否自动启动（关掉就不会创建 REST 生命周期 Bean） */
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

    public DefaultIndex getDefaultIndex() { return defaultIndex; }
    public void setDefaultIndex(DefaultIndex defaultIndex) {
        this.defaultIndex = defaultIndex == null ? new DefaultIndex() : defaultIndex;
    }

    public Server getServer() { return server; }
    public void setServer(Server server) { this.server = server; }
}