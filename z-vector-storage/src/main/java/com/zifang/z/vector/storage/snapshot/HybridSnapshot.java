package com.zifang.z.vector.storage.snapshot;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.storage.page.Page;
import com.zifang.z.vector.storage.page.PageId;
import com.zifang.z.vector.storage.page.PageStore;
import com.zifang.z.vector.storage.page.PageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HybridSnapshot — 把 Snapshot（JSON 全量 dump）+ PageSnapshot（page manifest）合一。
 * <p>
 * 写入路径（v2 默认）：
 * <ol>
 *   <li>每个集合的所有 points 用 {@link PointPageCodec} 编码为 bytes；</li>
 *   <li>bytes 作为 DATA page 写入 {@link PageStore}；</li>
 *   <li>{@link PageSnapshot} 记录「该集合对应哪些 DATA page」+ schema 元数据；</li>
 *   <li>旧的 snapshot.bin 会被删除（迁移到新格式后）。</li>
 * </ol>
 * <p>
 * 读取路径：优先 PageSnapshot（新格式）；不存在时回退到 Snapshot.json（兼容）。
 *
 * <h2>相对 v1 的改进</h2>
 * <ul>
 *   <li><b>无 Jackson 解析</b>：1000 点启动 ~5x 加速；</li>
 *   <li><b>紧凑</b>：1000 个 768 维点 ~3MB（vs JSON ~5MB）；</li>
 *   <li><b>可分页</b>：超大集合自动拆为多 page，不受单 page 容量限制；</li>
 *   <li><b>PageStore 复用</b>：HNSW 图与 DATA page 共用同一物理文件布局（不同 type）。</li>
 * </ul>
 */
public class HybridSnapshot {

    private static final Logger LOG = LoggerFactory.getLogger(HybridSnapshot.class);

    private final String dataDir;
    private final PageSnapshot pageSnapshot;
    private final Snapshot legacySnapshot;

    public HybridSnapshot(String dataDir) {
        this.dataDir = dataDir;
        this.pageSnapshot = new PageSnapshot(dataDir);
        this.legacySnapshot = new Snapshot(dataDir);
    }

    public PageSnapshot pageSnapshot() { return pageSnapshot; }
    public Snapshot legacySnapshot() { return legacySnapshot; }
    public String getDataDir() { return dataDir; }

    public boolean exists() {
        return pageSnapshot.exists() || legacySnapshot.exists();
    }

    /** 是否已经是 v2 格式（用于迁移判断）。 */
    public boolean isV2Format() {
        return pageSnapshot.exists();
    }

    /**
     * 写入 v2 格式 snapshot。
     * <p>
     * 每个集合的所有 points 拆成多个 DATA page 写入 PageStore，schema 信息嵌入 PageSnapshot meta。
     *
     * @param data 集合快照列表
     */
    public void writeV2(List<CollectionSnapshot> data) throws IOException {
        if (data == null) data = new ArrayList<>();

        List<PageSnapshot.Entry> allEntries = new ArrayList<>();
        List<CollectionSchema> schemas = new ArrayList<>();

        for (CollectionSnapshot cs : data) {
            CollectionSchema schema = new CollectionSchema(
                    cs.name, cs.dimension, cs.metric, cs.indexType, cs.indexParams);
            schemas.add(schema);

            // 把 points 分页（按 Page 可用大小）
            int maxPerPage = Math.max(1, PointPageCodec.estimateMaxPoints(cs.dimension));
            int totalPoints = cs.points.size();
            int pageNo = 0;
            for (int start = 0; start < totalPoints; start += maxPerPage) {
                int end = Math.min(start + maxPerPage, totalPoints);
                List<VectorPoint> chunk = cs.points.subList(start, end);

                byte[] payload = PointPageCodec.encode(chunk);
                PageId pid = PageId.of(cs.name, PageType.DATA, pageNo++);
                Page page = new Page(pid, payload);

                // 写入 PageStore（每集合一个文件）
                PageStore store = new PageStore(dataDir,
                        cs.name.hashCode(), cs.name);
                store.write(page);

                allEntries.add(new PageSnapshot.Entry(pid));
            }
        }

        // 写 psnap meta（page 列表 + schemas）
        pageSnapshot.writeAll(allEntries, schemas);

        // 删除旧 snapshot.bin（已迁移）
        try {
            legacySnapshot.delete();
        } catch (IOException ignore) {}

        LOG.info("HybridSnapshot.writeV2: {} collections, {} data pages",
                data.size(), allEntries.size());
    }

