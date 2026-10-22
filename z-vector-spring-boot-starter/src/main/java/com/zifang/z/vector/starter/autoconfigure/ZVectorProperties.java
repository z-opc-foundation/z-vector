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
 *   checkpoint-interval: 1000      # 每多少条 WAL 记录做一次快照 + WAL 截断
 *   wal:
 *     mode: sync                   # sync（默认，ack 即持久，RPO=0）/ async（性能选项，RPO≠0）
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
 *
 * <h2>{@code wal.mode} 是持久性语义，不是调优开关</h2>
 * {@code sync}（默认）= 每条写入当场 fsync ⇒ {@code upsert()} 返回即持久，<b>RPO = 0</b>；
 * {@code async} = 入队即返回、后台整批 fsync ⇒ 崩溃（kill -9 / 断电）会丢「队列中尚未落盘的那一段」
 * （实测自然窗口 1~4/126 条，人为拖慢 fsync 时可丢全批 200/200）。
 * 显式配了 {@code wal.mode} 却兑现不了（比如 {@code z-vector-storage} 不在 classpath 上），
 * 装配层会<b>让应用起不来</b>，而不是安静地退回内存存储。
 */
@ConfigurationProperties(prefix = "zvector")
public class ZVectorProperties {

    /** 存储类型: persistent / in-memory */
    private String storageType = "in-memory";

    /** 持久化目录（仅 storage-type=persistent 生效） */
    private String dataDir = "/tmp/zvector";

    /**
     * checkpoint 间隔：每累计多少条 WAL 记录做一次快照（snapshot）并截断 WAL。
     * <p>
     * 语义：<b>快照 = 崩溃恢复的起点</b>。WAL 截断后，崩溃重启只需重放「上次快照之后」的 WAL。
     * 它影响的是<b>恢复要重放的量</b>与<b>快照的写放大</b>，<b>不影响 RPO</b> ——
     * RPO 由 {@link #wal} 的模式决定（sync = 0 条）。
     * <p>
     * 默认 1000 条。
     */
    private long checkpointInterval = 1000;

    /** WAL 落盘模式（持久性语义） */
    private Wal wal = new Wal();

    /** 默认索引：3 参 createCollection 未显式指定索引时采用 */
    private DefaultIndex defaultIndex = new DefaultIndex();

    /** 服务端配置 */
    private Server server = new Server();

    /**
     * WAL 落盘模式。
     * <p>
     * {@code sync}（默认）= 每条记录一次 write + 一次 fsync ⇒ 已 ack 的写入不丢（RPO = 0）。
     * <p>
     * {@code async} = 入队即返回，后台整批 fsync ⇒ RPO ≠ 0；用它就是显式接受
     * 「崩溃会丢已 ack 的一段写入」的代价（启动日志会打警告文案）。
     */
    public static class Wal {
        /** WAL 落盘模式：sync（默认）/ async。拼错必须让应用起不来，不静默回落。 */
        private String mode = "sync";

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
    }

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

    public long getCheckpointInterval() { return checkpointInterval; }
    public void setCheckpointInterval(long checkpointInterval) { this.checkpointInterval = checkpointInterval; }

    public Wal getWal() { return wal; }
    public void setWal(Wal wal) { this.wal = wal == null ? new Wal() : wal; }

    public DefaultIndex getDefaultIndex() { return defaultIndex; }
    public void setDefaultIndex(DefaultIndex defaultIndex) {
        this.defaultIndex = defaultIndex == null ? new DefaultIndex() : defaultIndex;
    }

    public Server getServer() { return server; }
    public void setServer(Server server) { this.server = server; }
}