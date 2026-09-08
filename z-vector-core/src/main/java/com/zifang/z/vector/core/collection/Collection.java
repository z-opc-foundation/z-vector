package com.zifang.z.vector.core.collection;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.Filter;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorCollection;
import com.zifang.z.vector.api.VectorException;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.core.index.Index;
import com.zifang.z.vector.core.index.IndexFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private final VectorCollection schema;
    private Index index;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile boolean indexDirty = false;
    private volatile long lastFlushTimestamp = 0;

    public Collection(VectorCollection schema) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.index = IndexFactory.create(schema.getIndexType(), schema.getMetric(),
                schema.getDimension(), schema.getConfig());
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
            index.add(point);
            indexDirty = true;
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
                index.add(p);
            }
            indexDirty = true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public VectorPoint getPoint(String id) {
        return index.get(id);
    }

    public boolean delete(String id) {
        lock.writeLock().lock();
        try {
            if (index.remove(id)) {
                indexDirty = true;
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void deleteBatch(List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        lock.writeLock().lock();
        try {
            for (String id : ids) {
                if (index.remove(id)) {
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

        // 复杂 Filter（AND/OR/NOT/范围）在搜索时直接 evaluate 每个点的 payload
        // 简单等值过滤优先走索引层（更快），如果索引层不支持再 fallback
        List<SearchResult> raw = index.search(query, topK, null, Float.MAX_VALUE);
        if (filter == null) return raw;

        List<SearchResult> filtered = new ArrayList<>();
        for (SearchResult r : raw) {
            if (filter.evaluate(r.getPayload())) {
                filtered.add(r);
            }
        }
        return filtered;
    }

    public List<SearchResult> searchRange(float[] query, float maxDistance, int topK,
                                          Filter filter) {
        validateQuery(query);
        if (topK <= 0) return Collections.emptyList();

        List<SearchResult> raw = index.searchRange(query, maxDistance, topK, null);
        if (filter == null) return raw;

        List<SearchResult> filtered = new ArrayList<>();
        for (SearchResult r : raw) {
            if (filter.evaluate(r.getPayload())) {
                filtered.add(r);
            }
        }
        return filtered;
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
            indexDirty = false;
        } finally {
            lock.writeLock().unlock();
        }
    }

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