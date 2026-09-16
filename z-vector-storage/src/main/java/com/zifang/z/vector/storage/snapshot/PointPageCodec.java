package com.zifang.z.vector.storage.snapshot;

import com.zifang.z.vector.api.VectorPoint;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@link VectorPoint} 列表编码为二进制字节数组（适合作为 {@link com.zifang.z.vector.storage.page.Page} 的 payload）。
 * <p>
 * 二进制布局：
 * <pre>
 * ┌────────────────────────────────────────────────────────┐
 * │ count          (4B int)                                │ 偏移 0
 * │ per-point:
 * │   idLen         (2B short)
 * │   id            (变长 UTF-8)
 * │   vector        (dim * 4B float)
 * │   payloadLen    (4B int)
 * │   payloadJson   (变长 UTF-8 JSON)
 * │   payloadKvs    (4B int)                                │ 自 2026-09-08 起固定 0，保留扩展
 * └────────────────────────────────────────────────────────┘
 * </pre>
 * <p>
 * 优点：
 * <ul>
 *   <li>无需 Jackson 解析（每次重启省 ~ms 级开销）；</li>
 *   <li>紧凑：1000 个 768 维点约 3MB（vs JSON 约 5MB）；</li>
 *   <li>Page 64KB 容量下，单页可装 ~17 个 768 维点。</li>
 * </ul>
 */
public final class PointPageCodec {

    private PointPageCodec() {}

    /** 单页最多能装多少个点（粗略估计，用于拆分）。 */
    public static int estimateMaxPoints(int dimension) {
        // 64KB page - header (17) - crc (4) ≈ 65KB 可用
        // 每个点：2B idLen + 平均 16B id + dim*4B vec + 4B payloadLen + 平均 32B payload ≈ 58 + 4*dim
        int per = 58 + 4 * Math.max(1, dimension);
        int max = (PageUsableBytes) / per;
        return Math.max(1, max);
    }

    /** Page 的可用字节（保守值，避免 header 溢出）。 */
    public static final int PageUsableBytes =
            com.zifang.z.vector.storage.page.Page.DEFAULT_PAGE_SIZE
            - com.zifang.z.vector.storage.page.Page.HEADER_SIZE
            - com.zifang.z.vector.storage.page.Page.CRC_SIZE;

    /**
     * 编码 points → bytes（用于写入 Page payload）。
     */
    public static byte[] encode(List<VectorPoint> points) {
        if (points == null || points.isEmpty()) {
            return new byte[4];   // count=0
        }
        int dim = points.get(0).getDimension();
        // 预计算大小（避免 ByteBuffer 扩容）
        int estimated = 4 + points.size() * (2 + 32 + dim * 4 + 4 + 64);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(estimated);
        java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
        try {
            dos.writeInt(points.size());
            for (VectorPoint p : points) {
                if (p.getDimension() != dim) {
                    throw new IllegalArgumentException(
                            "All points in one page must share dimension; got " + p.getDimension()
                                    + " vs expected " + dim);
                }
                byte[] idBytes = p.getId().getBytes(StandardCharsets.UTF_8);
                if (idBytes.length > Short.MAX_VALUE) {
                    throw new IllegalArgumentException(
                            "Point id too long: " + idBytes.length);
                }
                dos.writeShort(idBytes.length);
                dos.write(idBytes);
                for (float v : p.getVector()) dos.writeFloat(v);
                byte[] payloadJson = payloadToJsonBytes(p.getPayload());
                dos.writeInt(payloadJson.length);
                dos.write(payloadJson);
                dos.writeInt(0);  // payloadKvs reserved
            }
            dos.flush();
        } catch (java.io.IOException e) {
            // ByteArrayOutputStream 不会真正抛 IOException
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }

    /**
     * 解码 bytes → points（用于从 Page payload 恢复）。
     *
     * @param bytes Page payload
     * @param dimension 该集合的维度（用于校验 vector 长度）
     */
    public static List<VectorPoint> decode(byte[] bytes, int dimension) {
        if (bytes == null || bytes.length < 4) return new ArrayList<>();
        java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(bytes));
        List<VectorPoint> points;
        try {
            int count = dis.readInt();
            points = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                short idLen = dis.readShort();
                byte[] idBytes = new byte[idLen];
                dis.readFully(idBytes);
                String id = new String(idBytes, StandardCharsets.UTF_8);
                float[] vec = new float[dimension];
                for (int d = 0; d < dimension; d++) vec[d] = dis.readFloat();
                int payloadLen = dis.readInt();
                byte[] payloadJson = new byte[payloadLen];
                dis.readFully(payloadJson);
                int payloadKvs = dis.readInt();   // reserved，忽略
                Map<String, Object> payload = payloadFromJsonBytes(payloadJson);
                points.add(new VectorPoint(id, vec, payload));
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to decode point page", e);
        }
        return points;
    }

    // ==================== Payload 序列化（最小化 JSON 依赖）====================

    private static byte[] payloadToJsonBytes(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return new byte[]{'{', '}'};
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsBytes(payload);
        } catch (Exception ex) {
            // fallback：手写最小 JSON
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                if (!first) sb.append(',');
                sb.append('"').append(escapeJson(entry.getKey())).append("\":");
                Object v = entry.getValue();
                if (v instanceof Number || v instanceof Boolean) sb.append(v);
                else sb.append('"').append(escapeJson(String.valueOf(v))).append('"');
                first = false;
            }
            sb.append('}');
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadFromJsonBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0
                || (bytes.length == 2 && bytes[0] == '{' && bytes[1] == '}')) {
            return new HashMap<>();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(bytes, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
