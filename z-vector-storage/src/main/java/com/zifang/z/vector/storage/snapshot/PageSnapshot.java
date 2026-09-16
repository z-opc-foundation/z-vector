package com.zifang.z.vector.storage.snapshot;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.storage.page.Page;
import com.zifang.z.vector.storage.page.PageId;
import com.zifang.z.vector.storage.page.PageStore;
import com.zifang.z.vector.storage.page.PageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 增量 Snapshot — 与 {@link Snapshot}（全量 JSON dump）并存，按 page 粒度写入。
 * <p>
 * 设计动机：现有 Snapshot 每次都序列化所有点；数据量大时磁盘 I/O 是主要瓶颈。
 * 增量 snapshot 只持久化自上次 checkpoint 以来变化的 page，重启时按 page 列表加载。
 *
 * <h2>文件布局</h2>
 * <pre>
 * &lt;dataDir&gt;/psnap_meta.bin     # 元数据：collection 列表 + 每集合 page 索引
 * &lt;dataDir&gt;/psnap_&lt;cid&gt;_&lt;type&gt;_&lt;pageNo&gt;.bin   # 单个 page
 * </pre>
 *
 * <h2>写入流程</h2>
 * <ol>
 *   <li>调用方收集自上次 checkpoint 以来 dirty 的 {@link PageId} 列表；</li>
 *   <li>{@link #writePageSnapshot(List)} 写入 psnap_meta.bin，记录所有 pageId；</li>
 *   <li>同时把每个 page 写入独立的 psnap_*.bin 文件（用 {@link PageStore} 的现有机制）；</li>
 *   <li>重启时先读 psnap_meta.bin 拿到所有 pageId，再批量加载。</li>
 * </ol>
 *
 * <h2>限制</h2>
 * <p>
 * 当前实现不存 payload（payload 仍在 PageStore 的 pages_&lt;cid&gt;.pgs 文件里）。
 * psnap_meta 只记录「哪些 page 属于这次 snapshot」，类似 LSM 的 manifest。
 */
public class PageSnapshot {

    private static final Logger LOG = LoggerFactory.getLogger(PageSnapshot.class);

    public static final String META_FILE = "psnap_meta.bin";
    public static final byte[] MAGIC = {'P', 'S', 'N', '2'};  // v2: 带 schema
    public static final byte VERSION = 2;

    /** v1（仅 pageId 列表）的 magic — 旧格式 */
    public static final byte[] MAGIC_V1 = {'P', 'S', 'N', '1'};

    /** 一条 psnap 记录 */
    public static class Entry {
        public final PageId pageId;

        public Entry(PageId pageId) {
            this.pageId = pageId;
        }
    }

    /** readAll() 返回的复合结果：entries + schemas */
    public static class AllData {
        public final List<Entry> entries;
        public final List<HybridSnapshot.CollectionSchema> schemas;

        public AllData(List<Entry> entries, List<HybridSnapshot.CollectionSchema> schemas) {
            this.entries = entries;
            this.schemas = schemas;
        }
    }

    private final String dataDir;

    public PageSnapshot(String dataDir) {
        this.dataDir = dataDir;
    }

    public String getDataDir() { return dataDir; }

    public Path metaPath() {
        return Paths.get(dataDir, META_FILE);
    }

    public boolean exists() {
        return Files.exists(metaPath());
    }

    /**
     * 写入一份增量 snapshot。
     * <p>
     * 先写 meta（包含所有 pageId 与时间戳），再触发每个 page 的持久化（调用 PageStore）。
     *
     * @param entries 本次 snapshot 覆盖的 page 列表
     * @param pageStore 用于校验 + 持久化的 PageStore（按 pageId.collectionId 取）
     */
    public void write(List<Entry> entries, PageStore pageStore) throws IOException {
        if (entries == null || entries.isEmpty()) {
            LOG.info("PageSnapshot.write: empty entries, skip");
            return;
        }
        Path meta = metaPath();
        if (meta.getParent() != null) Files.createDirectories(meta.getParent());

        // 先把每个 page 写入 PageStore（原子写入 page 文件）
        for (Entry e : entries) {
            Page p = pageStore.read(e.pageId);  // 验证 page 存在
            // 已经在 BufferPool 内的脏页需要先写回 PageStore，再做 snapshot
            // 这里调用方负责在调用 write 之前 flushDirty
            pageStore.write(p);
        }

        // 写 meta：magic(v1) + version + count + entries
        try (DataOutputStream dos = new DataOutputStream(
                Files.newOutputStream(meta,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE))) {
            dos.write(MAGIC_V1);
            dos.writeByte(1);  // v1
            dos.writeLong(System.currentTimeMillis());
            dos.writeInt(entries.size());
            for (Entry e : entries) {
                dos.writeInt(e.pageId.collectionId());
                dos.writeByte(e.pageId.type().code());
                dos.writeInt(e.pageId.pageNo());
            }
        }
        LOG.info("PageSnapshot written: {} entries (v1) to {}", entries.size(), meta);
    }

    /** 读取 meta 文件拿到所有 pageId（v1 兼容：legacy 入口只读 v1）。 */
    public List<Entry> read() throws IOException {
        Path meta = metaPath();
        if (!Files.exists(meta)) return new ArrayList<>();

        List<Entry> entries = new ArrayList<>();
        try (DataInputStream dis = new DataInputStream(Files.newInputStream(meta))) {
            byte[] magic = new byte[4];
            dis.readFully(magic);
            boolean v1 = matchesMagic(magic, MAGIC_V1);
            boolean v2 = matchesMagic(magic, MAGIC);
            if (!v1 && !v2) {
                throw new IOException("Invalid PageSnapshot magic");
            }
            byte version = dis.readByte();
            // 跳过 timestamp（两种格式都有）
            dis.readLong();
            // v1: 直接 entries count；v2: schemas count + entries count
            int count;
            if (v1) {
                count = dis.readInt();
            } else {
                int schemaCount = dis.readInt();
                // skip schemas
                for (int s = 0; s < schemaCount; s++) {
                    short nameLen = dis.readShort();
                    dis.skipBytes(nameLen);
                    dis.readInt();  // dimension
                    short metricLen = dis.readShort();
                    dis.skipBytes(metricLen);
                    short typeLen = dis.readShort();
                    dis.skipBytes(typeLen);
                    int paramCount = dis.readInt();
                    for (int p = 0; p < paramCount; p++) {
                        short kLen = dis.readShort();
                        dis.skipBytes(kLen);
                        int vLen = dis.readInt();
                        dis.skipBytes(vLen);
                    }
                }
                count = dis.readInt();
            }
            for (int i = 0; i < count; i++) {
                int cid = dis.readInt();
                PageType type = PageType.fromCode(dis.readByte());
                int pageNo = dis.readInt();
                PageId id = new PageId(cid, type, pageNo);
                entries.add(new Entry(id));
            }
            LOG.info("PageSnapshot.read: {} entries (version={})", entries.size(), version);
        }
        return entries;
    }

    /**
     * 写入 v2 格式：entries + schemas（页面 + 集合 schema）。
     */
    public void writeAll(List<Entry> entries, List<HybridSnapshot.CollectionSchema> schemas) throws IOException {
        if (entries == null) entries = new ArrayList<>();
        if (schemas == null) schemas = new ArrayList<>();
        Path meta = metaPath();
        if (meta.getParent() != null) Files.createDirectories(meta.getParent());

        try (DataOutputStream dos = new DataOutputStream(
                Files.newOutputStream(meta,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE))) {
            dos.write(MAGIC);
            dos.writeByte(VERSION);
            dos.writeLong(System.currentTimeMillis());
            // schemas
            dos.writeInt(schemas.size());
            for (HybridSnapshot.CollectionSchema s : schemas) {
                byte[] nameBytes = s.name.getBytes(StandardCharsets.UTF_8);
                dos.writeShort(nameBytes.length);
                dos.write(nameBytes);
                dos.writeInt(s.dimension);
                byte[] metricBytes = s.metric.name().getBytes(StandardCharsets.UTF_8);
                dos.writeShort(metricBytes.length);
                dos.write(metricBytes);
                byte[] typeBytes = s.indexType.name().getBytes(StandardCharsets.UTF_8);
                dos.writeShort(typeBytes.length);
                dos.write(typeBytes);
                // indexParams（暂用空 map）
                dos.writeInt(s.indexParams != null ? s.indexParams.size() : 0);
                if (s.indexParams != null) {
                    for (Map.Entry<String, Object> kv : s.indexParams.entrySet()) {
                        byte[] kBytes = kv.getKey().getBytes(StandardCharsets.UTF_8);
                        dos.writeShort(kBytes.length);
                        dos.write(kBytes);
                        byte[] vBytes = String.valueOf(kv.getValue()).getBytes(StandardCharsets.UTF_8);
                        dos.writeInt(vBytes.length);
                        dos.write(vBytes);
                    }
                }
            }
            // entries
            dos.writeInt(entries.size());
            for (Entry e : entries) {
                dos.writeInt(e.pageId.collectionId());
                dos.writeByte(e.pageId.type().code());
                dos.writeInt(e.pageId.pageNo());
            }
        }
        LOG.info("PageSnapshot.writeAll: {} schemas, {} entries",
                schemas.size(), entries.size());
    }

    /** 读 v2 格式：返回 entries + schemas。 */
    public AllData readAll() throws IOException {
        Path meta = metaPath();
        if (!Files.exists(meta)) return new AllData(new ArrayList<>(), new ArrayList<>());

        try (DataInputStream dis = new DataInputStream(Files.newInputStream(meta))) {
            byte[] magic = new byte[4];
            dis.readFully(magic);
            // 先比对 v2 magic
            if (matchesMagic(magic, MAGIC)) {
                return readV2(dis);
            }
            // 再比对 v1 magic
            if (matchesMagic(magic, MAGIC_V1)) {
                return readV1Entries(dis);
            }
            throw new IOException("Invalid PageSnapshot magic: expected PSN2 or PSN1, got " +
                    new String(magic, StandardCharsets.US_ASCII));
        }
    }

    private static boolean matchesMagic(byte[] got, byte[] expected) {
        if (got.length != expected.length) return false;
        for (int i = 0; i < got.length; i++) {
            if (got[i] != expected[i]) return false;
        }
        return true;
    }

    private AllData readV2(DataInputStream dis) throws IOException {
        byte version = dis.readByte();
        if (version != VERSION) {
            throw new IOException("Unsupported PageSnapshot version: " + version);
        }
        long timestamp = dis.readLong();
        // schemas
        int schemaCount = dis.readInt();
        List<HybridSnapshot.CollectionSchema> schemas = new ArrayList<>(schemaCount);
        for (int s = 0; s < schemaCount; s++) {
            short nameLen = dis.readShort();
            byte[] nameBytes = new byte[nameLen];
            dis.readFully(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            int dimension = dis.readInt();
            short metricLen = dis.readShort();
            byte[] metricBytes = new byte[metricLen];
            dis.readFully(metricBytes);
            DistanceMetric metric = DistanceMetric.valueOf(new String(metricBytes, StandardCharsets.UTF_8));
            short typeLen = dis.readShort();
            byte[] typeBytes = new byte[typeLen];
            dis.readFully(typeBytes);
            IndexType indexType = IndexType.valueOf(new String(typeBytes, StandardCharsets.UTF_8));
            int paramCount = dis.readInt();
            Map<String, Object> params = new java.util.HashMap<>(paramCount);
            for (int p = 0; p < paramCount; p++) {
                short kLen = dis.readShort();
                byte[] kBytes = new byte[kLen];
                dis.readFully(kBytes);
                int vLen = dis.readInt();
                byte[] vBytes = new byte[vLen];
                dis.readFully(vBytes);
                params.put(new String(kBytes, StandardCharsets.UTF_8),
                        new String(vBytes, StandardCharsets.UTF_8));
            }
            schemas.add(new HybridSnapshot.CollectionSchema(name, dimension, metric, indexType, params));
        }
        // entries
        int count = dis.readInt();
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int cid = dis.readInt();
            PageType type = PageType.fromCode(dis.readByte());
            int pageNo = dis.readInt();
            PageId id = new PageId(cid, type, pageNo);
            entries.add(new Entry(id));
        }
        LOG.info("PageSnapshot.readV2: {} schemas, {} entries (ts={})",
                schemas.size(), entries.size(), timestamp);
        return new AllData(entries, schemas);
    }

    /** v1 fallback：仅返回 entries（schemas 留给 Snapshot.json 提供）。 */
    private AllData readV1Entries(DataInputStream dis) throws IOException {
        byte version = dis.readByte();
        long timestamp = dis.readLong();
        int count = dis.readInt();
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int cid = dis.readInt();
            PageType type = PageType.fromCode(dis.readByte());
            int pageNo = dis.readInt();
            PageId id = new PageId(cid, type, pageNo);
            entries.add(new Entry(id));
        }
        LOG.info("PageSnapshot.readV1Entries: {} entries (ts={})", entries.size(), timestamp);
        return new AllData(entries, new ArrayList<>());
    }

    /**
     * 把 PageSnapshot 元数据中记录的所有 page 加载到 BufferPool。
     * <p>
     * 调用方负责：提供对应的 PageStore / BufferPool。
     */
    public int loadInto(BufferPoolFacade facade) throws IOException {
        AllData ad = readAll();
        if (ad.entries.isEmpty()) return 0;
        int loaded = 0;
        Set<Integer> cids = new HashSet<>();
        for (Entry e : ad.entries) {
            cids.add(e.pageId.collectionId());
            Page p = facade.readPage(e.pageId);
            facade.cachePage(p);
            loaded++;
        }
        LOG.info("PageSnapshot.loadInto: loaded {} pages across {} collections",
                loaded, cids.size());
        return loaded;
    }

    /** 删除 psnap 文件（用于 reset 或 snapshot 损坏时）。 */
    public void delete() throws IOException {
        Files.deleteIfExists(metaPath());
    }

    /** 简单的 facade 接口，避免 PageSnapshot 反向依赖 BufferPool 内部。 */
    public interface BufferPoolFacade {
        Page readPage(PageId id) throws IOException;
        void cachePage(Page page);
    }
}
