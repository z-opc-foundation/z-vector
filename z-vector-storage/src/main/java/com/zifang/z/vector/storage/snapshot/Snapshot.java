package com.zifang.z.vector.storage.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.VectorPoint;
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
 * Snapshot — 内存状态的完整快照。
 * <p>
 * 设计参考 LanceDB 的 manifest snapshot + RocksDB 的 SST + zvec 的本地快照.
 * <p>
 * 当 WAL 增长到一定大小时，触发 snapshot：把所有集合状态写入磁盘，
 * 然后 truncate WAL，避免日志无限增长。
 *
 * <h2>文件格式</h2>
 * <pre>
 * ┌─────────────────────────────────────┐
 * │ magic (4B) "SNAP"                    │
 * │ version (1B)                         │
 * │ jsonLength (4B)                      │
 * │ json (变长) — 完整状态                │
 * └─────────────────────────────────────┘
 * </pre>
 *
 * <h2>压缩选项</h2>
 * 生产环境可启用 GZIP 压缩（需要额外 snappy-java 或 zstd 依赖）。
 */
public class Snapshot {

    private static final Logger LOG = LoggerFactory.getLogger(Snapshot.class);

    public static final String MAGIC = "SNAP";
    public static final byte VERSION = 1;

    private final Path snapshotPath;
    private final ObjectMapper json = new ObjectMapper();

    public Snapshot(String dataDir) {
        this.snapshotPath = Paths.get(dataDir, "snapshot.bin");
    }

    /**
     * 写入 snapshot — 序列化完整的 VectorPoint 数据。
     */
    public void write(List<CollectionData> data) throws IOException {
        Files.createDirectories(snapshotPath.getParent());

        // 转 Map 列表（避免 POJO 序列化问题）
        List<Map<String, Object>> collectionsJson = new ArrayList<>();
        for (CollectionData cd : data) {
            collectionsJson.add(toJsonMap(cd));
        }
        Map<String, Object> root = new HashMap<>();
        root.put("version", VERSION);
        root.put("timestamp", System.currentTimeMillis());
        root.put("collections", collectionsJson);

        byte[] jsonBytes = json.writeValueAsBytes(root);

        try (RandomAccessFile raf = new RandomAccessFile(snapshotPath.toFile(), "rw")) {
            raf.setLength(0);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(baos)) {
                dos.writeBytes(MAGIC);
                dos.writeByte(VERSION);
                dos.writeInt(jsonBytes.length);
                dos.write(jsonBytes);
            }
            raf.write(baos.toByteArray());
            raf.getFD().sync();
        }
        LOG.info("Snapshot written: {} collections, {} bytes", data.size(), jsonBytes.length);
    }

    /**
     * 读取 snapshot — 恢复完整的 VectorPoint 数据。
     */
    @SuppressWarnings("unchecked")
    public List<CollectionData> read() throws IOException {
        if (!Files.exists(snapshotPath)) {
            return new ArrayList<>();
        }

        try (RandomAccessFile raf = new RandomAccessFile(snapshotPath.toFile(), "r")) {
            byte[] allBytes = new byte[(int) raf.length()];
            raf.readFully(allBytes);

            try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(allBytes))) {
                byte[] magic = new byte[4];
                dis.readFully(magic);
                String magicStr = new String(magic, StandardCharsets.US_ASCII);
                if (!MAGIC.equals(magicStr)) {
                    throw new IOException("Invalid snapshot magic: " + magicStr);
                }
                byte version = dis.readByte();
                if (version != VERSION) {
                    throw new IOException("Unsupported snapshot version: " + version);
                }
                int jsonLen = dis.readInt();
                byte[] jsonBytes = new byte[jsonLen];
                dis.readFully(jsonBytes);

                Map<String, Object> root = json.readValue(jsonBytes, Map.class);
                Object cols = root.get("collections");
                if (cols == null) return new ArrayList<>();
                List<CollectionData> result = new ArrayList<>();
                for (Object c : (List<?>) cols) {
                    result.add(parseCollection((Map<String, Object>) c));
                }
                return result;
            }
        }
    }

    public boolean exists() {
        return Files.exists(snapshotPath);
    }

    public void delete() throws IOException {
        Files.deleteIfExists(snapshotPath);
    }

    public Path getPath() { return snapshotPath; }

    // ==================== 序列化辅助 ====================

    private Map<String, Object> toJsonMap(CollectionData cd) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", cd.name);
        m.put("dimension", cd.dimension);
        m.put("metric", cd.metric.name());
        m.put("index_type", cd.indexType.name());
        m.put("index_params", cd.indexParams != null ? cd.indexParams : new HashMap<>());
        List<Map<String, Object>> pointsJson = new ArrayList<>();
        for (VectorPoint p : cd.points) {
            pointsJson.add(pointToJson(p));
        }
        m.put("points", pointsJson);
        return m;
    }

    private Map<String, Object> pointToJson(VectorPoint p) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", p.getId());
        m.put("vector", p.getVector());
        m.put("payload", p.getPayload() != null ? p.getPayload() : new HashMap<>());
        return m;
    }

    @SuppressWarnings("unchecked")
    private CollectionData parseCollection(Map<String, Object> m) {
        String name = (String) m.get("name");
        int dimension = ((Number) m.get("dimension")).intValue();
        DistanceMetric metric = DistanceMetric.valueOf((String) m.get("metric"));
        IndexType indexType = IndexType.valueOf((String) m.get("index_type"));
        Map<String, Object> indexParams = (Map<String, Object>) m.get("index_params");
        List<VectorPoint> points = new ArrayList<>();
        Object pts = m.get("points");
        if (pts instanceof List) {
            for (Object p : (List<?>) pts) {
                points.add(parsePoint((Map<String, Object>) p));
            }
        }
        return new CollectionData(name, dimension, metric, indexType, indexParams, points);
    }

    @SuppressWarnings("unchecked")
    private VectorPoint parsePoint(Map<String, Object> m) {
        String id = (String) m.get("id");
        List<Number> vecList = (List<Number>) m.get("vector");
        float[] vec = new float[vecList.size()];
        for (int i = 0; i < vecList.size(); i++) vec[i] = vecList.get(i).floatValue();
        Map<String, Object> payload = (Map<String, Object>) m.get("payload");
        if (payload == null) payload = new HashMap<>();
        return new VectorPoint(id, vec, payload);
    }

    /**
     * 集合快照数据 — 一个集合的完整状态
     */
    public static class CollectionData {
        public final String name;
        public final int dimension;
        public final DistanceMetric metric;
        public final IndexType indexType;
        public final Map<String, Object> indexParams;
        public final List<VectorPoint> points;

        public CollectionData(String name, int dimension, DistanceMetric metric,
                              IndexType indexType, Map<String, Object> indexParams,
                              List<VectorPoint> points) {
            this.name = name;
            this.dimension = dimension;
            this.metric = metric;
            this.indexType = indexType;
            this.indexParams = indexParams;
            this.points = points;
        }
    }
}