package com.zifang.z.vector.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.vector.api.VectorStore;
import com.zifang.z.vector.core.InMemoryVectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code QdrantRestServer} 的<b>端口与生命周期</b>尺 —— 钉三件改前各自都错的事：
 *
 * <ol>
 *   <li>{@code getPort()} 改前返回的是<b>构造时传进去的那个数</b>。传 0（"内核帮我挑"）时它就一直
 *       报 0，日志里那句 "started on port 0" 是假的，调用方拿不到能连的地址；</li>
 *   <li>{@code stop()} 改前只调 {@code HttpServer.stop()}，<b>不关自己 setExecutor() 交出去的那个
 *       8 线程池</b> —— JDK 的 HttpServer 不会替调用方关外部传入的 executor，所以每起停一次就漏一批
 *       worker，而且这些线程还是非 daemon（默认工厂），JVM 退出会被它们拖住；</li>
 *   <li>端口"释放"要有独立的证据，否则上面两条即使做对了也可能只是没绑上。</li>
 * </ol>
 *
 * <p>为什么工作线程要起名：{@code HttpServer} 的 worker 是<b>按需</b>创建的，光 start() 之后池里
 * 一条线程都没有 —— "stop 之后数不到线程"在这种情况下是空跑。所以 T2 先真的发几个请求、
 * <b>断言线程数确实涨上去了</b>（前置条件自己钉，见 {@link #restThreads()} 的注释），再要求
 * stop() 把它归零。名字本身也才是要钉的东西：换成默认的 {@code pool-N-thread-M}，这堆线程和
 * 同一 JVM 里别的池子无从区分，第 2 条缺陷在测试里就根本观测不到。
 */
class QdrantRestServerPortLifecycleTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private VectorStore store;
    private QdrantRestServer server;

    @AfterEach
    void tearDown() {
        // 有上限：JDK 8 的 HttpServer.stop() 在无请求在飞时会很快返回；这里再给一次显式关闭，
        // 保证测试之间不互相背着线程（背不动就是 T2 的锅，让它红而不是让下一个测试变慢）。
        if (server != null) server.stop();
        if (store != null) store.close();
    }

    /**
     * 本服务的 worker 线程数。只数带我们给的名字前缀的那些 —— 全局线程数会随同一 JVM 里
     * 其它测试的池子漂，那种尺子中不了这一条缺陷。
     */
    private static int restThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("z-vector-rest-") && t.isAlive()) n++;
        }
        return n;
    }

    private static void awaitQuietly(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    private Map<String, Object> get(int port, String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setConnectTimeout(3_000);
        c.setReadTimeout(3_000);
        c.setRequestMethod("GET");
        try {
            assertEquals(200, c.getResponseCode(),
                    "GET " + path + " 没有 200：报端口的人并没有在服务");
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

    @Test
    void reportedPortIsTheOneActuallyServing() throws Exception {
        store = new InMemoryVectorStore();
        server = new QdrantRestServer(store, 0);          // 交给内核挑端口
        server.start();

        int reported = server.getPort();
        assertTrue(reported > 0 && reported <= 65535,
                "port=0 时 getPort() 必须报内核挑的那个端口，实测报的是 " + reported);
        assertNotEquals(0, reported, "改前就是这里：字段一直是构造参数 0，日志会打出 started on port 0");

        // 阳性对照：报出来的这个端口真的在服务请求。只断言 >0 是不够的 ——
        // 一个"随便返回个 System.currentTimeMillis()%65535"的实现同样能过。
        Map<String, Object> body = get(reported, "/collections");
        assertEquals("ok", body.get("status"), "报出来的端口必须就是应答的端口");
    }

    @Test
    void stopReclaimsWorkerThreadsAndFreesThePort() throws Exception {
        store = new InMemoryVectorStore();
        assertEquals(0, restThreads(), "前置：进测试时不该有别的用例留下 z-vector-rest-* 线程");

        server = new QdrantRestServer(store, 0);
        server.start();
        int port = server.getPort();
        assertTrue(port > 0);

        // 工作线程是按需建的：不发流量就没有线程，"stop 之后 0 条"会是空跑。
        for (int i = 0; i < 4; i++) assertEquals("ok", get(port, "/collections").get("status"));
        int busy = restThreads();
        assertTrue(busy >= 1,
                "前置：起了流量之后必须真的有本服务的 worker 线程，否则下面的归零断言什么也没钉住"
                        + "（实测 " + busy + " 条）");

        server.stop();
        server = null;

        long deadline = System.currentTimeMillis() + 5_000;
        int left = restThreads();
        while (left > 0 && System.currentTimeMillis() < deadline) {
            awaitQuietly(50);
            left = restThreads();
        }
        assertEquals(0, left, "stop() 之后 z-vector-rest-* worker 必须全部退出：改前 stop() 只停"
                + " HttpServer，不关 setExecutor() 交出去的那个池 ⇒ 每起停一次漏 " + busy + " 条线程");

        // 端口释放的独立证据：同一个端口能重新 bind 上。
        try (ServerSocket rebind = new ServerSocket(port)) {
            assertEquals(port, rebind.getLocalPort(), "stop() 之后应当能重新绑上同一个端口");
        }
    }

    @Test
    void stopWithoutStartAndTwiceInARowAreSafe() throws IOException {
        InMemoryVectorStore s1 = new InMemoryVectorStore();
        QdrantRestServer never = new QdrantRestServer(s1, 0);
        never.stop();                       // 改前靠 if (server != null) 兜着；现在两个字段都会摘
        assertEquals(0, never.getPort(), "没启动过时退回请求值 0，不许凭空编一个端口");
        s1.close();

        InMemoryVectorStore s2 = new InMemoryVectorStore();
        server = new QdrantRestServer(s2, 0);
        server.start();
        server.stop();
        server.stop();                      // 幂等：第二次不能再停一次池子或 NPE
        server = null;
        assertEquals(0, restThreads(), "两次 stop 之后不该留下线程");
    }
}
