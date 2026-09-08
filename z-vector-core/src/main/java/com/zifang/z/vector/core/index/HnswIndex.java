package com.zifang.z.vector.core.index;

import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.core.distance.Distance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * HNSW（Hierarchical Navigable Small World）— 分层可导航小世界图索引。
 * <p>
 * 算法参考: Malkov & Yashunin 2016 论文 "Efficient and robust approximate nearest
 * neighbor search using Hierarchical Navigable Small World graphs".
 * <p>
 * 设计参考: zvec 的 HNSW 实现 + Faiss 的 HNSW 实现 + Qdrant 的 HNSW 实现.
 *
 * <h2>核心思想</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────┐
 * │  层级图结构:                                                │
 * │  Level 2: ● ──── ●                                         │
 * │            │                                                │
 * │  Level 1: ● ──── ● ──── ●                                  │
 * │            │      │      │                                 │
 * │  Level 0: ●─●─●─●─●─●─●─●─●─●─●─●  (全量数据 + 长程边)         │
 * └─────────────────────────────────────────────────────────┘
 * 搜索: 从顶层入口点出发 → 贪心下降到 Layer 0 → 在 Layer 0 内做 k-NN
 * </pre>
 *
 * <h2>关键参数</h2>
 * <ul>
 *   <li>M: 每节点最大邻居数（默认 16）</li>
 *   <li>efConstruction: 构建时的候选队列大小（默认 200）</li>
 *   <li>efSearch: 搜索时的候选队列大小（默认 50，可动态调）</li>
 *   <li>ml: 层级缩放因子 mL = 1/ln(M)（默认 0.25）</li>
 * </ul>
 *
 * <h2>复杂度</h2>
 * <ul>
 *   <li>插入: O(log N * M * d)</li>
 *   <li>搜索: O(log N * ef * d)</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * 使用 ReentrantReadWriteLock：构建期间写锁保护，搜索期间读锁允许并发。
 */
public class HnswIndex implements Index {

    private static final Logger LOG = LoggerFactory.getLogger(HnswIndex.class);

    /** 默认参数 */
    public static final int DEFAULT_M = 16;
    public static final int DEFAULT_EF_CONSTRUCTION = 200;
    public static final int DEFAULT_EF_SEARCH = 50;
    public static final int MAX_LEVEL = 16;

    private final Distance distance;
    private final int dimension;
    private final int M;
    private final int efConstruction;
    private volatile int efSearch;

    /** 节点存储: id → 节点 */
    private final Map<String, Node> nodes = new ConcurrentHashMap<>();
    /** 逻辑删除的 id */
    private final Set<String> tombstones = ConcurrentHashMap.newKeySet();

    /** 入口点 id（最高层的某个节点） */
    private volatile String entryPoint;
    /** 当前最大层级 */
    private volatile int maxLevel;
    /** 层级概率生成器 */
    private final Random random = new Random();
    /** 层级归一化因子 mL = 1/ln(M) */
    private final double mL;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public HnswIndex(Distance distance, int dimension) {
        this(distance, dimension, DEFAULT_M, DEFAULT_EF_CONSTRUCTION, DEFAULT_EF_SEARCH);
    }

    public HnswIndex(Distance distance, int dimension, int M, int efConstruction, int efSearch) {
        this.distance = Objects.requireNonNull(distance, "distance");
        if (dimension <= 0) throw new IllegalArgumentException("dimension must be > 0");
        this.dimension = dimension;
        if (M <= 0) throw new IllegalArgumentException("M must be > 0");
        this.M = M;
        if (efConstruction <= 0) throw new IllegalArgumentException("efConstruction must be > 0");
        this.efConstruction = efConstruction;
        if (efSearch <= 0) throw new IllegalArgumentException("efSearch must be > 0");
        this.efSearch = efSearch;
        this.mL = 1.0 / Math.log(M > 1 ? M : 2);
        this.maxLevel = -1;
    }

    @Override
    public String type() { return "HNSW"; }

    @Override
    public int dimension() { return dimension; }

    @Override
    public int size() { return nodes.size() - tombstones.size(); }

    @Override
    public boolean isBuilt() { return entryPoint != null; }

    public int getEfSearch() { return efSearch; }
    public void setEfSearch(int efSearch) {
        if (efSearch > 0) this.efSearch = efSearch;
    }

