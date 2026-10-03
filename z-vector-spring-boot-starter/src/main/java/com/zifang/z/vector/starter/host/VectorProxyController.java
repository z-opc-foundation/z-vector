package com.zifang.z.vector.starter.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 向量检索 (z-vector) 管理面代理: 把内嵌 Qdrant REST 服务挂到 z-opc 的统一前缀 {@code /api/vector/**}.
 *
 * <p>为什么需要这一层: z-vector 的 REST 面 ({@code /collections}, {@code /collections/{n}/points/search} …)
 * 是 {@link com.zifang.z.vector.grpc.QdrantRestServer} 自己起的 HTTP 服务 (默认 6334),
 * <b>不是 Spring MVC</b> ⇒ 永远不会出现在 {@code /actuator/mappings} 里, 也不在 vite 的 {@code /api} 代理内。
 * 前端只有 baseURL=/api 的一个 axios 实例，所以不把这条路经 8888 出去，页面就只能"渲染正常、表格永远空"。
 *
 * <p>为什么转 HTTP 而不是直接注入进程内 {@code zVectorVectorStore} bean:
 * 转发让"8888 上看到的就是 6334 上看到的就是 L3 自己产出的那份 JSON"成为**机械事实**
 * (逐字段一致不需要我手写一遍响应形状)；自己照 bean 拼响应就等于把 L3 的契约复制进 z-opc，
 * L3 一改这里就静默失真。
 *
 * <p>两个刻意的约束:
 * <ul>
 *   <li><b>端口只问本机内嵌实例</b>，且必须先确认它真的 bind 成功 —— {@code ZCompanyMainStarter} 的
 *       {@code static{}} 对 embed 启动是 catch Throwable 后静默跳过 (z-cache :6379 已实测踩过：
 *       端口被一个几天前的半僵尸 JVM 占着, 今天的内嵌实例根本没启)。绑不上就明确 503,
 *       <b>绝不退化成"连固定端口试试看"</b>，那样会把别的进程的应答读成自己的。</li>
 *   <li>只挂在 {@code /api/**} 下, 不开裸路径 —— 裸路径不在 {@code sso.intercept-paths} 内,
 *       等于不设防 (见 TASK-20260924-018 的教训)。子路径按白名单字符校验收紧, 防 {@code ..} 穿越。</li>
 * </ul>
 */
@RestController
public class VectorProxyController {

    private static final String PREFIX = "/api/vector";
    /** 允许透传的子路径: Qdrant 集合名本身是 [A-Za-z0-9_-]，中间只可能有 '/' */
    private static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9_\\-/]*$");
    private static final Pattern SAFE_QUERY = Pattern.compile("^[A-Za-z0-9_\\-.=&%:/]*$");
    private static final int UPSTREAM_TIMEOUT_MS = 5000;
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private final ObjectProvider<ZVectorEmbeddedServerConfig.QdrantRestServerLifecycle> lifecycleProvider;
    private final ObjectMapper mapper = new ObjectMapper();

    public VectorProxyController(
            ObjectProvider<ZVectorEmbeddedServerConfig.QdrantRestServerLifecycle> lifecycleProvider) {
        this.lifecycleProvider = lifecycleProvider;
    }

    /**
     * 自省接口: 这一路到底连的是谁。孵化期排障必需 —— 只有先确认"应答来自本 JVM 的哪个端口"，
     * 后面 /collections 的空数组才是"真·空"而不是"连错了进程"。
     */
    @GetMapping(PREFIX + "/__instance")
    public void instance(HttpServletResponse resp) throws Exception {
        ZVectorEmbeddedServerConfig.QdrantRestServerLifecycle lc = lifecycleProvider.getIfAvailable();
        int port = lc == null ? -1 : lc.getBoundPort();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jvm", ManagementFactory.getRuntimeMXBean().getName());
        out.put("lifecycleBean", lc != null);
        out.put("boundPort", port);
        out.put("embeddedRunning", port > 0);
        out.put("acceptingNow", lc != null && lc.isAcceptingConnections());
        writeJson(resp, 200, out);
    }

    @RequestMapping(path = PREFIX + "/**",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public void proxy(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String sub = subPath(req.getRequestURI());
        if (sub == null) {
            writeJson(resp, 400, err("bad path", req.getRequestURI()));
            return;
        }
        String query = req.getQueryString();
        if (query != null && !SAFE_QUERY.matcher(query).matches()) {
            writeJson(resp, 400, err("bad query", query));
            return;
        }
        ZVectorEmbeddedServerConfig.QdrantRestServerLifecycle lc = lifecycleProvider.getIfAvailable();
        int port = lc == null ? 0 : lc.getBoundPort();
        if (port <= 0) {
            // 静默跳过是这里最难查的故障形态，宁可显式失败也不要看起来"空得有道理"
            writeJson(resp, 503, err("embedded QdrantRestServer not started on this JVM"
                    + " (zvector.enabled=" + (lc != null) + ", boundPort=" + port + ")", sub));
            return;
        }

        String url = "http://127.0.0.1:" + port + sub + (query == null || query.isEmpty() ? "" : "?" + query);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(UPSTREAM_TIMEOUT_MS);
        conn.setReadTimeout(UPSTREAM_TIMEOUT_MS);
        conn.setRequestMethod(req.getMethod());
        conn.setRequestProperty("Accept", "application/json");
        String ct = req.getContentType();
        boolean wantsBody = "POST".equals(req.getMethod()) || "PUT".equals(req.getMethod());
        byte[] body = wantsBody ? readLimited(req.getInputStream(), MAX_BODY_BYTES) : null;
        if (body != null && body.length > 0) {
            conn.setDoOutput(true);
            if (ct != null) conn.setRequestProperty("Content-Type", ct);
            conn.setFixedLengthStreamingMode(body.length);
        }

        int status;
        byte[] respBody;
        try {
            conn.connect();
            if (body != null && body.length > 0) {
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
            }
            status = conn.getResponseCode();
            InputStream src = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            respBody = src == null ? new byte[0] : readLimited(src, MAX_BODY_BYTES);
        } catch (Exception e) {
            writeJson(resp, 502, err("upstream call failed: " + e.getClass().getSimpleName() + " " + e.getMessage(), sub));
            return;
        } finally {
            conn.disconnect();
        }

        String upstreamType = conn.getContentType();
        resp.setStatus(status);
        resp.setContentType(upstreamType == null ? "application/json;charset=UTF-8" : upstreamType);
        resp.setContentLength(respBody.length);
        resp.getOutputStream().write(respBody);
        resp.getOutputStream().flush();
    }

    /** "/api/vector/collections/demo" → "/collections/demo"; 不在白名单内返回 null */
    private String subPath(String uri) {
        if (uri == null || !uri.startsWith(PREFIX)) return null;
        String sub = uri.substring(PREFIX.length());
        if (sub.isEmpty()) sub = "/";
        if (sub.indexOf("..") >= 0 || !SAFE_PATH.matcher(sub).matches()) return null;
        return sub;
    }

    private static byte[] readLimited(InputStream in, int max) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n, total = 0;
        while ((n = in.read(chunk)) > 0) {
            total += n;
            if (total > max) throw new java.io.IOException("body exceeds " + max + " bytes");
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private Map<String, Object> err(String msg, String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("source", "z-opc-vector-proxy");
        m.put("message", msg);
        m.put("path", path);
        return m;
    }

    private void writeJson(HttpServletResponse resp, int status, Object body) throws Exception {
        byte[] out = mapper.writeValueAsBytes(body);
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setContentLength(out.length);
        resp.getOutputStream().write(out);
        resp.getOutputStream().flush();
    }
}
