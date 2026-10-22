package com.zifang.z.vector.grpc;

import com.zifang.z.vector.api.VectorStore;
import com.zifang.z.vector.core.InMemoryVectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Enumeration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code QdrantRestServer} 的 <b>bind 地址</b>尺。
 *
 * <p>这个 REST 面<b>没有鉴权</b>（{@code GET /collections} 直接吐集合列表）。所以
 * "绑在哪个地址上"就是安全边界本身，不是配置细节。
 *
 * <p>改前 {@code start()} 用的是 {@code new InetSocketAddress(port)} —— 这个构造
 * <b>恒为通配地址</b>，于是不管上层怎么配，6334 都对整个 LAN 裸奔。
 * 这里钉四件事：
 * <ol>
 *   <li>旧构造（两个）委托后<b>默认绑回环</b>，不是通配 —— 缺省值本身就是漏洞，
 *       "忘了配"绝不能等于"放通配"；</li>
 *   <li>显式 host 真的生效（bind 地址 = 传入值）；</li>
 *   <li>空白/null host 退回回环，不退回通配；</li>
 *   <li><b>反证</b>：绑回环之后，从本机 LAN IP 直连必须被拒 ——
 *       同时数据面（回环上的 {@code GET /collections}）仍然 200，
 *       不许用"关掉服务"来"修安全"。</li>
 * </ol>
 */
class QdrantRestServerBindHostTest {

    private static final String WILDCARD_V4 = "0.0.0.0";

    private QdrantRestServer server;
    private VectorStore store;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        if (store != null) store.close();
    }

    private VectorStore newStore() {
        store = new InMemoryVectorStore();
        return store;
    }

    // ==================== 旧构造不裸奔 ====================

    @Test
    void legacyPortOnlyConstructorBindsLoopbackNotWildcard() throws Exception {
        server = new QdrantRestServer(0);
        server.start();

        assertEquals("127.0.0.1", server.getBindHost(),
                "旧构造必须显式默认回环 —— 默认值本身就是漏洞");
        assertEquals("127.0.0.1", server.getBoundAddress(),
                "实际绑上的地址必须是回环");
        assertNotEquals(WILDCARD_V4, server.getBoundAddress(),
                "绑到通配地址就等于把无鉴权的库裸奔给 LAN");
    }

    @Test
    void legacyStoreConstructorBindsLoopbackNotWildcard() throws Exception {
        server = new QdrantRestServer(newStore(), 0);
        server.start();

        assertEquals("127.0.0.1", server.getBindHost());
        assertEquals("127.0.0.1", server.getBoundAddress());
        assertNotEquals(WILDCARD_V4, server.getBoundAddress());
    }

    // ==================== 显式 host 生效 ====================

    @Test
    void explicitHostConstructorBindsThatHost() throws Exception {
        server = new QdrantRestServer(newStore(), "127.0.0.1", 0);
        server.start();

        assertEquals("127.0.0.1", server.getBindHost());
        assertEquals("127.0.0.1", server.getBoundAddress());
    }

    @Test
    void blankOrNullHostFallsBackToLoopback() {
        assertEquals("127.0.0.1", QdrantRestServer.normalizeHost(null));
        assertEquals("127.0.0.1", QdrantRestServer.normalizeHost(""));
        assertEquals("127.0.0.1", QdrantRestServer.normalizeHost("   "));
        assertEquals("127.0.0.1", QdrantRestServer.DEFAULT_BIND_HOST);
    }

    // ==================== 反证：LAN 直连被拒 + 数据面仍通 ====================

    @Test
    void lanDirectConnectIsRefusedWhileLoopbackStillWorks() throws Exception {
        InetAddress lan = firstNonLoopbackV4();
        org.junit.jupiter.api.Assumptions.assumeTrue(lan != null,
                "本机没有非回环 IPv4，LAN 直连这一条在本机上没法证");

        server = new QdrantRestServer(newStore(), 0);
        server.start();
        int port = server.getPort();

        // 数据面：回环上仍然 200（不许用"关服务"换安全）
        assertEquals(200, httpStatus("127.0.0.1", port, "/collections"),
                "绑回环之后回环上的数据面必须仍然可用");

        // 暴露面：LAN IP 直连必须拿不到数据
        int lanStatus = httpStatus(lan.getHostAddress(), port, "/collections");
        assertNotEquals(200, lanStatus,
                "从 LAN IP " + lan.getHostAddress() + " 无凭证直连竟然拿到了 200 —— 仍然裸奔");
    }

    // ==================== 工具 ====================

    /** 本机第一个非回环 IPv4；没有就返回 null。 */
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
            // 枚举不到就当没有，交给 assumeTrue 跳过
        }
        return null;
    }

    /** 请求一次并返回 HTTP 状态码；连不上（被拒）算作 0 —— "连不上"正是期望的结果之一。 */
    private static int httpStatus(String host, int port, String path) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + host + ":" + port + path).openConnection();
            c.setConnectTimeout(2_000);
            c.setReadTimeout(2_000);
            c.setRequestMethod("GET");
            return c.getResponseCode();
        } catch (IOException e) {
            return 0;   // 连接被拒 / 超时 ⇒ 没有数据面泄漏
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