    /** 暴露内部参数（持久化用） */
    public int getM() { return M; }
    public int getEfConstruction() { return efConstruction; }
    public int getMaxLevel() { return maxLevel; }
    public String getEntryPoint() { return entryPoint; }

    /**
     * 导出所有节点为可序列化结构（持久化用）
     */
    public List<com.zifang.z.vector.core.index.HnswPersistence.HnswNodeData> exportNodes() {
        List<com.zifang.z.vector.core.index.HnswPersistence.HnswNodeData> result =
                new ArrayList<>(nodes.size());
        for (Map.Entry<String, Node> e : nodes.entrySet()) {
            if (tombstones.contains(e.getKey())) continue;
            Node n = e.getValue();
            Map<Integer, List<String>> neighbors = new HashMap<>();
            for (int lvl = 0; lvl <= n.level; lvl++) {
                List<String> list = new ArrayList<>();
                for (SearchResult nb : n.neighbors[lvl]) {
                    list.add(nb.getVectorId());
                }
                neighbors.put(lvl, list);
            }
            result.add(new com.zifang.z.vector.core.index.HnswPersistence.HnswNodeData(
                    n.id, n.vector, n.level, neighbors));
        }
        return result;
    }

    /**
     * 从序列化的节点数据导入（持久化恢复用）
     */
    public void importNodes(Map<String, com.zifang.z.vector.core.index.HnswPersistence.HnswNodeData> nodeMap,
                             String entryPointId) {
        lock.writeLock().lock();
        try {
            nodes.clear();
            tombstones.clear();
            int maxL = -1;
            for (Map.Entry<String, com.zifang.z.vector.core.index.HnswPersistence.HnswNodeData> e
                    : nodeMap.entrySet()) {
                com.zifang.z.vector.core.index.HnswPersistence.HnswNodeData data = e.getValue();
                @SuppressWarnings("unchecked")
                List<SearchResult>[] neighbors = new List[data.level + 1];
                for (int lvl = 0; lvl <= data.level; lvl++) {
                    List<String> ids = data.neighbors.get(lvl);
                    List<SearchResult> list = new ArrayList<>(ids.size());
                    for (String nbId : ids) {
                        com.zifang.z.vector.core.index.HnswPersistence.HnswNodeData nbData
                                = nodeMap.get(nbId);
                        float nbDist = nbData != null
                                ? distance.compute(data.vector, nbData.vector)
                                : 0f;
                        list.add(new SearchResult(nbId, nbDist));
                    }
                    neighbors[lvl] = list;
                }
                Node node = new Node(data.id, data.vector, data.level);
                node.payloadRef = new HashMap<>();
                for (int lvl = 0; lvl <= data.level; lvl++) {
                    node.neighbors[lvl] = neighbors[lvl];
                }
                nodes.put(e.getKey(), node);
                if (data.level > maxL) maxL = data.level;
            }
            this.maxLevel = maxL;
            this.entryPoint = entryPointId;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @SuppressWarnings("unused")
    private String indexType() {
        return "HNSW";
    }

    @Override
    public void build(List<VectorPoint> buildPoints) {
        lock.writeLock().lock();
        try {
            clear();
            for (VectorPoint p : buildPoints) {
                validate(p);
                insertInternal(p);
            }
            LOG.info("HNSW built: {} points, maxLevel={}, entryPoint={}",
                    nodes.size(), maxLevel, entryPoint);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void add(VectorPoint point) {
        validate(point);
        lock.writeLock().lock();
        try {
            insertInternal(point);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 插入内部逻辑（无锁） */
    private void insertInternal(VectorPoint point) {
        String id = point.getId();
        int level = randomLevel();
        Node node = new Node(id, point.getVector(), level);
        node.payloadRef = point.getPayload();
        nodes.put(id, node);
        tombstones.remove(id);

        if (entryPoint == null) {
            // 第一个点
            entryPoint = id;
            maxLevel = level;
            return;
        }

        // Phase 1: 贪心下降到与插入节点同层（或 Layer 0）
        String ep = entryPoint;
        float[] query = point.getVector();
        float distEp = distance.compute(query, nodes.get(ep).vector);

        for (int l = maxLevel; l > level; l--) {
            // 在每层做贪心搜索
            SearchResult[] greedy = searchLayer(query, new String[]{ep}, 1, l);
            ep = greedy[0].getVectorId();
            distEp = greedy[0].getScore();
        }

        // Phase 2: 在 [level, 0] 每层构建连接
        String[] epSet = new String[]{ep};
        for (int l = Math.min(level, maxLevel); l >= 0; l--) {
            SearchResult[] candidates = searchLayer(query, epSet, efConstruction, l);
            // 选择 M 个最近的节点作为邻居
            List<SearchResult> neighbors = selectNeighbors(candidates, M);
            connect(node, neighbors, l);
            // 反向：把新节点加入候选邻居的邻居列表
            for (SearchResult nb : neighbors) {
                Node nbNode = nodes.get(nb.getVectorId());
                if (nbNode != null && nbNode.neighbors[l] != null) {
                    nbNode.neighbors[l].add(new SearchResult(id, nb.getScore()));
                    // 修剪邻居列表到 M 个
                    if (nbNode.neighbors[l].size() > M) {
                        pruneNeighbors(nbNode, l, M);
                    }
                }
            }
            epSet = new String[neighbors.size()];
            for (int i = 0; i < neighbors.size(); i++) {
                epSet[i] = neighbors.get(i).getVectorId();
            }
        }

        // 如果新节点层级 > 当前最大层级，更新入口点
        if (level > maxLevel) {
            maxLevel = level;
            entryPoint = id;
        }
    }

    @Override
    public boolean remove(String id) {
        lock.writeLock().lock();
        try {
            Node removed = nodes.remove(id);
            if (removed == null) return false;
            tombstones.add(id);
            // 如果删除的是入口点，需要找一个新入口点
            if (id.equals(entryPoint)) {
                entryPoint = null;
                for (String nid : nodes.keySet()) {
                    Node n = nodes.get(nid);
                    if (n != null && !tombstones.contains(nid)) {
                        if (entryPoint == null || n.level > nodes.get(entryPoint).level) {
                            entryPoint = nid;
                            maxLevel = n.level;
                        }
                    }
                }
                if (entryPoint == null) {
                    maxLevel = -1;
                }
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void clear() {
        lock.writeLock().lock();
        try {
            nodes.clear();
            tombstones.clear();
            entryPoint = null;
            maxLevel = -1;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public VectorPoint get(String id) {
        Node n = nodes.get(id);
        if (n == null || tombstones.contains(id)) return null;
        return new VectorPoint(n.id, n.vector, n.payloadRef != null ? n.payloadRef : new java.util.HashMap<>());
    }

    @Override
    public List<VectorPoint> entries() {
        List<VectorPoint> all = new ArrayList<>(nodes.size());
        for (Map.Entry<String, Node> e : nodes.entrySet()) {
            if (!tombstones.contains(e.getKey())) {
                Node n = e.getValue();
                all.add(new VectorPoint(n.id, n.vector,
                        n.payloadRef != null ? n.payloadRef : new java.util.HashMap<>()));
            }
        }
        return all;
    }

    @Override
    public List<SearchResult> search(float[] query, int topK,
                                     Map<String, Object> filterPayload,
                                     float maxDistance) {
        validateQuery(query);
        if (topK <= 0 || entryPoint == null) return Collections.emptyList();

        lock.readLock().lock();
        try {
            String ep = entryPoint;
            // Phase 1: 从顶层向下贪心
            for (int l = maxLevel; l > 0; l--) {
                SearchResult[] greedy = searchLayer(query, new String[]{ep}, 1, l);
                ep = greedy[0].getVectorId();
            }
            // Phase 2: 在 Layer 0 做 efSearch 范围的 k-ANN
            SearchResult[] efCandidates = searchLayer(query, new String[]{ep},
                    Math.max(efSearch, topK), 0);

            // 过滤 + 排序 + topK
            List<SearchResult> filtered = new ArrayList<>(efCandidates.length);
            for (SearchResult r : efCandidates) {
                if (tombstones.contains(r.getVectorId())) continue;
                if (r.getScore() > maxDistance) continue;
                if (filterPayload != null && !matchesFilter(r, filterPayload)) continue;
                filtered.add(r);
            }
            filtered.sort(Comparator.comparingDouble(SearchResult::getScore));
            if (filtered.size() > topK) {
                filtered = filtered.subList(0, topK);
            }
            return filtered;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<SearchResult> searchRange(float[] query, float maxDistance, int topK,
                                          Map<String, Object> filterPayload) {
        return search(query, topK, filterPayload, maxDistance);
    }

    // ==================== HNSW 核心算法 ====================

    /**
     * 在指定层做 ef-ANN 搜索 — 返回 ef 个最近邻候选。
     */
    private SearchResult[] searchLayer(float[] query, String[] entryPoints, int ef, int level) {
        PriorityQueue<SearchResult> candidates = new PriorityQueue<>(
                Comparator.comparingDouble(SearchResult::getScore)); // 最小堆
        PriorityQueue<SearchResult> results = new PriorityQueue<>(
                Comparator.comparingDouble((SearchResult r) -> -r.getScore())); // 最大堆（保留最差）
        Set<String> visited = new HashSet<>();

        for (String ep : entryPoints) {
            Node n = nodes.get(ep);
            if (n == null) continue;
            float dist = distance.compute(query, n.vector);
            candidates.add(new SearchResult(ep, dist));
            results.add(new SearchResult(ep, dist));
            visited.add(ep);
        }

        while (!candidates.isEmpty()) {
            SearchResult curr = candidates.poll();
            SearchResult farthestInResults = results.peek();
            if (curr.getScore() > farthestInResults.getScore()) {
                break; // 当前最近候选比结果中最远的还远，停止
            }
            Node currNode = nodes.get(curr.getVectorId());
            if (currNode == null || currNode.neighbors[level] == null) continue;

            for (SearchResult neighbor : currNode.neighbors[level]) {
                if (visited.contains(neighbor.getVectorId())) continue;
                visited.add(neighbor.getVectorId());
                Node nbNode = nodes.get(neighbor.getVectorId());
                if (nbNode == null) continue;

                float dist = distance.compute(query, nbNode.vector);
                farthestInResults = results.peek();
                if (results.size() < ef || dist < farthestInResults.getScore()) {
                    candidates.add(new SearchResult(neighbor.getVectorId(), dist));
                    results.add(new SearchResult(neighbor.getVectorId(), dist));
                    if (results.size() > ef) {
                        results.poll();
                    }
                }
            }
        }

        // 转成数组并按距离排序
        SearchResult[] arr = results.toArray(new SearchResult[0]);
        Arrays.sort(arr, Comparator.comparingDouble(SearchResult::getScore));
        return arr;
    }

    /** 邻居选择（Heuristic 简化版：按距离排序取 M 个） */
    private List<SearchResult> selectNeighbors(SearchResult[] candidates, int M) {
        int n = Math.min(M, candidates.length);
        List<SearchResult> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            result.add(candidates[i]);
        }
        return result;
    }

    /** 建立新节点与候选的连接 */
    private void connect(Node node, List<SearchResult> neighbors, int level) {
        if (node.neighbors[level] == null) {
            node.neighbors[level] = new ArrayList<>();
        }
        node.neighbors[level].addAll(neighbors);
    }

    /** 修剪邻居到 M 个 */
    private void pruneNeighbors(Node node, int level, int M) {
        List<SearchResult> list = node.neighbors[level];
        if (list == null || list.size() <= M) return;
        list.sort(Comparator.comparingDouble(SearchResult::getScore));
        node.neighbors[level] = new ArrayList<>(list.subList(0, M));
    }

    /** 随机生成层级 — 指数衰减分布 */
    private int randomLevel() {
        double r = -Math.log(random.nextDouble()) * mL;
        return (int) Math.min(r, MAX_LEVEL);
    }

    private boolean matchesFilter(SearchResult r, Map<String, Object> filter) {
        Map<String, Object> payload = r.getPayload();
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            if (!Objects.equals(payload.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private void validate(VectorPoint p) {
        if (p.getDimension() != dimension) {
            throw new IllegalArgumentException(
                    "Dimension mismatch: expected " + dimension + ", got " + p.getDimension());
        }
    }

    private void validateQuery(float[] query) {
        if (query.length != dimension) {
            throw new IllegalArgumentException(
                    "Query dimension mismatch: expected " + dimension + ", got " + query.length);
        }
    }

    // ==================== 内部数据结构 ====================

    private static class Node {
        final String id;
        final float[] vector;
        final int level;
        Map<String, Object> payloadRef;  // 引用 VectorPoint 的 payload（节省内存）
        @SuppressWarnings("unchecked")
        final List<SearchResult>[] neighbors;

        @SuppressWarnings("unchecked")
        Node(String id, float[] vector, int level) {
            this.id = id;
            this.vector = vector;
            this.level = level;
            this.neighbors = new List[level + 1];
            for (int i = 0; i <= level; i++) {
                this.neighbors[i] = new ArrayList<>();
            }
        }
    }
}