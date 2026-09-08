package com.zifang.z.vector.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.Filter;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorCollection;
import com.zifang.z.vector.api.VectorException;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.api.VectorStore;
import com.zifang.z.vector.core.collection.Collection;
import com.zifang.z.vector.core.distance.DistanceFactory;
import com.zifang.z.vector.core.index.HnswIndex;
import com.zifang.z.vector.core.index.HnswPersistence;
import com.zifang.z.vector.core.index.Index;
import com.zifang.z.vector.storage.engine.StorageEngine;
import com.zifang.z.vector.storage.snapshot.HybridSnapshot;
import com.zifang.z.vector.storage.wal.WalFile;
import com.zifang.z.vector.storage.wal.WalOpType;
import com.zifang.z.vector.storage.wal.WalRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 持久化版 VectorStore — 基于 WAL + Snapshot 的崩溃安全存储。
 * <p>
 * 对标 zvec 的 WAL + ForwardStore + LanceDB 的 Manifest + Faiss 的 I/O.
 *
 * <h2>工作流程</h2>
 * <ol>
 *   <li>启动：从 Snapshot 恢复 + 重放 WAL 中所有记录</li>
 *   <li>写入：先写 WAL（强制 fsync），再更新内存</li>
 *   <li>Checkpoint：定期（每 N 条记录或 M 分钟）创建 Snapshot + 截断 WAL</li>
 *   <li>崩溃恢复：启动时自动 replay WAL，恢复到崩溃前状态</li>
 * </ol>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * try (VectorStore store = new PersistentVectorStore("/data/zvec")) {
 *     store.createCollection("docs", 768, DistanceMetric.COSINE);
 *     store.upsert("docs", new VectorPoint("d1", new float[768]));
 *     store.flush("docs"); // 强制 checkpoint
 * }
 * }</pre>
 */
public class PersistentVectorStore implements VectorStore {

    private static final Logger LOG = LoggerFactory.getLogger(PersistentVectorStore.class);

    private final String dataDir;
    private final WalFile wal;
    private final HybridSnapshot snapshot;  // v2 默认：PageSnapshot + Snapshot 兼容
    private final StorageEngine engine;  // v2：异步 WAL + Bloom + BufferPool + PageStore
    private final boolean engineOwned;   // 我们自己 new 的还是外部传入
    private final ConcurrentHashMap<String, Collection> collections = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ObjectMapper json = new ObjectMapper();

    private final long checkpointInterval; // 每多少条 WAL 触发一次 snapshot
    private long walRecordsSinceCheckpoint = 0;

    public PersistentVectorStore(String dataDir) {
        this(dataDir, 1000, true); // 默认：1000 条 checkpoint + 启用 v2 引擎
    }

    public PersistentVectorStore(String dataDir, long checkpointInterval) {
        this(dataDir, checkpointInterval, true);
    }

    /**
     * @param useStorageEngine true = 启用 v2 引擎（AsyncWal + Bloom + BufferPool + PageStore）；<br>
     *                        false = 退回同步 WAL（用于诊断 / 测试兼容旧路径）。
     */
    public PersistentVectorStore(String dataDir, long checkpointInterval, boolean useStorageEngine) {
        this.dataDir = dataDir;
        this.checkpointInterval = checkpointInterval;
        try {
            this.wal = new WalFile(dataDir);
            this.snapshot = new HybridSnapshot(dataDir);
            this.engine = useStorageEngine ? new StorageEngine(dataDir, wal) : null;
            this.engineOwned = useStorageEngine;
            recover();
        } catch (IOException e) {
            throw new VectorException("Failed to initialize PersistentVectorStore: " + e.getMessage(), e);
        }
    }

    // ==================== 恢复 ====================

