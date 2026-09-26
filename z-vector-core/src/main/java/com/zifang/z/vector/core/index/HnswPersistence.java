package com.zifang.z.vector.core.index;

import com.zifang.util.core.io.FileUtil;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.core.distance.Distance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HNSW 索引磁盘持久化工具 — 将内存中的 HNSW 图序列化到磁盘，支持 reload。
 * <p>
 * 设计参考 zvec 的 LocalWalFile + Faiss 的 HNSW I/O + LanceDB 的 Manifest.
 *
 * <h2>二进制文件格式</h2>
 * <pre>
 * ┌────────────────────────────────────────────────┐
 * │ magic "HNSW" (4B)                              │
 * │ version (1B)                                   │
 * │ dimension (4B)                                 │
 * │ M (4B)                                         │
 * │ maxLevel (4B)                                  │
 * │ efConstruction (4B)                            │
 * │ efSearch (4B)                                  │
 * │ mL double (8B)                                  │
 * │ entryPoint idLen (2B) + id (变长)              │
 * │ nodeCount (4B)                                 │
 * │ For each node:                                 │
 * │   idLen (2B), id (变长)                        │
 * │   level (1B)                                    │
 * │   vector (dim * 4B)                            │
 * │   payload (见下；v1 无此字段)                   │
 * │   For each level 0..level:                    │
 * │     neighborCount (4B)                         │
 * │     neighborId (变长, 用 2B 前缀) * count      │
 * │ CRC32 (4B)                                     │
 * └────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>payload 编码（VERSION 2 起）</h2>
 * 每个值是 1 字节类型标签 + 载荷：null / true / false / int / long / float / double /
 * short / byte / String(4B 长度前缀) / List(4B 个数) / Map(4B 个数，key 为 String) /
 * BigDecimal・BigInteger(十进制字符串)。core 模块不引 JSON 依赖，所以这里自带一套定长标签。
 * 既不在标签表内也不在 List/Map 里的对象（Date、自定义类）按 {@code toString()} 落成字符串。
 * <p>
 * <b>为什么必须带上 payload</b>：{@code Collection} 没有独立的点存储，向量索引就是数据本身。
 * 快照里不写 payload，重启后 {@code get()}/{@code search()} 返回的点 payload 全空，
 * payload 倒排也只能重建出一张空表（过滤搜索因此永久退回放大候选的慢路径）。
 * <p>
 * <b>版本策略</b>：{@code load()} 只认 {@link #VERSION}。旧版（v1，不含 payload）文件会被
 * {@code PersistentVectorStore} 的恢复流程按普通失败处理，日志记 "falling back to rebuild"
 * 并全量重建一次 —— 半个快照比没有快照更糟，因为它的 payload 是静默缺的。
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 保存
 * HnswPersistence.save(hnswIndex, "/data/zvec/hnsw.bin");
 *
 * // 加载
 * HnswIndex loaded = HnswPersistence.load("/data/zvec/hnsw.bin", distance);
 * }</pre>
 *
 * <h2>线程安全</h2>
 * save/load 由调用方在读写锁保护下调用；本类不做锁。
 */
public class HnswPersistence {

    private static final Logger LOG = LoggerFactory.getLogger(HnswPersistence.class);

    public static final String MAGIC = "HNSW";
    public static final byte VERSION = 2;

    // payload 值的类型标签
    private static final byte T_NULL = 0, T_FALSE = 1, T_TRUE = 2, T_INT = 3, T_LONG = 4,
            T_FLOAT = 5, T_DOUBLE = 6, T_SHORT = 7, T_BYTE = 8, T_STRING = 9, T_LIST = 10,
            T_MAP = 11, T_BIGDECIMAL = 12, T_BIGINTEGER = 13;

    private HnswPersistence() {}