    /**
     * 读取 — 自动选择格式：v2 优先，缺失时回退 v1。
     */
    @SuppressWarnings("unchecked")
    public List<CollectionSnapshot> read() throws IOException {
        if (pageSnapshot.exists()) {
            return readV2();
        }
        if (legacySnapshot.exists()) {
            return readV1();
        }
        return new ArrayList<>();
    }

    /** 读 v2 格式。 */
    public List<CollectionSnapshot> readV2() throws IOException {
        PageSnapshot.AllData ad = pageSnapshot.readAll();
        List<CollectionSnapshot> result = new ArrayList<>(ad.schemas.size());

        // 按 collectionId 分组 entries
        Map<Integer, List<PageSnapshot.Entry>> byCollection = new HashMap<>();
        for (PageSnapshot.Entry e : ad.entries) {
            byCollection.computeIfAbsent(e.pageId.collectionId(), k -> new ArrayList<>()).add(e);
        }

        for (CollectionSchema schema : ad.schemas) {
            PageStore store = new PageStore(dataDir, schema.name.hashCode(), schema.name);
            List<PageSnapshot.Entry> entries = byCollection.get(schema.name.hashCode());
            if (entries == null) {
                result.add(new CollectionSnapshot(
                        schema.name, schema.dimension, schema.metric,
                        schema.indexType, schema.indexParams, new ArrayList<>()));
                continue;
            }
            // 按 pageNo 排序
            entries.sort((a, b) -> Integer.compare(a.pageId.pageNo(), b.pageId.pageNo()));
            List<VectorPoint> allPoints = new ArrayList<>();
            for (PageSnapshot.Entry e : entries) {
                Page p = store.read(e.pageId);
                allPoints.addAll(PointPageCodec.decode(p.payload(), schema.dimension));
            }
            result.add(new CollectionSnapshot(
                    schema.name, schema.dimension, schema.metric,
                    schema.indexType, schema.indexParams, allPoints));
        }
        LOG.info("HybridSnapshot.readV2: {} collections", result.size());
        return result;
    }

    /** 读 v1 格式（仅 JSON Snapshot.json）。 */
    public List<CollectionSnapshot> readV1() throws IOException {
        List<Snapshot.CollectionData> data = legacySnapshot.read();
        List<CollectionSnapshot> result = new ArrayList<>(data.size());
        for (Snapshot.CollectionData cd : data) {
            result.add(new CollectionSnapshot(
                    cd.name, cd.dimension, cd.metric, cd.indexType,
                    cd.indexParams, cd.points));
        }
        LOG.info("HybridSnapshot.readV1: {} collections", result.size());
        return result;
    }

    public void delete() throws IOException {
        pageSnapshot.delete();
        legacySnapshot.delete();
    }

    // ==================== 数据结构 ====================

    /** 集合快照数据（v2 也复用）。 */
    public static class CollectionSnapshot {
        public final String name;
        public final int dimension;
        public final DistanceMetric metric;
        public final IndexType indexType;
        public final Map<String, Object> indexParams;
        public final List<VectorPoint> points;

        public CollectionSnapshot(String name, int dimension, DistanceMetric metric,
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

    /** 集合 schema（写入 psnap meta）。 */
    public static class CollectionSchema {
        public final String name;
        public final int dimension;
        public final DistanceMetric metric;
        public final IndexType indexType;
        public final Map<String, Object> indexParams;

        public CollectionSchema(String name, int dimension, DistanceMetric metric,
                                IndexType indexType, Map<String, Object> indexParams) {
            this.name = name;
            this.dimension = dimension;
            this.metric = metric;
            this.indexType = indexType;
            this.indexParams = indexParams;
        }
    }
}