    /**
     * 启动恢复流程：
     * <ol>
     *   <li>从 snapshot 恢复 collections 的 schema 与 points；</li>
     *   <li>重放 WAL 中所有 upsert/delete，使 points 与磁盘一致；</li>
     *   <li>对每个非 FLAT 集合：
     *     <ul>
     *       <li>HNSW：先尝试从 {@code hnsw_<name>.bin} 加载，校验节点数一致后直接接管；</li>
     *       <li>其他（IVF）或 HNSW 文件缺失 / 损坏：调用 {@code buildIndex()} 重建。</li>
     *     </ul>
     *   </li>
     * </ol>
     * <p>
     * <b>设计权衡</b>：把 buildIndex 移到 WAL 重放之后，避免 snapshot 后又被 WAL 改写导致索引浪费。
     */
    private void recover() throws IOException {
        // 0. 如果启用了 v2 引擎，先 flush 所有 pending records 到磁盘
        if (engine != null) {
            engine.flushWal();
        }

        // 1. 从 snapshot 恢复（v2 PageSnapshot 优先，缺失时回退 v1 Snapshot.json）
        if (snapshot.exists()) {
            boolean v2 = snapshot.isV2Format();
            LOG.info("Loading snapshot from {} (format={})",
                    snapshot.legacySnapshot().getPath(), v2 ? "v2-page" : "v1-json");
            List<HybridSnapshot.CollectionSnapshot> data = snapshot.read();
            for (HybridSnapshot.CollectionSnapshot cs : data) {
                Collection coll = new Collection(new VectorCollection(
                        cs.name, cs.dimension, cs.metric, cs.indexType, cs.indexParams));
                collections.put(cs.name, coll);
                for (VectorPoint p : cs.points) {
                    coll.upsert(p);
                    if (engine != null) engine.markBloom(cs.name, p.getId());
                }
            }
        }

        // 2. 重放 WAL（覆盖 snapshot 中的状态）
        LOG.info("Replaying WAL...");
        List<WalRecord> records = wal.readAll();
        for (WalRecord rec : records) {
            applyRecord(rec);
            // 增量更新 bloom
            if (engine != null && rec.getCollection() != null) {
                if (rec.getOp() == WalOpType.UPSERT_POINT) {
                    try {
                        VectorPoint p = parsePoint(rec.getPayload());
                        engine.markBloom(rec.getCollection(), p.getId());
                    } catch (Exception ignore) {
                        // 部分损坏的 record 不应阻止 bloom 构建
                    }
                }
            }
        }
        // 重放完毕，flush 一次确保所有 apply 都落盘（仅当启用了引擎时）
        if (engine != null) engine.flushWal();

        // 3. 索引恢复：HNSW 持久化优先，否则重建
        for (Collection coll : collections.values()) {
            IndexType type = coll.getIndexType();
            if (type == IndexType.FLAT) continue;
            if (type == IndexType.HNSW && tryLoadHnswFromDisk(coll)) {
                continue;
            }
            // fallback：rebuild
            LOG.info("Rebuilding index for collection '{}' (type={}, points={})",
                    coll.getName(), type, coll.count());
            coll.buildIndex();
        }
        LOG.info("Recovery complete: {} collections, {} WAL records",
                collections.size(), records.size());
    }

    /**
     * 尝试从磁盘加载 HNSW 图并接管 collection 的底层索引。
     * <p>
     * 校验条件：节点数必须与 collection 持有的点数一致，否则视为不一致，回退到 rebuild。
     *
     * @return true 表示成功接管；false 表示应当回退到 buildIndex
     */
    private boolean tryLoadHnswFromDisk(Collection coll) {
        Path hnswPath = hnswFile(coll.getName());
        if (!Files.exists(hnswPath)) {
            LOG.info("HNSW file not found for '{}', will rebuild", coll.getName());
            return false;
        }
        try {
            HnswIndex loaded = HnswPersistence.load(
                    hnswPath.toString(),
                    DistanceFactory.create(coll.getMetric()));
            if (loaded.size() != coll.count()) {
                LOG.warn("HNSW file size mismatch for '{}': file={}, collection={}; falling back to rebuild",
                        coll.getName(), loaded.size(), coll.count());
                return false;
            }
            if (!loaded.isBuilt()) {
                LOG.warn("HNSW file is not in 'built' state for '{}'; falling back to rebuild",
                        coll.getName());
                return false;
            }
            coll.replaceIndex(loaded);
            LOG.info("HNSW loaded from disk for collection '{}': {} nodes (skipped rebuild)",
                    coll.getName(), loaded.size());
            return true;
        } catch (Exception e) {
            LOG.warn("Failed to load HNSW for '{}': {}; falling back to rebuild",
                    coll.getName(), e.getMessage());
            return false;
        }
    }

    private void applyRecord(WalRecord rec) throws IOException {
        switch (rec.getOp()) {
            case CREATE_COLLECTION:
                applyCreateCollection(rec.getPayload());
                break;
            case DELETE_COLLECTION:
                collections.remove(rec.getPayload());
                break;
            case UPSERT_POINT: {
                String collection = rec.getCollection();
                Collection coll = collections.get(collection);
                if (coll != null) {
                    VectorPoint p = parsePoint(rec.getPayload());
                    coll.upsert(p);
                }
                break;
            }
            case DELETE_POINT: {
                String collection = rec.getCollection();
                Collection coll = collections.get(collection);
                if (coll != null) {
                    Map<String, Object> m = parseJson(rec.getPayload());
                    coll.delete((String) m.get("id"));
                }
                break;
            }
            case BATCH_BEGIN:
            case BATCH_COMMIT:
            case CHECKPOINT:
                // 标记；无需特殊处理
                break;
            default:
                LOG.warn("Unknown WAL op: {}", rec.getOp());
        }
    }

