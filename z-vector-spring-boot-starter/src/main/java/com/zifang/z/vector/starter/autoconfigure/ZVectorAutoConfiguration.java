package com.zifang.z.vector.starter.autoconfigure;

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

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;

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
    public VectorStore vectorStore(ZVectorProperties props) {
        log.info("z-vector initializing: storage-type={}, data-dir={}",
                props.getStorageType(), props.getDataDir());
        if ("persistent".equalsIgnoreCase(props.getStorageType())) {
            try {
                Class<?> clazz = Class.forName("com.zifang.z.vector.storage.PersistentVectorStore");
                return (VectorStore) clazz.getDeclaredConstructor(String.class)
                        .newInstance(props.getDataDir());
            } catch (Exception e) {
                log.warn("PersistentVectorStore not available, fallback to in-memory: {}", e.getMessage());
                return new InMemoryVectorStore();
            }
        }
        return new InMemoryVectorStore();
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