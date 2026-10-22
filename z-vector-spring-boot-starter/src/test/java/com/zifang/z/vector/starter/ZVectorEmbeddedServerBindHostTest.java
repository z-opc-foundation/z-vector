package com.zifang.z.vector.starter;

import com.zifang.z.vector.starter.host.ZVectorEmbeddedServerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.URL;
import java.util.Enumeration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内嵌 REST（{@code z.vector.*} 这条路，z-opc 走的就是它）的 <b>bind 地址</b>尺。
 *
 * <p>改前这条路的配置读出来是 {@code z.vector.server.host:0.0.0.0}，而且<b>读出来根本没传下去</b> ——
 * 构造 {@code QdrantRestServer} 时压根没有 host 这个入参，运行时仍然绑通配地址。
 * 于是 6334 这个<b>没有鉴权</b>的 REST 面，在 LAN 内无凭证直连即可读全库。
 *
 * <p>这里钉两件事：
 * <ol>
 *   <li>配置项的缺省值必须是<b>回环</b>（用注解本身来判，不是读源码文本）；</li>
 *   <li>缺省起出来的实例<b>真的</b>只绑回环：回环上数据面 200，
 *       而从本机 LAN IP 直连拿不到数据（反证：不许用"关掉服务"来"修安全"）。</li>
 * </ol>
 */
class ZVectorEmbeddedServerBindHostTest {

    /** 配置项的缺省值必须写在注解里 —— 源码文本会骗人，注解不会。 */
    @Test
    void defaultHostValueIsLoopback() throws Exception {
        Field f = ZVectorEmbeddedServerConfig.class.getDeclaredField("host");
        Value v = f.getAnnotation(Value.class);
        assertNotNull(v, "host 字段上的 @Value 注解不见了 —— 配置项就不是配置项了");
        assertEquals("${z.vector.server.host:127.0.0.1}", v.value(),
                "缺省必须是回环 127.0.0.1，不是 0.0.0.0（默认值本身就是漏洞）");
    }

    @Test
    void embeddedServerBindsLoopbackAndRefusesLanDirectConnect() throws Exception {
        int port = reservePort();
        InetAddress lan = firstNonLoopbackV4();

        new ApplicationContextRunner()
                .withUserConfiguration(ZVectorEmbeddedServerConfig.class)
                .withPropertyValues("z.vector.enabled=true",
                        "z.vector.server.port=" + port)
                .run(ctx -> {
                    assertTrue(awaitListening(port), "内嵌 REST 没有在 " + port + " 上起来");

                    // 数据面：回环上仍然 200
                    assertEquals(200, httpStatus("127.0.0.1", port, "/collections"),
                            "绑回环之后回环上的数据面必须仍然可用");

                    // 暴露面：LAN IP 直连必须拿不到数据
                    if (lan != null) {
                        assertNotEquals(200, httpStatus(lan.getHostAddress(), port, "/collections"),
                                "从 LAN IP " + lan.getHostAddress() + " 无凭证直连拿到了 200 —— 仍然裸奔");
                    }
                });
    }

    // ==================== 工具 ====================

    /** 占一个内核认为空闲的端口，立刻放开交给被测服务去绑。 */
    private static int reservePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    /** 内嵌 server 是异步起的，等它真的在监听（最多 5 秒）。 */
    private static boolean awaitListening(int port) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 200);
                return true;
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
        return false;
    }

    private static InetAddress firstNonLoopbackV4() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isAnyLocalAddress()) {
                        return a;
                    }
                }
            }
        } catch (Exception ignored) {
            // 枚举不到就当没有
        }
        return null;
    }

    /** 请求一次并返回 HTTP 状态码；连不上（被拒）算 0 —— "连不上"正是期望的结果之一。 */
    private static int httpStatus(String host, int port, String path) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + host + ":" + port + path).openConnection();
            c.setConnectTimeout(2_000);
            c.setReadTimeout(2_000);
            c.setRequestMethod("GET");
            return c.getResponseCode();
        } catch (IOException e) {
            return 0;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
