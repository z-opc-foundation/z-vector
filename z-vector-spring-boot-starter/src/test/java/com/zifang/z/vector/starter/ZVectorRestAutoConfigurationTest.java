package com.zifang.z.vector.starter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.vector.starter.autoconfigure.ZVectorAutoConfiguration;
import com.zifang.z.vector.starter.autoconfigure.ZVectorAutoConfiguration.QdrantRestServerLifecycle;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REST 服务的<b>装配契约</b>尺 —— 改前 README（{@code zvector.server.port: 6334 # REST API 端口}）
 * 和 {@code ZVectorProperties.Server} 的注释（"0 = 不启动"）说的是同一件事，代码做的正好相反：
 *
 * <pre>
 *   &#64;ConditionalOnProperty(name = "port", havingValue = "0")   // 端口填 0 才建这个 Bean
 * </pre>
 *
 * 于是照文档配 6334 的应用<b>一个 REST 端口都拿不到</b>，而且没有任何日志说"我没启动"；配 0 反而
 * 会起在一个随机端口上。{@code autoStart} 这个属性则全仓没人读（和被忽略的 Bloom 配置同一类）。
 * <p>
 * 这一支把三条口径钉住：<b>给了真端口就真的在服务</b>（不是只建了 Bean）、<b>auto-start=false
 * 就不建 Bean</b>、<b>port=0 就是不启动</b>。三支都从"能不能连上"这个外部事实来判，
 * 不看 Bean 建没建 —— Bean 建了却没 listen、或者 listen 在别的端口上，都会被同一把尺抓到。
 */
class ZVectorRestAutoConfigurationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 每个用例都要真的进到 lambda 里。{@code ApplicationContextRunner} 在上下文启动失败时
     * 会把失败"攒着"，consumer 一条都不执行 —— 那种绿是空跑，不是通过。
     */
    private static void requireReached(AtomicBoolean ran) {
        assertTrue(ran.get(), "上下文没有起来，里面的断言一条都没跑 ⇒ 这一例什么都没测");
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ZVectorAutoConfiguration.class));
    }

    /** 占一个内核认为空闲的端口，立刻放开交给被测服务去绑。 */
    private static int reservePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    private static Map<String, Object> getStatus(ApplicationContext ctx) throws IOException {
        int port = ctx.getBean(QdrantRestServerLifecycle.class).getPort();
        HttpURLConnection c = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + "/collections").openConnection();
        c.setConnectTimeout(3_000);
        c.setReadTimeout(3_000);
        try {
            assertEquals(200, c.getResponseCode(), "端口 " + port + " 上没有 REST 服务在应答");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            try (InputStream in = c.getInputStream()) {
                while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.readValue(bos.toByteArray(), Map.class);
            return parsed;
        } finally {
            c.disconnect();
        }
    }

    private static int liveRestThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("z-vector-rest-") && t.isAlive()) n++;
        }
        return n;
    }

    @Test
    void aConfiguredPortActuallyServesRequests() throws Exception {
        int port = reservePort();
        AtomicBoolean ran = new AtomicBoolean();
        runner().withPropertyValues("zvector.storage-type=in-memory",
                "zvector.server.port=" + port)
                .run(ctx -> {
                    assertFalse(ctx.getBeanNamesForType(QdrantRestServerLifecycle.class).length == 0,
                            "配了真端口就该有 REST 生命周期 Bean（改前：只有 port=0 才有）");
                    QdrantRestServerLifecycle bean = ctx.getBean(QdrantRestServerLifecycle.class);
                    assertEquals(port, bean.getPort(),
                            "Bean 报的端口必须就是配置的那个 —— 否则日志和实际监听是两回事");
                    assertEquals("ok", getStatus(ctx).get("status"),
                            "配置的端口必须真的在服务 —— 建了 Bean 不等于 listen 成功");
                    ran.set(true);
                });
        requireReached(ran);
    }

    @Test
    void autoStartFalseMeansNoServerAtAll() {
        int baseline = liveRestThreads();
        AtomicBoolean ran = new AtomicBoolean();
        runner().withPropertyValues("zvector.storage-type=in-memory",
                "zvector.server.auto-start=false",
                "zvector.server.port=6334")
                .run(ctx -> {
                    assertEquals(0, ctx.getBeanNamesForType(QdrantRestServerLifecycle.class).length,
                            "auto-start=false 是一条开关，不是装饰：改前全仓没有任何地方读它");
                    assertEquals(baseline, liveRestThreads(), "关掉之后不该新增工作线程");
                    ran.set(true);
                });
        requireReached(ran);
    }

    @Test
    void portZeroDoesNotListenAnywhere() throws Exception {
        int before = liveRestThreads();
        AtomicBoolean ran = new AtomicBoolean();
        runner().withPropertyValues("zvector.storage-type=in-memory",
                "zvector.server.port=0")
                .run(ctx -> {
                    QdrantRestServerLifecycle bean = ctx.getBean(QdrantRestServerLifecycle.class);
                    assertEquals(0, bean.getPort(),
                            "port=0 按属性文档是\"不启动\"，Bean 不该占住任何端口");
                    assertEquals(before, liveRestThreads(),
                            "port=0 时改前会起在随机端口上（并且日志打 \"started on port 0\"）");
                    ran.set(true);
                });
        requireReached(ran);
    }

    @Test
    void closingTheContextReleasesTheServer() throws Exception {
        int port = reservePort();
        int baseline = liveRestThreads();
        AtomicBoolean ran = new AtomicBoolean();
        runner().withPropertyValues("zvector.storage-type=in-memory",
                "zvector.server.port=" + port)
                .run(ctx -> {
                    assertEquals("ok", getStatus(ctx).get("status"));
                    assertTrue(liveRestThreads() > baseline,
                            "前置：跑过请求之后必须已经有 worker 线程，否则下面的归零是空跑");
                    ran.set(true);
                });
        requireReached(ran);
        // runner 在 lambda 之后 close() 上下文 → @PreDestroy stop()
        long deadline = System.currentTimeMillis() + 5_000;
        int left = liveRestThreads();
        while (left > baseline && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            left = liveRestThreads();
        }
        assertEquals(baseline, left,
                "上下文关闭之后本服务的 worker 必须全部退出（stop() 不关 setExecutor 交出去的池就漏）");
        try (ServerSocket rebind = new ServerSocket(port)) {
            assertEquals(port, rebind.getLocalPort(), "上下文关闭后端口应可重新绑定");
        }
    }
}
