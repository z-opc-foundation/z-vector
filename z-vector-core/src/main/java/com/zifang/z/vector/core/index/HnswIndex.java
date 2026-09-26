package com.zifang.z.vector.core.index;

import com.zifang.z.vector.api.Filter;
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

    /**
     * 现存节点数。
     * <p>
     * <b>不能</b>写成 {@code nodes.size() - tombstones.size()}：{@link #remove(String)} 已经把
     * 节点从 {@code nodes} 里物理删掉、只是另外记一条 tombstone 用来挡住"邻居表里残留的引用"，
     * 两个集合永不相交，相减等于每删一个点就少算两个 —— {@code Collection.count()} 会随删除
     * 成倍下坠，而 {@code Collection} 用它跟自己维护的 payload 倒排做同步校验，误判成"不同步"
     * 之后过滤搜索会整体退回慢路径。
     */
    @Override
    public int size() { return nodes.size(); }

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
                    n.id, n.vector, n.level, neighbors, n.payloadRef));
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
                // payload 必须跟着图一起回来：Collection 没有独立的点存储，索引就是数据本身。
                // 这里若塞空 map，重启后 get()/search() 的 payload 全空，payload 倒排也只能
                // 重建一张空表（过滤搜索永久退回放大候选的慢路径）。
                node.payloadRef = data.payload != null ? data.payload : new HashMap<>();
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
        // 对已存在的 id 再插入（= upsert）时沿用旧节点的层数：别的节点的高层邻居表已经引用了这个 id，
        // 换一个新随机层就把图弄坏了 —— 新节点若是 level 0，所有 "layer≥1 邻居表 → 该 id" 的引用
        // 会指向一个 neighbors 数组长度只有 1 的节点，遍历到它就抛 AIOOBE。
        Node previous = nodes.get(id);
        int level = previous != null ? previous.level : randomLevel();
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
            // 第 0 层是搜索真正落地的地方，标准 HNSW 给它 2M 的度上限（M_max0 = 2M）；
            // 只留 M 条边会让 layer 0 的可达性随数据量变差（实测同一套参数 recall
            // 从 n=5000 的 0.816 掉到 n=20000 的 0.58）。正反两个方向都按这个上限走，
            // 只放宽反向修剪会让新节点在 layer 0 依然只连 M 条边。
            int maxNb = (l == 0) ? 2 * M : M;
            // 选择 maxNb 个最近的节点作为邻居
            List<SearchResult> neighbors = selectNeighbors(candidates, maxNb);
            connect(node, neighbors, l);
            // 反向：把新节点加入候选邻居的邻居列表
            for (SearchResult nb : neighbors) {
                Node nbNode = nodes.get(nb.getVectorId());
                if (nbNode != null && nbNode.neighbors[l] != null) {
                    nbNode.neighbors[l].add(new SearchResult(id, nb.getScore()));
                    // 修剪邻居列表到该层的上限
                    if (nbNode.neighbors[l].size() > maxNb) {
                        pruneNeighbors(nbNode, l, maxNb);
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
            //
            // searchLayer 内部用 SearchResult 只当作 (id, score) 的候选载体，payload 恒为空。
            // 这里必须从 Node.payloadRef 把真实 payload 取回来：
            //   - matchesFilter 读的就是 r.getPayload()，不挂回去则任何带 filterPayload 的
            //     查询在 HNSW 上恒为 0 命中（FlatIndex 却正常，两条路径语义不一致）；
            //   - 上层 Collection.search 也是先拿 raw 结果再 filter.evaluate(r.getPayload())。
            // 只在最终 topK 上构造带 payload 的对象，避免为 ef 个候选各复制一份 map。
            List<SearchResult> scored = new ArrayList<>(efCandidates.length);
            for (SearchResult r : efCandidates) {
                if (tombstones.contains(r.getVectorId())) continue;
                if (r.getScore() > maxDistance) continue;
                Node n = nodes.get(r.getVectorId());
                if (n == null) continue;
                Map<String, Object> payload = n.payloadRef;
                if (filterPayload != null && !matchesFilter(payload, filterPayload)) continue;
                scored.add(new SearchResult(n.id, r.getScore(), payload));
            }
            scored.sort(Comparator.comparingDouble(SearchResult::getScore));
            if (scored.size() > topK) {
                scored = new ArrayList<>(scored.subList(0, topK));
            }
            return scored;
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
            // 只判 null 挡不住越界：Node.neighbors 的长度恰好是 level+1，所以"被某层邻居表引用、
            // 自身层数却更低"的节点在这里直接抛 AIOOBE（remove() 不清理反向引用、持久化恢复也
            // 不校验引用双方的层数）。层数不够 ⇒ 这一层没有它的份，跳过扩展即可。
            if (currNode == null || level >= currNode.neighbors.length
                    || currNode.neighbors[level] == null) continue;

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

    /** 与 FlatIndex.matchesFilter 同语义：缺失的 key 视为 null，不匹配任何非 null 值 */
    private static boolean matchesFilter(Map<String, Object> payload, Map<String, Object> filter) {
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            Object actual = payload == null ? null : payload.get(entry.getKey());
            if (!Filter.valuesMatch(actual, entry.getValue())) {
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