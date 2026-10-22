package com.zifang.z.vector.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 独立 server 形态的 <b>bind 地址</b>尺。
 *
 * <p>改前 {@code VectorServerApplication.start()} 用 {@code new InetSocketAddress(port)} ——
 * 那个构造恒为通配地址，于是独立部署的 z-vector 也把无鉴权的 REST 面裸奔给 LAN。
 * 这里钉：
 * <ol>
 *   <li>没设 {@code ZVECTOR_HOST} ⇒ 绑回环；</li>
 *   <li>设成空串 ⇒ 仍然绑回环（"配了但配空"不能等于"放通配"）；</li>
 *   <li>显式设 {@code ZVECTOR_HOST} ⇒ 真的生效（能设成通配，说明这条配置确实到位，
 *       而不是被代码里的硬编码盖掉）；</li>
 *   <li>绑回环之后，数据面在回环上仍然 200 —— 不许用"关掉服务"来"修安全"。</li>
 * </ol>
 *
 * <p>注意 k8s 侧的配套：Service / NodePort 要把流量转到 Pod IP，进程必须绑到 Pod IP 才收得到，
 * 所以那种部署要在 ConfigMap 里<b>显式</b>写 {@code ZVECTOR_HOST=0.0.0.0}。
 */
class VectorServerBindHostTest {

    private VectorServerApplication.Boot boot;

    @AfterEach
    void tearDown() {
        if (boot != null) VectorServerApplication.shutdown(boot.server, boot.store);
    }

    private static Map<String, String> env(String dataDir, String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ZVECTOR_PORT", "0");
        m.put("ZVECTOR_DATA_DIR", dataDir);
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void defaultHostIsLoopback(@TempDir Path dir) throws IOException {
        boot = VectorServerApplication.boot(env(dir.toString()));

        assertEquals("127.0.0.1", boot.server.getAddress().getHostString(),
                "独立 server 的缺省 bind 必须是回环，不是通配");
        assertNotEquals("0.0.0.0", boot.server.getAddress().getHostString());
    }

    @Test
    void blankHostIsStillLoopback(@TempDir Path dir) throws IOException {
        boot = VectorServerApplication.boot(env(dir.toString(), "ZVECTOR_HOST", "   "));

        assertEquals("127.0.0.1", boot.server.getAddress().getHostString(),
                "配了空串不能等于放通配");
    }

    @Test
    void explicitHostIsHonoured(@TempDir Path dir) throws IOException {
        boot = VectorServerApplication.boot(env(dir.toString(), "ZVECTOR_HOST", "127.0.0.1"));

        assertEquals("127.0.0.1", boot.server.getAddress().getHostString());
        assertEquals(200, httpStatus("127.0.0.1", boot.port, "/health"),
                "绑回环之后数据面必须仍然通");
    }

    @Test
    void wildcardCanStillBeAskedForExplicitly(@TempDir Path dir) throws IOException {
        // 通配是<b>显式意图</b>，不是默认值兜底：k8s Service/NodePort 场景要它，
        // 但它必须由部署方写出来，而不是代码替他决定。
        boot = VectorServerApplication.boot(env(dir.toString(), "ZVECTOR_HOST", "0.0.0.0"));

        assertTrue(boot.server.getAddress().getAddress().isAnyLocalAddress(),
                "ZVECTOR_HOST 显式设通配时应当生效（否则这条配置形同虚设）");
        // 字面量在不同栈上不一样（IPv4 通配是 0.0.0.0，双栈通配是 ::）⇒ 只判"是不是通配"。
    }

    private static int httpStatus(String host, int port, String path) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + host + ":" + port + path).openConnection();
            c.setConnectTimeout(3_000);
            c.setReadTimeout(3_000);
            c.setRequestMethod("GET");
            return c.getResponseCode();
        } catch (IOException e) {
            return 0;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
