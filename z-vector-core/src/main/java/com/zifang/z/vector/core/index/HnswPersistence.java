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
 * │   For each level 0..level:                    │
 * │     neighborCount (4B)                         │
 * │     neighborId (变长, 用 2B 前缀) * count      │
 * │ CRC32 (4B)                                     │
 * └────────────────────────────────────────────────┘
 * </pre>
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
    public static final byte VERSION = 1;

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
            if (ep == null) ep = "";
            byte[] epBytes = ep.getBytes(StandardCharsets.UTF_8);
            dos.writeShort(epBytes.length);
            dos.write(epBytes);

            // Node count
            List<HnswNodeData> nodeList = index.exportNodes();
            dos.writeInt(nodeList.size());

            // Nodes
            for (HnswNodeData node : nodeList) {
                byte[] idBytes = node.id.getBytes(StandardCharsets.UTF_8);
                dos.writeShort(idBytes.length);
                dos.write(idBytes);
                dos.writeByte(node.level);

                // Vector (dim * 4 bytes)
                for (int i = 0; i < index.dimension(); i++) {
                    dos.writeFloat(node.vector[i]);
                }

                // Neighbor lists for each level 0..level
                for (int lvl = 0; lvl <= node.level; lvl++) {
                    List<String> neighbors = node.neighbors.get(lvl);
                    dos.writeInt(neighbors.size());
                    for (String nb : neighbors) {
                        byte[] nbBytes = nb.getBytes(StandardCharsets.UTF_8);
                        dos.writeShort(nbBytes.length);
                        dos.write(nbBytes);
                    }
                }
            }

            // CRC32
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
     */
    public static HnswIndex load(String filePath, Distance distance) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new IOException("HNSW file not found: " + filePath);
        }

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            byte[] allBytes = new byte[(int) raf.length()];
            raf.readFully(allBytes);

            try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(allBytes))) {
                // Header
                byte[] magic = new byte[4];
                dis.readFully(magic);
                if (!MAGIC.equals(new String(magic, StandardCharsets.US_ASCII))) {
                    throw new IOException("Invalid HNSW magic");
                }
                byte version = dis.readByte();
                if (version != VERSION) {
                    throw new IOException("Unsupported HNSW version: " + version);
                }
                int dimension = dis.readInt();
                int M = dis.readInt();
                int maxLevel = dis.readInt();
                int efConstruction = dis.readInt();
                int efSearch = dis.readInt();

                // entryPoint
                short epLen = dis.readShort();
                byte[] epBytes = new byte[epLen];
                dis.readFully(epBytes);
                String entryPoint = epLen > 0 ? new String(epBytes, StandardCharsets.UTF_8) : null;

                int nodeCount = dis.readInt();

                // 创建 HNSW（不触发 build，通过内部 importNodes）
                HnswIndex index = new HnswIndex(distance, dimension,
                        M, efConstruction, efSearch);
                Map<String, HnswNodeData> nodeMap = new HashMap<>();

                // 读取所有节点
                for (int n = 0; n < nodeCount; n++) {
                    short idLen = dis.readShort();
                    byte[] idBytes = new byte[idLen];
                    dis.readFully(idBytes);
                    String id = new String(idBytes, StandardCharsets.UTF_8);

                    int level = dis.readByte() & 0xFF;

                    float[] vec = new float[dimension];
                    for (int i = 0; i < dimension; i++) {
                        vec[i] = dis.readFloat();
                    }

                    Map<Integer, List<String>> neighbors = new HashMap<>();
                    for (int lvl = 0; lvl <= level; lvl++) {
                        int nbCount = dis.readInt();
                        List<String> list = new ArrayList<>(nbCount);
                        for (int k = 0; k < nbCount; k++) {
                            short nbIdLen = dis.readShort();
                            byte[] nbIdBytes = new byte[nbIdLen];
                            dis.readFully(nbIdBytes);
                            list.add(new String(nbIdBytes, StandardCharsets.UTF_8));
                        }
                        neighbors.put(lvl, list);
                    }

                    nodeMap.put(id, new HnswNodeData(id, vec, level, neighbors));
                }

                index.importNodes(nodeMap, entryPoint);
                LOG.info("HNSW loaded: {} nodes from {}", nodeCount, path);
                return index;
            }
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

        public HnswNodeData(String id, float[] vector, int level,
                             Map<Integer, List<String>> neighbors) {
            this.id = id;
            this.vector = vector;
            this.level = level;
            this.neighbors = neighbors;
        }
    }
}