package com.zifang.z.vector.starter.host;

import com.zifang.z.vector.core.InMemoryVectorStore;
import com.zifang.z.vector.grpc.QdrantRestServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * z-vector Qdrant REST server 内嵌启动器.
 * <p>
 * z-vector-spring-boot-starter 1.0.1 的 {@code @ConditionalOnProperty(port=0, matchIfMissing=false)} 是倒置 bug —
 * 只在 {@code zvector.server.port=0} 才注册 QdrantRestServerLifecycle, 跟设计意图 (port!=0 才启 REST) 相反.
 * <p>
 * 这里自定义 {@link QdrantRestServer} bean:
 * <ul>
 *   <li>用 {@code InMemoryVectorStore} (z-vector-core 提供) 当 store, bean name 重命名为 {@code zVectorVectorStore}
 *       — 避免与 z-agent-knowledge 的同名 vectorStore bean (类型不同) 冲突</li>
 *   <li>Async 启 QdrantRestServer 在 6334 端口, 提供 Qdrant REST API (collection/upsert/search)</li>
 *   <li>JVM shutdown hook 优雅关闭</li>
 * </ul>
 *
 * <p>同时: {@code ZVectorAutoConfiguration} 保留装载, 它的 {@code @ConditionalOnMissingBean(VectorStore)}
 * 看到我们已注册 z-vector.api.VectorStore 就跳过自带的 vectorStore bean — 不污染 z-agent-knowledge 的同名 bean.
 */
@Configuration
@ConditionalOnProperty(prefix = "z.vector", name = "enabled", havingValue = "true")
public class ZVectorEmbeddedServerConfig {

    private static final Logger log = LoggerFactory.getLogger(ZVectorEmbeddedServerConfig.class);

    @Value("${z.vector.server.host:0.0.0.0}")
    private String host;

    @Value("${z.vector.server.port:6334}")
    private int port;

    @Value("${z.vector.storage-type:in-memory}")
    private String storageType;

    private static final AtomicBoolean SERVER_STARTED = new AtomicBoolean(false);
    private volatile QdrantRestServer server;

    /**
     * z-vector 自己的 VectorStore bean, 用重命名 + 显式类型避免与 z-agent-knowledge 的同名 bean 冲突.
     * ZVectorAutoConfiguration 的 @ConditionalOnMissingBean(VectorStore.class) 看到这个 bean 存在就跳过.
     */
    @Bean(name = "zVectorVectorStore")
    public com.zifang.z.vector.api.VectorStore zVectorVectorStore() {
        // 当前只支持 in-memory, persistent 暂不引入额外依赖
        if ("persistent".equalsIgnoreCase(storageType)) {
            log.warn("[z-vector] storage-type=persistent not supported in this build, fallback to in-memory");
        }
        return new InMemoryVectorStore();
    }

    /**
     * 启 Qdrant REST server. 通过 z-vector.api.VectorStore bean (上面定义的 zVectorVectorStore).
     * 走 ApplicationStartedEvent 由 caller 调用 start() — 简化起见我们用 @Bean 直接返回 lifecycle 对象.
     */
    @Bean
    public QdrantRestServerLifecycle zVectorQdrantRestLifecycle(
            @org.springframework.beans.factory.annotation.Qualifier("zVectorVectorStore")
            com.zifang.z.vector.api.VectorStore store) {
        return new QdrantRestServerLifecycle(store);
    }

    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed(ContextRefreshedEvent event) {
        // Spring 容器已 ready, 启 Qdrant REST server
        QdrantRestServerLifecycle lifecycle = event.getApplicationContext()
                .getBean(QdrantRestServerLifecycle.class);
        lifecycle.start();
    }

    public class QdrantRestServerLifecycle {
        private final com.zifang.z.vector.api.VectorStore store;
        /**
         * 只有 {@code server.start()} **真的返回了**才置位。
         * L3 的 {@code QdrantRestServer.getPort()} 返回的是**配置值**，对象一构造就非零 ——
         * 拿它当"起起来了"会在端口被占时骗人 (z-cache :6379 上已经实测发生过这种"看起来在跑")。
         */
        private volatile boolean bound = false;

        public QdrantRestServerLifecycle(com.zifang.z.vector.api.VectorStore store) {
            this.store = store;
        }

        public void start() {
            if (!SERVER_STARTED.compareAndSet(false, true)) {
                return;
            }
            try {
                server = new QdrantRestServer(store, port);
                Thread t = new Thread(() -> {
                    try {
                        server.start();
                        bound = true;
                        log.info("[z-vector] QdrantRestServer started on {}:{} (bean=zVectorVectorStore)",
                                host, port);
                    } catch (Exception e) {
                        log.error("[z-vector] QdrantRestServer start failed (port {} busy?)", port, e);
                    }
                }, "z-vector-qdrant-rest");
                t.setDaemon(true);
                t.start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    log.info("[z-vector] shutdown hook stopping QdrantRestServer...");
                    if (server != null) server.stop();
                }, "z-vector-shutdown"));
            } catch (Exception e) {
                log.error("[z-vector] failed to start QdrantRestServer", e);
                throw new RuntimeException(e);
            }
        }

        /** 内嵌 REST 实际 bind 成功的端口；没 bind 成就是 0（调用方须据此显式失败，不许猜端口） */
        public int getBoundPort() {
            return bound ? port : 0;
        }

        /** 此刻还能不能连上 —— bind 成功过但后来被关掉/进程换过，这一层才测得出来 */
        public boolean isAcceptingConnections() {
            if (!bound) return false;
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 300);
                return true;
            } catch (java.io.IOException e) {
                return false;
            }
        }

        public boolean isRunning() {
            return bound;
        }

        public com.zifang.z.vector.api.VectorStore getStore() {
            return store;
        }
    }
}