    /**
     * 将 HNSW 索引保存到磁盘。
     * <p>
     * <b>调用方</b>应持有 HNSW 读锁以保证一致性。
     */
    public static void save(HnswIndex index, String filePath) throws IOException {
        if (index == null) throw new IllegalArgumentException("index is null");
        Path path = Paths.get(filePath);
        // 复用 z-util-core 的 FileUtil.mkdirs 替代 Files.createDirectories
        FileUtil.mkdirs(path.getParent().toString());

        ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            // Header
            dos.writeBytes(MAGIC);
            dos.writeByte(VERSION);
            dos.writeInt(index.dimension());
            dos.writeInt(index.getM());
            dos.writeInt(index.getMaxLevel());
            dos.writeInt(index.getEfConstruction());
            dos.writeInt(index.getEfSearch());

            // entryPoint
            String ep = index.getEntryPoint();
            writeU16Bytes(dos, ep == null ? "" : ep, "entryPoint id");

            // Node count
            List<HnswNodeData> nodeList = index.exportNodes();
            dos.writeInt(nodeList.size());

            // Nodes
            for (HnswNodeData node : nodeList) {
                writeU16Bytes(dos, node.id, "node id");
                dos.writeByte(node.level);

                // Vector (dim * 4 bytes)
                for (int i = 0; i < index.dimension(); i++) {
                    dos.writeFloat(node.vector[i]);
                }

                writePayloadMap(dos, node.payload);

                // Neighbor lists for each level 0..level
                for (int lvl = 0; lvl <= node.level; lvl++) {
                    List<String> neighbors = node.neighbors.get(lvl);
                    dos.writeInt(neighbors.size());
                    for (String nb : neighbors) {
                        writeU16Bytes(dos, nb, "neighbor id");
                    }
                }
            }

            // CRC32 覆盖前面写出的全部字节；dos 仍在 try-with-resources 内，写到这里才 flush
            dos.flush();
            byte[] allBytes = baos.toByteArray();
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(allBytes);
            dos.writeInt((int) crc.getValue());
        }

