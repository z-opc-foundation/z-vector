package com.zifang.z.vector.core;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.Filter;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorCollection;
import com.zifang.z.vector.api.VectorException;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.api.VectorStore;
import com.zifang.z.vector.core.collection.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内存版 VectorStore 实现 — 纯内存 + ANN 索引，无持久化。
 * <p>
 * 这是 z-vector 的核心实现之一，对标 zvec 的 in-process Python SDK。
 * <p>
 * <b>适用场景</b>：嵌入式 / 单元测试 / 小规模数据集（&lt; 100 万向量）
 * <br><b>不适用</b>：超大规模、需要持久化的场景（用 PersistentVectorStore）
 *
 * <h2>特性</h2>
 * <ul>
 *   <li>支持 Flat / HNSW / IVF 三种索引</li>
 *   <li>支持 L2 / IP / Cosine / Hamming 四种距离</li>
 *   <li>支持复杂 Filter 表达式（AND/OR/NOT）</li>
 *   <li>线程安全（每个 Collection 内部用 RWLock）</li>
 *   <li>支持批量搜索 + 范围搜索</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * try (VectorStore store = new InMemoryVectorStore()) {
 *     store.createCollection("docs", 768, DistanceMetric.COSINE, IndexType.HNSW, null);
 *     store.upsertBatch("docs", docs);
 *     store.buildIndex("docs");
 *     List<SearchResult> hits = store.search("docs", query, 10, Filter.eq("lang", "zh"));
 * }
 * }</pre>
 */
public class InMemoryVectorStore implements VectorStore {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryVectorStore.class);

    private final ConcurrentHashMap<String, Collection> collections = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public InMemoryVectorStore() {
        LOG.info("InMemoryVectorStore created");
    }

    // ==================== Collection 管理 ====================

    @Override
    public void createCollection(String name, int dimension, DistanceMetric metric) {
        createCollection(name, dimension, metric, IndexType.FLAT, null);
    }

    @Override
    public void createCollection(String name, int dimension, DistanceMetric metric,
                                  IndexType indexType, Map<String, Object> indexParams) {
        ensureNotClosed();
        if (collections.containsKey(name)) {
            throw new VectorException("Collection already exists: " + name);
        }
        if (dimension <= 0) {
            throw new VectorException("Dimension must be > 0, got " + dimension);
        }
        VectorCollection schema = new VectorCollection(name, dimension, metric,
                indexType, indexParams);
        Collection coll = new Collection(schema);
        collections.put(name, coll);
        LOG.info("Created collection: {} (dim={}, metric={}, index={})",
                name, dimension, metric, indexType);
    }

    @Override
    public VectorCollection getCollection(String name) {
        Collection coll = collections.get(name);
        return coll == null ? null : coll.getSchema();
    }

    @Override
    public boolean deleteCollection(String name) {
        Collection removed = collections.remove(name);
        if (removed != null) {
            LOG.info("Deleted collection: {}", name);
            return true;
        }
        return false;
    }

    @Override
    public List<String> listCollections() {
        return new ArrayList<>(collections.keySet());
    }

    @Override
    public boolean hasCollection(String name) {
        return collections.containsKey(name);
    }

    // ==================== 向量 CRUD ====================

    @Override
    public void upsert(String collectionName, VectorPoint point) {
        requireCollection(collectionName).upsert(point);
    }

    @Override
    public void upsertBatch(String collectionName, List<VectorPoint> points) {
        requireCollection(collectionName).upsertBatch(points);
    }

    @Override
    public VectorPoint getPoint(String collectionName, String id) {
        return requireCollection(collectionName).getPoint(id);
    }

    @Override
    public boolean deletePoint(String collectionName, String id) {
        return requireCollection(collectionName).delete(id);
    }

    @Override
    public void deletePoints(String collectionName, List<String> ids) {
        requireCollection(collectionName).deleteBatch(ids);
    }

    @Override
    public long getPointCount(String collectionName) {
        Collection coll = collections.get(collectionName);
        return coll == null ? 0 : coll.count();
    }

    // ==================== ANN 搜索 ====================

    @Override
    public List<SearchResult> search(String collectionName, float[] queryVector,
                                     int topK, Filter filter) {
        return requireCollection(collectionName).search(queryVector, topK, filter);
    }

    @Override
    public List<SearchResult> searchRange(String collectionName, float[] queryVector,
                                          float distanceThreshold, int topK, Filter filter) {
        return requireCollection(collectionName).searchRange(queryVector, distanceThreshold, topK, filter);
    }

    @Override
    public List<List<SearchResult>> searchBatch(String collectionName, List<float[]> queryVectors,
                                                int topK, Filter filter) {
        return requireCollection(collectionName).searchBatch(queryVectors, topK, filter);
    }

    // ==================== 索引 ====================

    @Override
    public void buildIndex(String collectionName) {
        requireCollection(collectionName).buildIndex();
    }

    @Override
    public boolean isIndexed(String collectionName) {
        Collection coll = collections.get(collectionName);
        return coll != null && coll.isIndexed();
    }

    @Override
    public IndexType getIndexType(String collectionName) {
        Collection coll = collections.get(collectionName);
        return coll == null ? null : coll.getIndexType();
    }

    // ==================== 持久化 ====================

    @Override
    public void flush(String collectionName) {
        Collection coll = requireCollection(collectionName);
        // 内存版 flush 主要是触发索引重建
        if (coll.isIndexDirty()) {
            coll.buildIndex();
        }
        coll.markFlushed();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            LOG.info("InMemoryVectorStore closing ({} collections)", collections.size());
            collections.clear();
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    // ==================== 内部方法 ====================

    private Collection requireCollection(String name) {
        ensureNotClosed();
        Collection coll = collections.get(name);
        if (coll == null) {
            throw new VectorException("Collection not found: " + name);
        }
        return coll;
    }

    private void ensureNotClosed() {
        if (closed.get()) {
            throw new VectorException("VectorStore is closed");
        }
    }
}