package com.zifang.z.vector.core.collection;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.Filter;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorCollection;
import com.zifang.z.vector.api.VectorException;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.core.distance.Distance;
import com.zifang.z.vector.core.distance.DistanceFactory;
import com.zifang.z.vector.core.filter.PayloadIndex;
import com.zifang.z.vector.core.index.Index;
import com.zifang.z.vector.core.index.IndexFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Collection 实现 — 一个集合的生命周期管理：向量存储 + ANN 索引 + 过滤搜索。
 * <p>
 * 这是 z-vector 的核心数据结构，类似于 Milvus 的 Collection 或 zvec 的 Collection.
 * <p>
 * 内部结构:
 * <pre>{@code
 *   Collection
 *   ├── schema: name/dim/metric/indexType
 *   └── index: Index (Flat/HNSW/IVF)         (自管数据存储 + 加速搜索)
 * }</pre>
 *
 * <h2>设计要点</h2>
 * <p>
 * Collection <b>委托</b>所有 CRUD 操作给底层 Index：Index 自管数据存储（ConcurrentHashMap），
 * Collection 只负责：
 * <ul>
 *   <li>维护 schema（dimension/metric/indexType）</li>
 *   <li>维护索引切换（switchIndex）</li>
 *   <li>提供线程安全的复合搜索（payload filter + ANN）</li>
 *   <li>提供 flush/close 等生命周期方法</li>
 * </ul>
 *
 * <h2>并发模型</h2>
 * <ul>
 *   <li>读操作（get/search）使用读锁，可并发</li>
 *   <li>写操作（upsert/delete）使用写锁，串行</li>
 *   <li>索引构建（buildIndex）使用写锁，可能阻塞读写</li>
 * </ul>
 */
public class Collection {

    private static final Logger LOG = LoggerFactory.getLogger(Collection.class);

    /** 过滤搜索时每轮的候选放大倍数（见 {@link #filteredSearch}） */
    private static final int FILTER_OVERFETCH = 8;

    private final VectorCollection schema;
    private Index index;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile boolean indexDirty = false;
    private volatile long lastFlushTimestamp = 0;

    /**
     * payload 倒排索引（field → value → ids）。默认开启：所有标量 payload 字段都登记。
     * 用于把"过滤后的最近邻"从"扫全量 / 放大候选"降级成"只对命中集算距离"。
     */
    private final PayloadIndex payloadIndex = new PayloadIndex();
    private final Distance distance;
    /** 已登记进 payloadIndex 的点数；与 index.size() 不等即视为不同步，快路径不作数 */
    private int payloadTracked = 0;
    /** 观测用：走 payload 倒排快路径的次数（测试据此断言快路径真的被走到） */
    private final AtomicLong payloadFastPath = new AtomicLong();

