package com.zifang.z.vector.starter.autoconfigure;

import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.VectorStore;
import com.zifang.z.vector.core.InMemoryVectorStore;
import com.zifang.z.vector.grpc.QdrantRestServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.Locale;

/**
 * z-vector Spring Boot 自动装配。
 * <p>
 * 通过 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 注册，应用启动时自动加载。
 * <p>
 * 提供的 Bean:
 * <ul>
 *   <li>{@link VectorStore} — 内存版或持久化版</li>
 *   <li>{@link QdrantRestServer} — Qdrant 兼容 REST 服务（可选）</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * @SpringBootApplication
 * public class MyApp { }
 *
 * # application.yml
 * zvector:
 *   storage-type: persistent
 *   data-dir: /data/zvec
 *   server:
 *     port: 6334
 * }</pre>
 *
 * 然后注入:
 * <pre>{@code
 * @Autowired VectorStore vectorStore;
 * vectorStore.upsert(...);
 * }</pre>
 */
@AutoConfiguration
@ConditionalOnClass(VectorStore.class)
@EnableConfigurationProperties(ZVectorProperties.class)
public class ZVectorAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZVectorAutoConfiguration.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(VectorStore.class)
    public VectorStore vectorStore(ZVectorProperties props, Environment env) {
        VectorStore store = newStore(props, env);
        ZVectorProperties.DefaultIndex di = props.getDefaultIndex();
        String type = di.getType();
        if (type != null && !type.trim().isEmpty()) {
            IndexType indexType = parseIndexType(type);
            store.setDefaultIndex(indexType, di.getParams());
            log.info("z-vector default index for 3-arg createCollection: {} params={}",
                    indexType, di.getParams());
        }
        return store;
    }

    /**
     * 建 store。
     * <p>
     * <b>WAL 模式的兑现纪律</b>：{@code zvector.wal.mode} 是<b>持久性语义</b>，不是调优开关。
     * 因此：
     * <ol>
     *   <li>取值拼错 ⇒ 启动失败（不静默退回默认）；</li>
     *   <li>显式配了模式却兑现不了（{@code z-vector-storage} 不在 classpath 上）⇒ 启动失败
     *       —— 退回内存存储会让「我要持久 / 我要 RPO=0」这类显式要求<b>静默失效</b>；</li>
     *   <li>配了 {@code async} ⇒ 启动日志打警告文案，把代价（RPO ≠ 0）说清楚。</li>
     * </ol>
     * 没显式配模式时维持旧行为：{@code z-vector-storage} 不在 classpath 上就退回内存（只警告）。
     */
    private static VectorStore newStore(ZVectorProperties props, Environment env) {
        String mode = parseWalMode(props.getWal().getMode());
        boolean modeConfigured = env != null && env.containsProperty("zvector.wal.mode");
        long checkpointInterval = props.getCheckpointInterval();
        if (checkpointInterval <= 0) {
            throw new IllegalStateException("zvector.checkpoint-interval=" + checkpointInterval
                    + " 非法：必须是正整数（每多少条 WAL 记录做一次快照并截断 WAL）");
        }
        log.info("z-vector initializing: storage-type={}, data-dir={}, wal.mode={}, checkpoint-interval={}",
                props.getStorageType(), props.getDataDir(), mode, checkpointInterval);
        if (!"persistent".equalsIgnoreCase(props.getStorageType())) {
            return new InMemoryVectorStore();
        }
        Class<?> clazz;
        try {
            clazz = Class.forName("com.zifang.z.vector.storage.PersistentVectorStore");
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            if (modeConfigured) {
                throw new IllegalStateException("zvector.wal.mode=" + mode + " 已显式配置，"
                        + "但 com.zifang.z.vector.storage.PersistentVectorStore 不在 classpath 上 —— "
                        + "退回内存存储会让显式的持久性要求静默失效，故拒绝启动"
                        + "（请把 z-vector-storage 加进依赖）");
            }
            log.warn("PersistentVectorStore not available, fallback to in-memory: {}", e.getMessage());
            return new InMemoryVectorStore();
        }
        try {
            return newPersistentStore(clazz, props, mode);
        } catch (ReflectiveOperationException e) {
            if (modeConfigured) {
                throw new IllegalStateException("zvector.wal.mode=" + mode + " 已显式配置，"
                        + "但 PersistentVectorStore 没能按该模式建起来: " + e, e);
            }
            log.warn("PersistentVectorStore not available, fallback to in-memory: {}", e.getMessage());
            return new InMemoryVectorStore();
        }
    }

    /**
     * 用带 WAL 模式的构造器建持久化 store。
     * <p>
     * {@code z-vector-storage} 不是本 starter 的编译依赖（starter 也不该把存储实现强塞给应用），
     * 所以这里走反射；{@code WalMode} 同样按名反射取。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static VectorStore newPersistentStore(Class<?> clazz, ZVectorProperties props, String mode)
            throws ReflectiveOperationException {
        Class<?> modeClass = Class.forName("com.zifang.z.vector.storage.wal.WalMode");
        Object modeValue = Enum.valueOf((Class) modeClass.asSubclass(Enum.class),
                mode.toUpperCase(Locale.ROOT));
        if ("async".equals(mode)) {
            log.warn("zvector.wal.mode=async：WAL 走后台整批 fsync（group commit）—— upsert() 返回时"
                    + "数据可能还只在内存队列里，进程被 kill -9 / 机器断电会丢失已 ack 的写入（RPO ≠ 0，"
                    + "实测自然窗口丢 1~4/126 条，人为拖慢 fsync 时可丢全批）。"
                    + "需要「ack 即持久」（RPO = 0）请用默认的 zvector.wal.mode=sync。");
        }
        try {
            java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructor(
                    String.class, long.class, boolean.class, modeClass);
            return (VectorStore) ctor.newInstance(props.getDataDir(),
                    props.getCheckpointInterval(), Boolean.TRUE, modeValue);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("z-vector-storage 版本过旧：PersistentVectorStore 没有带 WAL 模式的"
                    + "构造器，兑现不了 zvector.wal.mode=" + mode + "（请升级 z-vector-storage）", e);
        }
    }

    /**
     * 配错一个字母必须让应用起不来，而不是安静退回默认模式 —— 后者正是这一族缺陷的原型：
     * 属性有值、日志有值、行为没有值。
     */
    private static String parseWalMode(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "sync";
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if ("sync".equals(v) || "async".equals(v)) {
            return v;
        }
        throw new IllegalStateException("zvector.wal.mode=\"" + raw + "\" 不是合法的 WAL 模式。"
                + "Supported: sync, async"
                + "（sync = 每条写入当场 fsync，ack 即持久、RPO=0；"
                + "async = 后台整批 fsync、性能更好但 RPO≠0）");
    }

    /**
     * 配错一个字母必须让应用起不来，而不是安静地退回 FLAT —— 后者正是这一族缺陷的原型：
     * 属性有值、日志有值、行为没有值。
     */
    private static IndexType parseIndexType(String raw) {
        String v = raw.trim().toUpperCase(Locale.ROOT);
        for (IndexType t : IndexType.values()) {
            if (t.name().equals(v)) return t;
        }
        throw new IllegalStateException("zvector.default-index.type=\"" + raw + "\" is not an IndexType. "
                + "Supported: " + java.util.Arrays.toString(IndexType.values()));
    }

    /**
     * REST 服务的开关按 {@code zvector.server.auto-start} 判（默认开），<b>不是</b>按端口判。
     * <p>
     * 这里原本写的是 {@code @ConditionalOnProperty(name="port", havingValue="0")} —— 端口配成 0
     * 才建这个 Bean，而 {@link ZVectorProperties.Server} 的注释和 README 都说 0 是"不启动"、
     * 6334 才是 REST 端口。两个含义正好相反：按文档配端口的应用一个 REST 端口都拿不到（静默，
     * 没有任何日志说"没启动"），而配 0 的应用会被塞一个随机端口。同时 {@code autoStart} 这个
     * 属性全仓没有任何读取方 —— 一条写了却没人认的开关，和被忽略的 Bloom 配置是同一类缺陷。
     */
    @Bean
    @ConditionalOnProperty(prefix = "zvector.server", name = "auto-start",
            havingValue = "true", matchIfMissing = true)
    public QdrantRestServerLifecycle qdrantRestServerLifecycle(VectorStore store,
                                                                ZVectorProperties props) {
        return new QdrantRestServerLifecycle(store, props.getServer().getPort());
    }

    /**
     * REST 服务生命周期管理 — 启动/关闭钩子。
     */
    public static class QdrantRestServerLifecycle {

        private static final Logger log = LoggerFactory.getLogger(QdrantRestServerLifecycle.class);

        private final VectorStore store;
        private final int port;
        private QdrantRestServer server;

        public QdrantRestServerLifecycle(VectorStore store, int port) {
            this.store = store;
            this.port = port;
        }

        @PostConstruct
        public void start() {
            if (port == 0) {
                // 属性文档里的口径："0 = 不启动"。以前 0 反而是唯一会启动的取值，且起在随机端口上。
                // 这一层和上面的 @ConditionalOnProperty(auto-start) 是**两条独立的开关**，不是重复：
                // auto-start 管"要不要这个 Bean"，这里管"端口为 0 时不许 listen"。摘掉任一条，
                // ZVectorRestAutoConfigurationTest 的 S2 / S3 各红各的（变异 M4/M7 分别验证）。
                log.info("z-vector REST API disabled (zvector.server.port=0)");
                return;
            }
            try {
                server = new QdrantRestServer(store, port);
                server.start();
                // 报真实端口：port=0 时这是内核挑的那个（QdrantRestServer.getPort() 现在会跟着变），
                // 否则日志会打出一句"started on port 0"，运维照着 0 去连什么也连不上。
                log.info("z-vector REST API started on port {}", server.getPort());
            } catch (IOException e) {
                throw new RuntimeException("Failed to start z-vector REST server on port " + port, e);
            }
        }

        @PreDestroy
        public void stop() {
            if (server != null) {
                server.stop();
                log.info("z-vector REST API stopped");
            }
        }

        /** 观测：真实监听端口；未启动时返回配置的端口值。 */
        public int getPort() {
            return server != null ? server.getPort() : port;
        }
    }
}