        // 写到文件
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.setLength(0);
            raf.write(baos.toByteArray());
            raf.getFD().sync();
        }
        LOG.info("HNSW saved: {} nodes to {}", index.size(), path);
    }

    /**
     * 从磁盘加载 HNSW 索引。
     * <p>
     * CRC 先于解析：文件尾 4 字节是 {@link #save} 写的整文件校验和，不匹配就直接拒 ——
     * 页头里的 id 长度、邻居个数都是磁盘字节，让它们先进解析器会换来
     * NegativeArraySizeException / OOM，而且坏图会被当成好图接管。
     */
    public static HnswIndex load(String filePath, Distance distance) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new IOException("HNSW file not found: " + filePath);
        }

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long len = raf.length();
            if (len > Integer.MAX_VALUE) {
                throw new IOException("HNSW file too large to load: " + len + " bytes");
            }
            byte[] allBytes = new byte[(int) len];
            raf.readFully(allBytes);
            verifyCrc(allBytes);

            try (DataInputStream dis =
                         new DataInputStream(new ByteArrayInputStream(allBytes, 0, allBytes.length - 4))) {
                // Header
                byte[] magic = new byte[4];
                dis.readFully(magic);
                if (!MAGIC.equals(new String(magic, StandardCharsets.US_ASCII))) {
                    throw new IOException("Invalid HNSW magic");
                }
                byte version = dis.readByte();
                if (version != VERSION) {
                    throw new IOException("Unsupported HNSW version: " + version
                            + " (this build writes " + VERSION + ")");
                }
                int dimension = dis.readInt();
                int M = dis.readInt();
                int maxLevel = dis.readInt();
                int efConstruction = dis.readInt();
                int efSearch = dis.readInt();
                if (dimension <= 0 || M <= 0 || efConstruction <= 0 || efSearch <= 0) {
                    throw new IOException("Non-positive HNSW header: dim=" + dimension + ", M=" + M
                            + ", efConstruction=" + efConstruction + ", efSearch=" + efSearch);
                }

                // entryPoint —— 空串是"没有入口"的编码（未 build 的空索引），必须还原成 null
                String entryPoint = readU16String(dis, "entryPoint id");
                if (entryPoint.isEmpty()) entryPoint = null;

                int nodeCount = dis.readInt();
                if (nodeCount < 0) {
                    throw new IOException("Negative nodeCount in HNSW file: " + nodeCount);
                }

                // 创建 HNSW（不触发 build，通过内部 importNodes）
                HnswIndex index = new HnswIndex(distance, dimension,
                        M, efConstruction, efSearch);
                Map<String, HnswNodeData> nodeMap = new HashMap<>();

                // 读取所有节点
                for (int n = 0; n < nodeCount; n++) {
                    String id = readU16String(dis, "node id");

                    int level = dis.readByte() & 0xFF;

                    float[] vec = new float[dimension];
                    for (int i = 0; i < dimension; i++) {
                        vec[i] = dis.readFloat();
                    }

                    Map<String, Object> payload = readPayloadMap(dis, id);

                    Map<Integer, List<String>> neighbors = new HashMap<>();
                    for (int lvl = 0; lvl <= level; lvl++) {
                        int nbCount = dis.readInt();
                        if (nbCount < 0) {
                            throw new IOException("Negative neighbor count for node " + id
                                    + " at level " + lvl + ": " + nbCount);
                        }
                        List<String> list = new ArrayList<>(nbCount);
                        for (int k = 0; k < nbCount; k++) {
                            list.add(readU16String(dis, "neighbor id"));
                        }
                        neighbors.put(lvl, list);
                    }

                    nodeMap.put(id, new HnswNodeData(id, vec, level, neighbors, payload));
                }

                if (dis.available() > 0) {
                    throw new IOException("HNSW file has " + dis.available()
                            + " unread bytes after " + nodeCount + " nodes — truncated or misaligned");
                }

                index.importNodes(nodeMap, entryPoint);
                LOG.info("HNSW loaded: {} nodes from {}", nodeCount, path);
                return index;
            }
        }
    }

    /** 校验 save() 写在文件尾的整文件 CRC32。 */
    private static void verifyCrc(byte[] allBytes) throws IOException {
        if (allBytes.length < 4) {
            throw new IOException("HNSW file too short to contain a CRC32 trailer: "
                    + allBytes.length + " bytes");
        }
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(allBytes, 0, allBytes.length - 4);
        int written = ((allBytes[allBytes.length - 4] & 0xFF) << 24)
                | ((allBytes[allBytes.length - 3] & 0xFF) << 16)
                | ((allBytes[allBytes.length - 2] & 0xFF) << 8)
                | (allBytes[allBytes.length - 1] & 0xFF);
        int computed = (int) crc.getValue();
        if (written != computed) {
            throw new IOException("HNSW file CRC mismatch (written=" + written
                    + ", computed=" + computed + ") — corrupted or partially written snapshot: "
                    + allBytes.length + " bytes");
        }
    }

    /** id 走 2 字节长度前缀：超过 65535 会静默截断长度字段、把整个文件结构错位，必须当场拒。 */
    private static void writeU16Bytes(DataOutputStream dos, String s, String what) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0xFFFF) {
            throw new IOException("HNSW " + what + " too long to serialize: " + bytes.length
                    + " bytes (max 65535) — id=" + s.substring(0, Math.min(32, s.length())) + "…");
        }
        dos.writeShort(bytes.length);
        dos.write(bytes);
    }

    private static String readU16String(DataInputStream dis, String what) throws IOException {
        int len = dis.readUnsignedShort();
        byte[] bytes = new byte[len];
        dis.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeUtf(DataOutputStream dos, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        dos.writeInt(bytes.length);
        dos.write(bytes);
    }

    private static String readUtf(DataInputStream dis, int len, String what) throws IOException {
        if (len < 0) {
            throw new IOException("Negative length for HNSW payload " + what + ": " + len);
        }
        byte[] bytes = new byte[len];
        dis.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writePayloadMap(DataOutputStream dos, Map<String, Object> payload)
            throws IOException {
        if (payload == null) {
            dos.writeInt(0);
            return;
        }
        dos.writeInt(payload.size());
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            writeUtf(dos, e.getKey() == null ? "null" : e.getKey());
            writePayloadValue(dos, e.getValue());
        }
    }

    private static Map<String, Object> readPayloadMap(DataInputStream dis, String nodeId)
            throws IOException {
        int size = dis.readInt();
        if (size < 0) {
            throw new IOException("Negative payload size for node " + nodeId + ": " + size);
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            String key = readUtf(dis, dis.readInt(), "key");
            payload.put(key, readPayloadValue(dis));
        }
        return payload;
    }

    private static void writePayloadValue(DataOutputStream dos, Object v) throws IOException {
        if (v == null) {
            dos.writeByte(T_NULL);
        } else if (v instanceof Boolean) {
            dos.writeByte(((Boolean) v) ? T_TRUE : T_FALSE);
        } else if (v instanceof String) {
            dos.writeByte(T_STRING);
            writeUtf(dos, (String) v);
        } else if (v instanceof Integer) {
            dos.writeByte(T_INT);
            dos.writeInt((Integer) v);
        } else if (v instanceof Long) {
            dos.writeByte(T_LONG);
            dos.writeLong((Long) v);
        } else if (v instanceof Float) {
            dos.writeByte(T_FLOAT);
            dos.writeFloat((Float) v);
        } else if (v instanceof Double) {
            dos.writeByte(T_DOUBLE);
            dos.writeDouble((Double) v);
        } else if (v instanceof Short) {
            dos.writeByte(T_SHORT);
            dos.writeShort((Short) v);
        } else if (v instanceof Byte) {
            dos.writeByte(T_BYTE);
            dos.writeByte((Byte) v);
        } else if (v instanceof java.math.BigDecimal) {
            dos.writeByte(T_BIGDECIMAL);
            writeUtf(dos, ((java.math.BigDecimal) v).toPlainString());
        } else if (v instanceof java.math.BigInteger) {
            dos.writeByte(T_BIGINTEGER);
            writeUtf(dos, v.toString());
        } else if (v instanceof List) {
            List<?> list = (List<?>) v;
            dos.writeByte(T_LIST);
            dos.writeInt(list.size());
            for (Object item : list) writePayloadValue(dos, item);
        } else if (v instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) v;
            dos.writeByte(T_MAP);
            dos.writeInt(map.size());
            for (Map.Entry<?, ?> e : map.entrySet()) {
                writeUtf(dos, String.valueOf(e.getKey()));
                writePayloadValue(dos, e.getValue());
            }
        } else {
            // Date / 自定义对象：这类值 payload 倒排本来也不认（isIndexable 只收
            // String/Number/Boolean），落成字符串至少不把整个字段丢掉。
            dos.writeByte(T_STRING);
            writeUtf(dos, v.toString());
        }
    }

    private static Object readPayloadValue(DataInputStream dis) throws IOException {
        byte tag = dis.readByte();
        switch (tag) {
            case T_NULL: return null;
            case T_TRUE: return Boolean.TRUE;
            case T_FALSE: return Boolean.FALSE;
            case T_INT: return dis.readInt();
            case T_LONG: return dis.readLong();
            case T_FLOAT: return dis.readFloat();
            case T_DOUBLE: return dis.readDouble();
            case T_SHORT: return dis.readShort();
            case T_BYTE: return dis.readByte();
            case T_STRING: return readUtf(dis, dis.readInt(), "string value");
            case T_BIGDECIMAL: return new java.math.BigDecimal(readUtf(dis, dis.readInt(), "decimal"));
            case T_BIGINTEGER: return new java.math.BigInteger(readUtf(dis, dis.readInt(), "integer"));
            case T_LIST: {
                int n = dis.readInt();
                if (n < 0) throw new IOException("Negative payload list size: " + n);
                List<Object> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) list.add(readPayloadValue(dis));
                return list;
            }
            case T_MAP: {
                int n = dis.readInt();
                if (n < 0) throw new IOException("Negative payload map size: " + n);
                Map<String, Object> map = new java.util.LinkedHashMap<>();
                for (int i = 0; i < n; i++) {
                    map.put(readUtf(dis, dis.readInt(), "nested key"), readPayloadValue(dis));
                }
                return map;
            }
            default:
                throw new IOException("Unknown payload value tag in HNSW snapshot: " + tag);
        }
    }

    /**
     * HNSW 节点的序列化数据结构（仅持久化层使用）。
     */
    public static class HnswNodeData {
        public final String id;
        public final float[] vector;
        public final int level;
        public final Map<Integer, List<String>> neighbors;
        /** 可为 null（只关心图形状的测试/构造），导入时按空 payload 处理。 */
        public final Map<String, Object> payload;

        public HnswNodeData(String id, float[] vector, int level,
                             Map<Integer, List<String>> neighbors) {
            this(id, vector, level, neighbors, null);
        }

        public HnswNodeData(String id, float[] vector, int level,
                             Map<Integer, List<String>> neighbors, Map<String, Object> payload) {
            this.id = id;
            this.vector = vector;
            this.level = level;
            this.neighbors = neighbors;
            this.payload = payload;
        }
    }
}