    public Collection(VectorCollection schema) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.index = IndexFactory.create(schema.getIndexType(), schema.getMetric(),
                schema.getDimension(), schema.getConfig());
        this.distance = DistanceFactory.create(schema.getMetric());
    }

    public VectorCollection getSchema() { return schema; }
    public String getName() { return schema.getName(); }
    public int getDimension() { return schema.getDimension(); }
    public DistanceMetric getMetric() { return schema.getMetric(); }
    public IndexType getIndexType() { return schema.getIndexType(); }

    // ==================== CRUD（委托给 Index）====================

    public void upsert(VectorPoint point) {
        validateDimension(point);
        lock.writeLock().lock();
        try {
            upsertLocked(point);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void upsertBatch(List<VectorPoint> batch) {
        if (batch == null || batch.isEmpty()) return;
        lock.writeLock().lock();
        try {
            for (VectorPoint p : batch) {
                validateDimension(p);
                upsertLocked(p);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** upsert 的公共部分：写向量索引 + 同步 payload 倒排（调用方持写锁） */
    private void upsertLocked(VectorPoint point) {
        // 借用版回表：previous 只用来"这个 id 在不在"和"摘掉旧倒排"，读的是 map 本体。
        // 下一行 index.add() 换掉的是节点上的引用，previous 仍指着旧 map，倒排摘得干净。
        VectorPoint previous = index.getRef(point.getId());
        index.add(point);
        indexDirty = true;
        if (previous == null) {
            payloadTracked++;
        } else {
            unindexPayload(previous);
        }
        Map<String, Object> payload = point.getPayload();
        if (payload != null) {
            for (Map.Entry<String, Object> e : payload.entrySet()) {
                if (isIndexable(e.getValue())) payloadIndex.index(e.getKey(), e.getValue(), point.getId());
            }
        }
    }

    public VectorPoint getPoint(String id) {
        // 对外边界一律复制。HnswIndex.get() 本来就现场重建，但 FlatIndex / IvfIndex 的 get()
        // 交出的是**存着的那个点**本身：调用方 setPayload 会直接改进索引本体，payload 倒排还会
        // 照旧值留着（下次过滤既查不到新值也摘不掉旧值）。快路径改用借用视图之后，
        // "借用不外泄"这条线必须由这里钉死（见 PayloadIndexFastPathTest.publicPointStillIsACopy）。
        VectorPoint p = index.getRef(id);
        return p == null ? null : new VectorPoint(p.getId(), p.vectorRef(), p.payloadRef());
    }

    public boolean delete(String id) {
        lock.writeLock().lock();
        try {
            VectorPoint existing = index.getRef(id);
            if (!index.remove(id)) return false;
            if (existing != null) {
                unindexPayload(existing);
                payloadTracked--;
            }
            indexDirty = true;
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void deleteBatch(List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        lock.writeLock().lock();
        try {
            for (String id : ids) {
                VectorPoint existing = index.getRef(id);
                if (index.remove(id)) {
                    if (existing != null) {
                        unindexPayload(existing);
                        payloadTracked--;
                    }
                    indexDirty = true;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public long count() {
        return index.size();
    }

    // ==================== 搜索 ====================

    public List<SearchResult> search(float[] query, int topK, Filter filter) {
        validateQuery(query);
        if (topK <= 0) return Collections.emptyList();
        if (filter == null) {
            return index.search(query, topK, null, Float.MAX_VALUE);
        }
        return filteredSearch(query, topK, filter, Float.MAX_VALUE);
    }

    public List<SearchResult> searchRange(float[] query, float maxDistance, int topK,
                                          Filter filter) {
        validateQuery(query);
        if (topK <= 0) return Collections.emptyList();
        if (filter == null) {
            return index.searchRange(query, maxDistance, topK, null);
        }
        return filteredSearch(query, topK, filter, maxDistance);
    }

    /**
     * 带 payload 过滤的搜索 — 核心约束：<b>不能"只问索引要 topK 条、然后 post-filter"</b>。
     * <p>
     * 那种写法等价于"先取最近的 10 个点，再从这 10 个点里挑 lang='zh' 的"：只要过滤条件有
     * 一点点选择性，返回行数就断崖掉到 0~2 行，而用户要的语义是<b>"过滤之后</b>的最近邻"。
     * HNSW 上更糟 — 它的 filter 在 ef 窗口内生效，命中数与窗口外的匹配点完全无关。
     * <p>
     * 现在的做法：
     * <ol>
     *   <li>纯等值条件（{@link Filter#toFlatPayload()}）下推进索引层，让图遍历 / 簇扫描时
     *       就跳过不匹配的点，不必白算距离；</li>
     *   <li>候选不足 topK 时按 {@link #FILTER_OVERFETCH} 倍放大重取，直到取满 topK，
     *       或已经问遍了索引里的每一个点。</li>
     * </ol>
     * 代价：过滤越挑剔，越接近全量扫描（最坏 O(N)，与 Flat 暴力扫同量级）。在没有 payload
     * 倒排索引的前提下这是"过滤结果正确"的必要成本 — 宁可慢，不可静默少返回。
     * <p>
     * <b>终止条件里不能用 {@code raw.size() < fetch}</b>（看起来像"索引已经没有更多候选了"）：
     * 对 HNSW / IVF 这类窗口型索引，短返回只说明"ef / nprobe 窗口里匹配的少"，窗口外还有
     * 大量匹配点；实测 1.3% 命中率的过滤会因此被误判为"取尽了"而返回 0 行。只有
     * {@code fetch >= size} 才真的代表问遍了全量。
     */
    private List<SearchResult> filteredSearch(float[] query, int topK, Filter filter,
                                              float maxDistance) {
        List<SearchResult> fast = payloadFilteredSearch(query, topK, filter, maxDistance);
        if (fast != null) return fast;

        Map<String, Object> pushdown = filter.toFlatPayload();
        int size = index.size();
        int fetch = Math.max(topK,
                (int) Math.min((long) topK * FILTER_OVERFETCH, Math.max((long) size, topK)));
        List<SearchResult> out;
        while (true) {
            List<SearchResult> raw = index.searchRange(query, maxDistance, fetch, pushdown);
            out = new ArrayList<>(Math.min(topK, raw.size()));
            for (SearchResult r : raw) {          // raw 已按距离升序
                if (filter.evaluate(r.getPayload())) {
                    out.add(r);
                    if (out.size() == topK) break;
                }
            }
            if (out.size() >= topK || fetch >= size) return out;
            fetch = (int) Math.min((long) fetch * FILTER_OVERFETCH, (long) size);
        }
    }

    /**
     * payload 倒排快路径：先把 Filter 翻译成命中 id 集合，只对这集合算距离。
     * <p>
     * 与 {@link #filteredSearch} 的放大候选路径相比，它给出的是<b>精确</b>的"过滤后最近邻"
     * （没有 ANN 窗口漏点的问题），代价是 O(命中数) 次距离计算 —— 所以只在命中集"够小"时
     * 采用；命中集接近全量时，在图上跑一遍窗口反而更便宜，返回 {@code null} 让给慢路径。
     * <p>
     * <b>只在命中集的"够小"这一侧省：</b>没进前 K 的点连 {@link SearchResult} 都不建。
     * 建一个就要复制一份 payload（结果生命周期长于查询，那次复制是真实保护），而快路径
     * 上限是 {@code max(topK*8, size/64)} 个命中、只返回 topK —— 80 命中取 10 的配置里，
     * 那 70 份当场就是垃圾（实测约占整次查询分配量的三分之二，见
     * {@code CollectionFastPathAllocationTest} 钉的字节线）。
     *
     * @return 精确结果；{@code null} 表示"这次答不了"，调用方走慢路径
     */
    private List<SearchResult> payloadFilteredSearch(float[] query, int topK, Filter filter,
                                                     float maxDistance) {
        Set<String> ids = resolvePayload(filter);
        if (ids == null) return null;
        int size = index.size();
        if (ids.size() > Math.max((long) topK * FILTER_OVERFETCH, size / 64)) return null;

        // 定长插入式选择，结果与"全量收集 + 稳定升序排序 + 截断前 K"逐元素相同：
        //   - 满了且 d 不严格优于已保留的最差值 ⇒ 丢（等距离保住先来者，与稳定排序截断后一致）；
        //   - 插入时只把 Float.compare 判定"更差"的槽位后移（等距离不后移 ⇒ 后来者排在其后）。
        // 比较一律走 Float.compare 而不是裸 </>：它和旧写法（Comparator.comparingDouble 的
        // Double.compare，作用在 float 提升后的分数上）对每个 float 值判序一致 —— NaN 判最大、
        // -0.0 排在 0.0 前（IP 度量下与查询正交的点分数恰好是 -0.0f）。
        // best[i].getId() 就是倒排里的那个 id —— 三个索引都按 p.getId() 建键（见 Index.getRef）。
        VectorPoint[] best = new VectorPoint[topK];
        float[] scores = new float[topK];
        int kept = 0;
        for (String id : ids) {
            // 回表借用节点本体：读完就走，不 clone 向量也不复制 payload 表
            VectorPoint p = index.getRef(id);
            if (p == null) {
                // 倒排说"有这个点"、向量索引说"没有" ⇒ 两边不同步，宁可整体退回慢路径，
                // 也不要拿半截结果冒充精确答案
                return null;
            }
            float d = distance.compute(query, p.vectorRef());
            if (d > maxDistance) continue;
            if (kept == topK && Float.compare(d, scores[topK - 1]) >= 0) continue;
            int at = (kept == topK) ? topK - 1 : kept;
            while (at > 0 && Float.compare(scores[at - 1], d) > 0) {
                scores[at] = scores[at - 1];
                best[at] = best[at - 1];
                at--;
            }
            scores[at] = d;
            best[at] = p;
            if (kept < topK) kept++;
        }
        List<SearchResult> hits = new ArrayList<>(kept);
        for (int i = 0; i < kept; i++) {
            hits.add(new SearchResult(best[i].getId(), scores[i], best[i].payloadRef()));
        }
        payloadFastPath.incrementAndGet();
        return hits;
    }

    public List<List<SearchResult>> searchBatch(List<float[]> queries, int topK, Filter filter) {
        if (queries == null || queries.isEmpty()) return Collections.emptyList();
        List<List<SearchResult>> results = new ArrayList<>(queries.size());
        for (float[] q : queries) {
            results.add(search(q, topK, filter));
        }
        return results;
    }

    // ==================== 索引 ====================

    public void buildIndex() {
        lock.writeLock().lock();
        try {
            LOG.info("Building index for collection '{}' ({} points, type={})",
                    getName(), index.size(), index.type());
            index.build(index.entries());
            indexDirty = false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean isIndexed() {
        return index.isBuilt() && !indexDirty;
    }

    public boolean isIndexDirty() {
        return indexDirty;
    }

    public Index getIndex() {
        return index;
    }

    /** 切换索引类型（需要重建） */
    public void switchIndex(IndexType newType, Map<String, Object> params) {
        lock.writeLock().lock();
        try {
            Index newIndex = IndexFactory.create(newType, schema.getMetric(),
                    schema.getDimension(), params);
            newIndex.build(this.index.entries());
            this.index = newIndex;
            this.indexDirty = false;
            rebuildPayloadIndex();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 原子替换底层索引 — 仅由持久化层的 {@code recover()} 使用，用于把磁盘加载好的
     * HNSW 图直接接管，避免每次重启都全量重建。
     * <p>
     * <b>调用方契约</b>：
     * <ul>
     *   <li>本方法内部已获取写锁，外部调用方<b>不要</b>在外层再加锁（避免死锁）；</li>
     *   <li>传入的 {@code newIndex} 必须与原 index 持有相同的点集合（id 一致、vector 一致）；</li>
     *   <li>替换后 {@code indexDirty = false}，因为它代表一个刚被持久化加载的"干净"索引。</li>
     * </ul>
     */
    public void replaceIndex(Index newIndex) {
        lock.writeLock().lock();
        try {
            Objects.requireNonNull(newIndex, "newIndex");
            if (newIndex.dimension() != schema.getDimension()) {
                throw new VectorException(
                        "replaceIndex dimension mismatch: expected " + schema.getDimension()
                                + ", got " + newIndex.dimension());
            }
            this.index = newIndex;
            this.indexDirty = false;
            rebuildPayloadIndex();
            LOG.info("Index replaced for collection '{}': dim={}, size={}, built={}",
                    getName(), newIndex.dimension(), newIndex.size(), newIndex.isBuilt());
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== 维护 ====================

    public long lastFlushTimestamp() { return lastFlushTimestamp; }
    public void markFlushed() { this.lastFlushTimestamp = System.currentTimeMillis(); }

    /** 清空所有点（保留 schema） */
    public void clear() {
        lock.writeLock().lock();
        try {
            index.clear();
            payloadIndex.clear();
            payloadTracked = 0;
            indexDirty = false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== payload 倒排索引 ====================

    /** 只有标量值可进倒排；List / Map 类型的 payload 由 post-filter 路径处理 */
    private static boolean isIndexable(Object v) {
        return v instanceof String || v instanceof Number || v instanceof Boolean;
    }

    private void unindexPayload(VectorPoint p) {
        // 只读遍历，且调用方传进来的多是索引借出的节点本体 ⇒ 走引用版，省掉
        // getPayload() 那层每调用一次的 unmodifiableMap 壳
        for (Map.Entry<String, Object> e : p.payloadRef().entrySet()) {
            if (isIndexable(e.getValue())) payloadIndex.remove(e.getKey(), e.getValue(), p.getId());
        }
    }

    /**
     * 从向量索引里现存的全部点重建倒排。
     * <p>
     * 只用在"索引整体被换掉"的场合（switchIndex / recover 后的 replaceIndex）。
     * 注意：如果换进来的索引没带 payload（HNSW 快照恢复就是这种情况，见
     * {@code HnswPersistence}），这里会得到一个空的倒排表 —— 此时 {@link #resolvePayload}
     * 返回 null，过滤搜索自动退回放大候选的慢路径，<b>不会</b>给出错误结果。
     */
    private void rebuildPayloadIndex() {
        payloadIndex.clear();
        payloadTracked = 0;
        for (VectorPoint p : index.entries()) {
            payloadTracked++;
            Map<String, Object> payload = p.getPayload();
            if (payload == null) continue;
            for (Map.Entry<String, Object> e : payload.entrySet()) {
                if (isIndexable(e.getValue())) payloadIndex.index(e.getKey(), e.getValue(), p.getId());
            }
        }
    }

    /**
     * 用 payload 倒排把 Filter 翻译成命中 id 集合。
     * 返回 {@code null} 表示"倒排答不了或不可信"，调用方必须走放大候选的通用路径。
     */
    private Set<String> resolvePayload(Filter filter) {
        if (payloadTracked != index.size()) {
            // 与向量索引不同步（例如外部直接换过 index）——倒排里少点，拿它答就会静默少返回
            return null;
        }
        return payloadIndex.resolve(filter);
    }

    /** 观测：走 payload 倒排快路径的次数 */
    public long payloadFastPathHits() { return payloadFastPath.get(); }

    /** 观测：倒排里已登记的字段数 */
    public int payloadIndexedFields() { return payloadIndex.size(); }

    // ==================== 内部校验 ====================

    private void validateDimension(VectorPoint p) {
        if (p.getDimension() != schema.getDimension()) {
            throw new VectorException(
                    "Dimension mismatch: expected " + schema.getDimension()
                            + ", got " + p.getDimension());
        }
    }

    private void validateQuery(float[] query) {
        if (query.length != schema.getDimension()) {
            throw new VectorException(
                    "Query dimension mismatch: expected " + schema.getDimension()
                            + ", got " + query.length);
        }
    }

    private String indexType() {
        return index.type();
    }
}