    private void applyCreateCollection(String payload) throws IOException {
        Map<String, Object> m = parseJson(payload);
        String name = (String) m.get("name");
        int dimension = ((Number) m.get("dimension")).intValue();
        DistanceMetric metric = DistanceMetric.valueOf((String) m.get("metric"));
        IndexType indexType = IndexType.valueOf((String) m.get("index_type"));
        if (collections.containsKey(name)) return;
        Map<String, Object> params = (Map<String, Object>) m.get("index_params");
        Collection coll = new Collection(new VectorCollection(name, dimension, metric,
                indexType, params));
        collections.put(name, coll);
    }

    // ==================== VectorStore API ====================

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
        try {
            walAppend(WalRecord.createCollection(name, dimension, metric, indexType));
            Collection coll = new Collection(new VectorCollection(name, dimension, metric,
                    indexType, indexParams));
            collections.put(name, coll);
            walRecordsSinceCheckpoint++;
            maybeCheckpoint();
            LOG.info("Persistent collection created: {}", name);
        } catch (IOException e) {
            throw new VectorException("Failed to create collection: " + e.getMessage(), e);
        }
    }

    @Override
    public VectorCollection getCollection(String name) {
        Collection coll = collections.get(name);
        return coll == null ? null : coll.getSchema();
    }

    @Override
    public boolean deleteCollection(String name) {
        if (!collections.containsKey(name)) return false;
        try {
            walAppend(WalRecord.deleteCollection(name));
            // 同步删除 HNSW 磁盘文件（如果有），避免遗留
            try {
                Files.deleteIfExists(hnswFile(name));
            } catch (IOException e) {
                LOG.warn("Failed to delete HNSW file for '{}': {}", name, e.getMessage());
            }
            collections.remove(name);
            walRecordsSinceCheckpoint++;
            maybeCheckpoint();
            return true;
        } catch (IOException e) {
            throw new VectorException("Failed to delete collection: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> listCollections() {
        return new ArrayList<>(collections.keySet());
    }

    @Override
    public boolean hasCollection(String name) {
        return collections.containsKey(name);
    }

    @Override
    public void upsert(String collectionName, VectorPoint point) {
        Collection coll = requireCollection(collectionName);
        try {
            walAppend(WalRecord.upsertPoint(collectionName, point));
            if (engine != null) engine.markBloom(collectionName, point.getId());
            coll.upsert(point);
            walRecordsSinceCheckpoint++;
            maybeCheckpoint();
        } catch (IOException e) {
            throw new VectorException("Failed to upsert: " + e.getMessage(), e);
        }
    }

    @Override
    public void upsertBatch(String collectionName, List<VectorPoint> points) {
        Collection coll = requireCollection(collectionName);
        try {
            for (VectorPoint p : points) {
                walAppend(WalRecord.upsertPoint(collectionName, p));
                if (engine != null) engine.markBloom(collectionName, p.getId());
                coll.upsert(p);
            }
            walRecordsSinceCheckpoint++;
            maybeCheckpoint();
        } catch (IOException e) {
            throw new VectorException("Failed to upsert batch: " + e.getMessage(), e);
        }
    }

    @Override
    public VectorPoint getPoint(String collectionName, String id) {
        return requireCollection(collectionName).getPoint(id);
    }

    @Override
    public boolean deletePoint(String collectionName, String id) {
        try {
            walAppend(WalRecord.deletePoint(collectionName, id));
            boolean removed = requireCollection(collectionName).delete(id);
            walRecordsSinceCheckpoint++;
            maybeCheckpoint();
            return removed;
        } catch (IOException e) {
            throw new VectorException("Failed to delete point: " + e.getMessage(), e);
        }
    }

    @Override
    public void deletePoints(String collectionName, List<String> ids) {
        for (String id : ids) deletePoint(collectionName, id);
    }

    @Override
    public long getPointCount(String collectionName) {
        Collection coll = collections.get(collectionName);
        return coll == null ? 0 : coll.count();
    }

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

    @Override
    public void flush(String collectionName) {
        try {
            createSnapshot();
        } catch (IOException e) {
            throw new VectorException("Failed to flush: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                createSnapshot();
            } catch (IOException e) {
                LOG.warn("Failed to create snapshot on close", e);
            }
            try {
                if (engine != null) {
                    engine.close();
                    LOG.info("StorageEngine metrics on close: {}", engine.metrics());
                }
            } catch (IOException e) {
                LOG.warn("Failed to close StorageEngine", e);
            }
            try { wal.close(); } catch (IOException e) { LOG.warn("Failed to close WAL", e); }
            collections.clear();
            LOG.info("PersistentVectorStore closed");
        }
    }

    /**
     * 暴露 v2 引擎的只读指标（如需在外部监控，可读取）。
     */
    public String storageMetrics() {
        return engine == null ? "(StorageEngine disabled)" : engine.metrics();
    }

    /** v2 引擎是否启用。 */
    public boolean isStorageEngineEnabled() {
        return engine != null;
    }

    @Override
    public boolean isClosed() { return closed.get(); }

    // ==================== 内部 ====================

    private void maybeCheckpoint() {
        if (walRecordsSinceCheckpoint >= checkpointInterval) {
            try {
                createSnapshot();
            } catch (IOException e) {
                LOG.warn("Checkpoint failed: {}", e.getMessage());
            }
        }
    }

    private void createSnapshot() throws IOException {
        // 1. 先把每个 HNSW 集合的图保存到磁盘（崩溃后下次启动可直接接管）
        for (Collection coll : collections.values()) {
            if (coll.getIndexType() == IndexType.HNSW) {
                saveHnswToDisk(coll);
            }
        }

        // 2. 写 v2 page-based snapshot（points 分页入 PageStore，schema 入 psnap meta）
        List<HybridSnapshot.CollectionSnapshot> data = new ArrayList<>();
        for (Collection coll : collections.values()) {
            data.add(new HybridSnapshot.CollectionSnapshot(
                    coll.getName(), coll.getDimension(), coll.getMetric(),
                    coll.getIndexType(), new HashMap<>(coll.getSchema().getConfig()),
                    coll.getIndex().entries()));
        }
        snapshot.writeV2(data);

        // 3. 关键：先 flush 所有 pending WAL，再追加 CHECKPOINT 记录，最后截断
        if (engine != null) {
            engine.flushWal();   // 阻塞直到所有 pending 都落盘
        }
        wal.append(WalRecord.checkpoint(wal.getSequenceNumber()));
        wal.truncate();
        walRecordsSinceCheckpoint = 0;
        LOG.info("Checkpoint complete ({} collections via v2 page-based snapshot)",
                data.size());
    }

    /**
     * 统一的 WAL append 入口 — 启用引擎时走 AsyncWal（Group Commit），否则同步写。
     * <p>
     * 这样既保持 API 兼容，又能让现有流程默认获得异步写入性能。
     */
    private void walAppend(WalRecord rec) throws IOException {
        if (engine != null) {
            engine.appendWal(rec);
        } else {
            wal.append(rec);
        }
    }

    /**
     * 把指定 collection 的 HNSW 图保存到 {@code hnsw_<name>.bin}。
     * <p>
     * 仅在索引已 build 且未 dirty 时才落盘（dirty 状态代表有未刷盘的修改，下次启动会走 WAL 重放 + rebuild）。
     */
    private void saveHnswToDisk(Collection coll) {
        try {
            Index idx = coll.getIndex();
            if (!(idx instanceof HnswIndex)) {
                LOG.debug("Index for '{}' is not HnswIndex (type={}), skip HNSW save",
                        coll.getName(), idx.getClass().getSimpleName());
                return;
            }
            if (!idx.isBuilt()) {
                LOG.debug("Index for '{}' is not built, skip HNSW save", coll.getName());
                return;
            }
            if (coll.isIndexDirty()) {
                LOG.debug("Index for '{}' is dirty, skip HNSW save (will rebuild on next start)",
                        coll.getName());
                return;
            }
            HnswPersistence.save((HnswIndex) idx, hnswFile(coll.getName()).toString());
        } catch (Exception e) {
            LOG.warn("Failed to save HNSW for '{}': {}", coll.getName(), e.getMessage());
        }
    }

    /** HNSW 磁盘路径：{@code <dataDir>/hnsw_<collectionName>.bin} */
    private Path hnswFile(String collectionName) {
        return Paths.get(dataDir, "hnsw_" + collectionName + ".bin");
    }

    private Collection requireCollection(String name) {
        ensureNotClosed();
        Collection coll = collections.get(name);
        if (coll == null) throw new VectorException("Collection not found: " + name);
        return coll;
    }

    private void ensureNotClosed() {
        if (closed.get()) throw new VectorException("VectorStore is closed");
    }

    private Map<String, Object> parseJson(String json) throws IOException {
        return this.json.readValue(json, new TypeReference<Map<String, Object>>() {});
    }

    private VectorPoint parsePoint(String payload) throws IOException {
        Map<String, Object> m = parseJson(payload);
        String id = (String) m.get("id");
        List<Number> vec = (List<Number>) m.get("vector");
        float[] v = new float[vec.size()];
        for (int i = 0; i < vec.size(); i++) v[i] = vec.get(i).floatValue();
        @SuppressWarnings("unchecked")
        Map<String, Object> pl = (Map<String, Object>) m.getOrDefault("payload", new HashMap<>());
        return new VectorPoint(id, v, pl);
    }
}