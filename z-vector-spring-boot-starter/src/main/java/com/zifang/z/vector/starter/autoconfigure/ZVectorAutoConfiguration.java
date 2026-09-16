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

    @Bean
    @ConditionalOnProperty(prefix = "zvector.server", name = "port", havingValue = "0", matchIfMissing = false)
    public QdrantRestServerLifecycle qdrantRestServerLifecycle(VectorStore store,
                                                                ZVectorProperties props) {
        return new QdrantRestServerLifecycle(store, props.getServer().getPort());
    }

    /**
     * REST 服务生命周期管理 — 启动/关闭钩子。
     */
    public static class QdrantRestServerLifecycle {

        private final VectorStore store;
        private final int port;
        private QdrantRestServer server;

        public QdrantRestServerLifecycle(VectorStore store, int port) {
            this.store = store;
            this.port = port;
        }

        @PostConstruct
        public void start() {
            try {
                server = new QdrantRestServer(store, port);
                server.start();
                log.info("z-vector REST API started on port {}", port);
            } catch (IOException e) {
                throw new RuntimeException("Failed to start z-vector REST server", e);
            }
        }

        @PreDestroy
        public void stop() {
            if (server != null) {
                server.stop();
                log.info("z-vector REST API stopped");
            }
        }
    }
}