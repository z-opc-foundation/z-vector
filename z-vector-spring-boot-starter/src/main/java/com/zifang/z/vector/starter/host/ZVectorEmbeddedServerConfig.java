package com.zifang.z.vector.starter.host;

import com.zifang.z.vector.core.InMemoryVectorStore;
import com.zifang.z.vector.grpc.QdrantRestServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

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
 *
 * <p><b>【TASK-20260925-023】bind 失败不许再只有一行日志</b>:<br>
 * {@code QdrantRestServer.start()} 在构造函数里就完成 bind, 端口被占时抛 {@code java.net.BindException},
 * 而那个异常原来落在 daemon 线程的 {@code catch (Exception e)} 里 —— 只打一行
 * {@code log.error("[z-vector] QdrantRestServer start failed (port {} busy?)", port, e)},
 * 之后 {@code bound} 永远 false, 而**没有任何人**会去看那个字段:
 * 进程健康、页面照常打开, 只是"没有数据" (TASK-008 当年就是因为踩过它才建了 {@code /api/vector/__instance})。
 * ⇒ 现在失败会落成三样东西:
 * <ol>
 *   <li><b>可查询的状态</b>: {@link #getBindError()} / {@link #isBindFailed()} / {@link #getBoundPort()}。</li>
 *   <li><b>监控面变红</b>: 具名 health 组件 {@code zVectorEmbedded} (本 starter 的 pom 已带 actuator)。</li>
 *   <li><b>启动横幅打一行</b>: 自检 runner 打完横幅后把 readiness 打成 {@code REFUSING_TRAFFIC};
 *       非零退出码的开关 {@value #FAILFAST_PROPERTY} <b>默认关</b>。</li>
 * </ol>
 */
@Configuration
@ConditionalOnProperty(prefix = "z.vector", name = "enabled", havingValue = "true")
public class ZVectorEmbeddedServerConfig {

    private static final Logger log = LoggerFactory.getLogger(ZVectorEmbeddedServerConfig.class);

    /**
     * 横幅行的统一前缀 —— 三个中间件 (cache / graph / vector) 用同一句,
     * 运维只要 grep 这一个串就能把"内嵌面没起来"从启动日志里捞出来。
     */
    public static final String SELFCHECK_TAG = "[EMBEDDED-SELFCHECK]";

    /** 启动自检失败时是否让进程以非零退出码退出。**默认关** —— 本地多实例并存是正常形态, 不能因此起不来。 */
    public static final String FAILFAST_PROPERTY = "z.embedded.selfcheck.failfast";

    /** bind 结论窗口: 内嵌 bind 在 daemon 线程里做, 自检最多等这么久拿结论。 */
    private static final long BIND_SETTLE_TIMEOUT_MS =
            Long.getLong("z.vector.server.bind-settle-timeout-ms", 5000L);

    private static final AtomicBoolean SERVER_STARTED = new AtomicBoolean(false);
    private static volatile QdrantRestServer server;

    /**
     * bind 结论 —— <b>失败是一等公民</b> (TASK-20260925-023)。
     * 原来只有实例字段 {@code bound} (false = 没起来), 但**没有任何人**会去看它:
     * 进程健康、页面照常打开, 只是"没有数据"。
     */
    private static volatile boolean bindSucceeded = false;
    private static volatile String bindError = null;
    /** 内嵌 REST 实际 bind 成功的端口; 没 bind 成就是 0 */
    private static volatile int boundPort = 0;
    /** 这次尝试 bind 的地址 —— 失败时横幅要指名道姓说"哪个端口没起来" */
    private static volatile String attemptedHost = null;
    private static volatile int attemptedPort = 0;

    /**
     * REST server 的 bind 地址。
     * <p>
     * 缺省必须是<b>回环</b>：这个 REST 面<b>没有鉴权</b>，绑到通配地址上就等于
     * 把整个库裸奔给 LAN。改前这里默认 {@code 0.0.0.0}，而且读出来的值<b>根本没传给</b>
     * {@link QdrantRestServer} —— 配置项读了个寂寞，运行时依然是通配。
     */
    @Value("${z.vector.server.host:127.0.0.1}")
    private String host;

    @Value("${z.vector.server.port:6334}")
    private int port;

    @Value("${z.vector.storage-type:in-memory}")
    private String storageType;

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
            attemptedHost = host;
            attemptedPort = port;
            try {
                server = new QdrantRestServer(store, host, port);
                Thread t = new Thread(() -> {
                    try {
                        server.start();
                        bound = true;
                        bindSucceeded = true;
                        boundPort = port;
                        log.info("[z-vector] QdrantRestServer started on {}:{} (bean=zVectorVectorStore)",
                                host, port);
                    } catch (Throwable e) {
                        // ★ TASK-20260925-023: 原来只有一行 log.error, 之后**没有任何人**会知道内嵌面没起来 ——
                        // 进程健康、页面照常打开, 只是"没有数据"。失败必须落成可查询的状态。
                        recordBindFailure(e);
                        log.error("[z-vector] QdrantRestServer start FAILED on {}:{} —— 内嵌实例**没有起来**。"
                                        + "若该端口已被别的进程占着, 页面只会显示\"没有数据\", 看不出\"实例没起\"。"
                                        + "查属主: lsof -nP -iTCP:{} -sTCP:LISTEN",
                                host, port, port, e);
                    }
                }, "z-vector-qdrant-rest");
                t.setDaemon(true);
                t.start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    log.info("[z-vector] shutdown hook stopping QdrantRestServer...");
                    if (server != null) server.stop();
                }, "z-vector-shutdown"));
            } catch (Throwable e) {
                recordBindFailure(e);
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
                // 连 host 本身，不再写死 127.0.0.1：host 现在真的生效了，
                // 写死回环会在"绑到具体网卡地址"时误报为"没起来"。
                s.connect(new java.net.InetSocketAddress(
                        com.zifang.z.vector.grpc.QdrantRestServer.normalizeHost(host), port), 300);
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

    // ============================================================
    // 监控面: bind 状态 + 具名 health 组件 + 启动自检 runner
    // ============================================================

    /** 记一次 bind 失败 (只记第一次 —— 后续同类错误不许覆盖首因) */
    private static void recordBindFailure(Throwable t) {
        if (bindError == null) {
            bindError = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 内嵌实例**真的**在 listen 吗 (与"我们试过"是两回事) */
    public static boolean isEmbeddedRunning() {
        return bindSucceeded;
    }

    /** 内嵌 REST 实际 bind 成功的端口; 没 bind 成就是 0 */
    public static int getBoundPort() {
        return bindSucceeded ? boundPort : 0;
    }

    /** bind 失败的原文; null = 没有失败记录 (**不代表成功**, 要看 {@link #isEmbeddedRunning()}) */
    public static String getBindError() {
        return bindError;
    }

    /** 内嵌实例是不是明确 bind 失败了 */
    public static boolean isBindFailed() {
        return bindError != null;
    }

    /** 这次尝试 bind 的端口 (失败时横幅要指名道姓) */
    public static int getAttemptedPort() {
        return attemptedPort;
    }

    /** 这次尝试 bind 的地址 */
    public static String getAttemptedHost() {
        return attemptedHost;
    }

    /** 配置端口上此刻是否有**任意**监听者 (用于区分"没人"和"是别人") */
    private static boolean tcpReachable(String h, int p) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(
                    com.zifang.z.vector.grpc.QdrantRestServer.normalizeHost(h), p), 300);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /**
     * 具名 health 组件 {@code zVectorEmbedded} —— 本 starter 的 pom 已带 actuator, 直接可用。
     */
    @Configuration
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    public static class EmbeddedHealthConfiguration {

        /**
         * 具名 DOWN 组件: {@code zVectorEmbedded}。
         *
         * <p>绑不上 ⇒ DOWN (不是 UNKNOWN): "内嵌面没起来"是一个**确定的坏**, 不是"没测"。
         */
        @Bean(name = "zVectorEmbedded")
        public org.springframework.boot.actuate.health.HealthIndicator zVectorEmbeddedHealthIndicator() {
            return new org.springframework.boot.actuate.health.HealthIndicator() {
                @Override
                public org.springframework.boot.actuate.health.Health health() {
                    if (isEmbeddedRunning()) {
                        return org.springframework.boot.actuate.health.Health.up()
                                .withDetail("boundPort", Integer.valueOf(getBoundPort()))
                                .withDetail("attemptedHost", String.valueOf(attemptedHost))
                                .withDetail("attemptedPort", Integer.valueOf(attemptedPort))
                                .build();
                    }
                    return org.springframework.boot.actuate.health.Health.down()
                            .withDetail("boundPort", Integer.valueOf(0))
                            .withDetail("attemptedHost", String.valueOf(attemptedHost))
                            .withDetail("attemptedPort", Integer.valueOf(attemptedPort))
                            .withDetail("bindError", String.valueOf(bindError))
                            .withDetail("selfCheckTag", SELFCHECK_TAG)
                            .build();
                }
            };
        }
    }

    /**
     * 启动自检: boot 完成后拨自己的内嵌端口, 属主不对就在横幅上打一行。
     *
     * <p>默认只报错不退出 (开关 {@value #FAILFAST_PROPERTY} 默认关):
     * 本地多实例并存是正常形态, 不能因为别人占了端口就起不来。
     * 但**监控面必须变红** —— 所以这里同时把 readiness 打成 {@code REFUSING_TRAFFIC}。
     */
    @Bean
    public org.springframework.boot.ApplicationRunner zVectorEmbeddedSelfCheckRunner(
            final ApplicationContext applicationContext,
            final Environment environment) {
        return new org.springframework.boot.ApplicationRunner() {
            @Override
            public void run(org.springframework.boot.ApplicationArguments args) {
                awaitBindOutcome();
                if (isEmbeddedRunning()) {
                    log.info("{} [OK] z-vector 内嵌实例已 bind {}:{} —— 内嵌面可用",
                            SELFCHECK_TAG, attemptedHost, getBoundPort());
                    System.out.println(SELFCHECK_TAG + " [OK] z-vector embedded instance bound "
                            + attemptedHost + ":" + getBoundPort());
                    return;
                }
                int p = attemptedPort > 0 ? attemptedPort : 6334;
                boolean foreign = tcpReachable(attemptedHost == null ? "127.0.0.1" : attemptedHost, p);
                String msg = SELFCHECK_TAG + " [DOWN] z-vector 内嵌实例**没有起来** (尝试 bind "
                        + attemptedHost + ":" + p + ", 失败原因: " + bindError + ")。"
                        + (foreign
                        ? "该端口**可以连上** ⇒ 应答者是**别的进程**, 不是本 JVM —— "
                        + "页面会显示\"没有数据\", 那不是数据问题, 是实例没起。"
                        : "该端口连不上 ⇒ 本 JVM 的内嵌实例确实没 bind 上。")
                        + "查属主: lsof -nP -iTCP:" + p + " -sTCP:LISTEN";
                log.error(msg);
                System.out.println(msg);
                AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC);
                if (isFailFast(environment)) {
                    throw new IllegalStateException(msg
                            + " —— 开关 " + FAILFAST_PROPERTY + "=true ⇒ 进程以非零退出码退出。");
                }
            }
        };
    }

    /** 等 bind 有结论 (内嵌 bind 在 daemon 线程里做) */
    private static void awaitBindOutcome() {
        long deadline = System.currentTimeMillis() + BIND_SETTLE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (bindSucceeded || bindError != null) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** fail-fast 开关: System property 优先 (static 块也能读到), 再看 Spring 环境。 */
    public static boolean isFailFast(Environment environment) {
        String v = System.getProperty(FAILFAST_PROPERTY);
        if (v == null && environment != null) {
            v = environment.getProperty(FAILFAST_PROPERTY);
        }
        return Boolean.parseBoolean(v);
    }
}