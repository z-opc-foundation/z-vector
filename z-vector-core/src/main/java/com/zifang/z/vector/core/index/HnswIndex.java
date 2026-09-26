package com.zifang.z.vector.core.index;

import com.zifang.z.vector.api.Filter;
import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.core.distance.Distance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
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
    /** 层级随机数的默认种子：让同一份语料的两次 build 得到同一张图。 */
    public static final long DEFAULT_LEVEL_SEED = 0x5EEDL;

    /**
     * 结果列表按距离升序。beam 内部已经排好序（{@link SearchScratch#sortResultsAscending()}），
     * 这里只剩"过滤可能跳过几条"这一种情况需要再排一次；写成常量而不是
     * {@code Comparator.comparingDouble(...)} 就地新建，是为了不再往查询路径里塞 lambda 实例。
     */
    private static final Comparator<SearchResult> NEAREST_FIRST =
            Comparator.comparingDouble(SearchResult::getScore);

    private final Distance distance;
    private final int dimension;
    private final int M;
    private final int efConstruction;
    private volatile int efSearch;

    /** 节点存储: id → 节点 */
    private final Map<String, Node> nodes = new ConcurrentHashMap<>();
    /**
     * 逻辑删除的 id → 它<b>生前的层级</b>。
     * <p>
     * 层级必须跟着墓碑一起记住：{@link #remove} 不会去擦别人邻居表里指向本 id 的边（代价是
     * O(n·M)，没有向量库这么做），所以"第 l 层还有一条边指过来"这件事在删除之后依然成立。
     * 重新写入同一个 id 时若让它随机到更低的层，那条边就指向了一个 {@code neighbors.length}
     * 更小的节点。实测：删掉一个 layer≥1 的点再写回来，之后继续插入，40 个种子里 36 个在
     * {@link #insertInternal} 的反向连接上抛 AIOOBE —— 单线程、无竞态，纯"删了再插"就能踩到。
     */
    private final Map<String, Integer> tombstones = new ConcurrentHashMap<>();

    /**
     * 节点槽位分配器 —— 只为查询期那份"已访问位图"服务。
     * <p>
     * 位图按槽位寻址，所以槽位必须密集：删掉的节点把槽位退回 {@code freeSlots} 复用，
     * upsert 沿用旧槽位，于是位图大小跟着"活着的节点数"走而不是写入次数。
     * <p>
     * 只在写锁里改（{@code insertInternal}/{@code remove}/{@code importNodes}/{@code clear}），
     * 读侧全程持读锁，因此"复用槽位"与"正在进行的搜索"不会同时发生。
     */
    private int slotCounter;
    private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
    /** 每线程一份查询工作区（随本索引一起回收，不做成 static）。 */
    private final ThreadLocal<SearchScratch> scratches = new ThreadLocal<SearchScratch>() {
        @Override protected SearchScratch initialValue() { return new SearchScratch(); }
    };

    /**
     * 查询期的工作区 —— 位图、两个堆、距离数组全在这里，线程私有、跨查询复用。
     * <p>
     * 旧写法每次 {@link #searchLayer} 新建一个 {@code HashSet<String>} 加两个
     * {@code PriorityQueue<SearchResult>}，beam 每收一个候选还要再 new 一个 SearchResult：
     * ef=64、n=5000/dim=128 的一次查询光这些就 48 KB（实测 239 B/次距离计算）。现在堆里装
     * 的是<b>本次搜索内的序号</b>，序号经 {@link #nodeAt} 指回节点、经 {@link #distAt} 取距离，
     * 位图按节点槽位判已访问 —— 这条路径上零分配。
     * <p>
     * 每线程的内存成本：位图 n/8 字节，其余数组只跟"单次搜索访问过的节点数"同量级
     * （≈ ef·M），不随语料大小增长。
     */
    private static final class SearchScratch {
        /** slot → 本层已访问位。{@link #endLayer()} 逐位清掉，只有置过位的槽位需要清。 */
        private long[] bits = new long[8];
        private int[] touched = new int[256];
        private int touchedSize;
        /** 序号 → 节点 / 到查询的距离。两个堆里放的都是序号。 */
        private Node[] nodeAt = new Node[256];
        private float[] distAt = new float[256];
        private int size;
        /** 待扩展最小堆（近的先出）/ 结果最大堆（远的先出，容量 ef）。 */
        private int[] near = new int[64];
        private int nearSize;
        private int[] far = new int[64];
        private int farSize;

        void beginLayer() {
            size = 0;
            nearSize = 0;
            farSize = 0;
            touchedSize = 0;
        }

        /** 只清本次置过的位：代价随"访问过的节点数"，不随全库大小。 */
        void endLayer() {
            for (int i = 0; i < touchedSize; i++) {
                int slot = touched[i];
                bits[slot >>> 6] &= ~(1L << slot);
            }
            touchedSize = 0;
        }

        /** 第一次访问该槽位返回 true。 */
        boolean visit(int slot) {
            int word = slot >>> 6;
            long mask = 1L << slot;
            if (word >= bits.length) {
                bits = Arrays.copyOf(bits, Math.max(word + 1, bits.length << 1));
            }
            if ((bits[word] & mask) != 0L) return false;
            bits[word] |= mask;
            if (touchedSize == touched.length) {
                touched = Arrays.copyOf(touched, touched.length << 1);
            }
            touched[touchedSize++] = slot;
            return true;
        }

        /** 记下 (node, dist)，返回它本次搜索内的序号。同一节点每次搜索只记一次（位图已挡）。 */
        int remember(Node node, float dist) {
            if (size == nodeAt.length) {
                int cap = nodeAt.length << 1;
                nodeAt = Arrays.copyOf(nodeAt, cap);
                distAt = Arrays.copyOf(distAt, cap);
            }
            int ord = size++;
            nodeAt[ord] = node;
            distAt[ord] = dist;
            return ord;
        }

        float farthest() { return distAt[far[0]]; }

        void pushNear(int ord) {
            if (nearSize == near.length) near = Arrays.copyOf(near, near.length << 1);
            float d = distAt[ord];
            int i = nearSize++;
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (distAt[near[parent]] <= d) break;
                near[i] = near[parent];
                i = parent;
            }
            near[i] = ord;
        }

        int popNear() {
            int top = near[0];
            int moved = near[--nearSize];
            if (nearSize > 0) siftDownNear(0, nearSize, moved);
            return top;
        }

        private void siftDownNear(int start, int limit, int x) {
            float d = distAt[x];
            int i = start;
            for (;;) {
                int left = (i << 1) + 1;
                if (left >= limit) break;
                int right = left + 1;
                int child = (right < limit && distAt[near[right]] < distAt[near[left]]) ? right : left;
                if (distAt[near[child]] >= d) break;
                near[i] = near[child];
                i = child;
            }
            near[i] = x;
        }

        void pushFar(int ord) {
            if (farSize == far.length) far = Arrays.copyOf(far, far.length << 1);
            float d = distAt[ord];
            int i = farSize++;
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (distAt[far[parent]] >= d) break;
                far[i] = far[parent];
                i = parent;
            }
            far[i] = ord;
        }

        int popFar() {
            int top = far[0];
            int moved = far[--farSize];
            if (farSize > 0) siftDownFar(0, farSize, moved);
            return top;
        }

        private void siftDownFar(int start, int limit, int x) {
            float d = distAt[x];
            int i = start;
            for (;;) {
                int left = (i << 1) + 1;
                if (left >= limit) break;
                int right = left + 1;
                int child = (right < limit && distAt[far[right]] > distAt[far[left]]) ? right : left;
                if (distAt[far[child]] <= d) break;
                far[i] = far[child];
                i = child;
            }
            far[i] = x;
        }

        /**
         * 把结果堆原地排成"由近到远"，返回条数；调用方随后按 {@code far[0..n)} 取序号。
         * 堆排序每次把堆顶（最远）换到未排序区间的末尾，倒着装完就是升序。
         */
        int sortResultsAscending() {
            int n = farSize;
            for (int end = n - 1; end > 0; end--) {
                int tmp = far[0];
                far[0] = far[end];
                far[end] = tmp;
                siftDownFar(0, end, far[0]);
            }
            return n;
        }
    }

    /** 取一个槽位：优先复用被删除节点腾出来的，否则往前推分配器。调用方持写锁。 */
    private int nextSlot() {
        Integer recycled = freeSlots.pollFirst();
        if (recycled != null) return recycled;
        return slotCounter++;
    }

    /**
     * 槽位分配器归零（{@link #clear()} 与 {@link #importNodes} 用），节点从 0 号槽位重新发号。
     * 调用方持写锁。世代戳不用清：{@code gen} 只单调往前走，重发出来的槽位里残留的是<b>更旧</b>
     * 世代的号，永远不会等于当前世代。
     */
    private void resetSlots() {
        slotCounter = 0;
        freeSlots.clear();
    }

    /** 入口点 id（最高层的某个节点） */
    private volatile String entryPoint;
    /** 当前最大层级 */
    private volatile int maxLevel;
    /** 层级概率生成器 */
    private final Random random;
    /** 层级归一化因子 mL = 1/ln(M) */
    private final double mL;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public HnswIndex(Distance distance, int dimension) {
        this(distance, dimension, DEFAULT_M, DEFAULT_EF_CONSTRUCTION, DEFAULT_EF_SEARCH);
    }

    public HnswIndex(Distance distance, int dimension, int M, int efConstruction, int efSearch) {
        this(distance, dimension, M, efConstruction, efSearch, DEFAULT_LEVEL_SEED);
    }

    /**
     * @param levelSeed 层级随机数的种子。同一个语料两次 {@code build} 会得到同一张图，
     *                  这样"召回率"这类门禁才能钉死数字（不播种时同一份数据重跑会有几个点的抖动，
     *                  红/绿就变成掷硬币）。要每次构建都换形状就自己传一个变动的值。
     */
    public HnswIndex(Distance distance, int dimension, int M, int efConstruction, int efSearch,
                     long levelSeed) {
        this.distance = Objects.requireNonNull(distance, "distance");
        if (dimension <= 0) throw new IllegalArgumentException("dimension must be > 0");
        this.dimension = dimension;
        if (M <= 0) throw new IllegalArgumentException("M must be > 0");
        this.M = M;
        if (efConstruction <= 0) throw new IllegalArgumentException("efConstruction must be > 0");
        this.efConstruction = efConstruction;
        if (efSearch <= 0) throw new IllegalArgumentException("efSearch must be > 0");
        this.efSearch = efSearch;
        this.random = new Random(levelSeed);
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
            if (tombstones.containsKey(e.getKey())) continue;
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
            resetSlots();
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
                        // 这里不校验"被指向的节点活不落得到 lvl 层"，也不顺手丢掉这类边：
                        // 快照里出现这种形状时，被引用的节点在这一层依然是合法候选，整条丢掉会
                        // 连带丢结果（HnswGraphShapeTest.highLayerReferenceToLowLayerNodeKeepsExactTopK
                        // 钉的就是这件事）。守卫放在使用点：卡 neighbors.length，不卡图的入口。
                        float nbDist = nbData != null
                                ? distance.compute(data.vector, nbData.vector)
                                : 0f;
                        list.add(new SearchResult(nbId, nbDist));
                    }
                    neighbors[lvl] = list;
                }
                Node node = new Node(data.id, data.vector, data.level, nextSlot());
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
        // 墓碑里的层数要一并算进来：remove() 不擦别人指向该 id 的高层边，所以"删掉再写回来"的
        // 节点同样不许往回退层，否则那些边当场变成悬空引用（实测 40 个种子里 36 个随后插入时抛
        // AIOOBE，见 HnswLevelInvariantTest）。没有墓碑时 remembered 为 null，这里与改动前逐字
        // 相同、随机数抽取次序也不变 ⇒ 同一份语料 build 出的图不变。
        Integer remembered = tombstones.remove(id);
        int level;
        if (previous != null) {
            level = previous.level;
        } else {
            int drawn = randomLevel();
            level = remembered != null && remembered > drawn ? remembered.intValue() : drawn;
        }
        // upsert 沿用旧槽位：每重写一次就换一个号的话，槽位号会随写入次数无限增长，
        // 位图也跟着长，而实际活着的节点只有 nodes.size() 个。
        Node node = new Node(id, point.getVector(), level,
                previous != null ? previous.slot : nextSlot());
        node.payloadRef = point.getPayload();
        nodes.put(id, node);

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
            // 从 efConstruction 个候选里按启发式挑 maxNb 条（论文 Algorithm 4）：只按"离自己最近"
            // 取 maxNb 条会让跨簇的长程边在修剪中被同簇近邻挤光，图会碎成孤岛。
            List<SearchResult> neighbors = selectNeighbors(node, candidates, maxNb);
            connect(node, neighbors, l);
            // 反向：把新节点加入候选邻居的邻居列表
            for (SearchResult nb : neighbors) {
                Node nbNode = nodes.get(nb.getVectorId());
                // 先卡长度再卡 null：邻居表可能指着一个活不到这一层的节点（图被持久化过、
                // 或者来自别的版本写的快照），neighbors[l] 在这种情况下直接越界。
                if (nbNode == null || l >= nbNode.neighbors.length
                        || nbNode.neighbors[l] == null) continue;
                // 反向这条边的 id 是新节点、score 才沿用候选的距离，两个字段与正向那条
                // 正好错开，所以这里不能共享 SearchResult 实例（正向那条的 id 是邻居自己的）。
                nbNode.neighbors[l].add(new SearchResult(id, nb.getScore()));
                // 修剪邻居列表到该层的上限
                if (nbNode.neighbors[l].size() > maxNb) {
                    pruneNeighbors(nbNode, l, maxNb);
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
            // 槽位回收给后面的节点用，int[] 的大小才始终跟着"活着的节点数"而不是"曾经写过的节点数"。
            freeSlots.addLast(removed.slot);
            // 墓碑带上层数：别人邻居表里指向本 id 的边还在原位，重新写入时层数不许往回退
            // （见 tombstones 字段与 HnswLevelInvariantTest）。
            tombstones.put(id, removed.level);
            // 如果删除的是入口点，需要找一个新入口点
            if (id.equals(entryPoint)) {
                entryPoint = null;
                for (String nid : nodes.keySet()) {
                    Node n = nodes.get(nid);
                    if (n != null && !tombstones.containsKey(nid)) {
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
            resetSlots();
            entryPoint = null;
            maxLevel = -1;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public VectorPoint get(String id) {
        Node n = nodes.get(id);
        if (n == null || tombstones.containsKey(id)) return null;
        return new VectorPoint(n.id, n.vector, n.payloadRef != null ? n.payloadRef : new java.util.HashMap<>());
    }

    @Override
    public List<VectorPoint> entries() {
        List<VectorPoint> all = new ArrayList<>(nodes.size());
        for (Map.Entry<String, Node> e : nodes.entrySet()) {
            if (!tombstones.containsKey(e.getKey())) {
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
            SearchScratch sc = scratches.get();
            // Phase 1: 从顶层向下贪心
            // 复用同一个单元素数组当入口点集合：贪心下降每层都要调一次搜索，每层新建 String[]
            // 就是每查询多建 maxLevel+1 个数组，而内容只需要一个槽位。
            // 槽里始终是"目前下降到的那个点"，Phase 2 直接从它继续 —— 中途拿旧的 ep 覆写一次，
            // layer 0 就等于放弃了贪心下降（实测 distCalls/query 从 176.6 涨回 249.9）。
            String[] epScratch = new String[1];
            epScratch[0] = ep;
            for (int l = maxLevel; l > 0; l--) {
                int greedied = searchLayerSlots(query, epScratch, 1, l, sc);
                if (greedied == 0) return Collections.emptyList();
                epScratch[0] = sc.nodeAt[sc.far[0]].id;
            }
            // Phase 2: 在 Layer 0 做 efSearch 范围的 k-ANN
            int found = searchLayerSlots(query, epScratch, Math.max(efSearch, topK), 0, sc);

            // 过滤 + topK
            //
            // 候选只以序号形态存在，这里才第一次造 SearchResult，而且只造最终 topK 个：
            //   - searchLayerSlots 的产物已按距离升序，所以"够 topK 条就停"取到的就是最近的
            //     topK 条，不必先给 ef 个候选各造一个对象再排序截断；
            //   - payload 必须从 Node.payloadRef 取回真身：matchesFilter 读的就是它，不挂回去
            //     则任何带 filterPayload 的查询在 HNSW 上恒为 0 命中（FlatIndex 却正常）；
            //     上层 Collection.search 也是先拿 raw 结果再 filter.evaluate(r.getPayload())。
            List<SearchResult> scored = new ArrayList<>(topK);
            for (int i = 0; i < found && scored.size() < topK; i++) {
                int ord = sc.far[i];
                Node n = sc.nodeAt[ord];
                float dist = sc.distAt[ord];
                if (tombstones.containsKey(n.id)) continue;
                if (dist > maxDistance) continue;
                Map<String, Object> payload = n.payloadRef;
                if (filterPayload != null && !matchesFilter(payload, filterPayload)) continue;
                scored.add(new SearchResult(n.id, dist, payload));
            }
            scored.sort(NEAREST_FIRST);
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
     * 在指定层做 ef-ANN 搜索 —— 查询路径的正身。
     * <p>
     * 结果写在本线程的 {@code sc.far[0..n)} 里（按距离升序的<b>序号</b>，用 {@code sc.nodeAt} /
     * {@code sc.distAt} 还原成 (id, score)），返回条数。调用方按序号取就够了，一个候选对象都不必
     * 造 —— 造出来就是每查询 ef 个 {@code SearchResult}（ef=64 时约 2 KB，见
     * {@link SearchScratch}）。
     * <p>
     * 每层一个独立的已访问集合：{@link SearchScratch#beginLayer()} 起、
     * {@link SearchScratch#endLayer()} 收（异常路径也要收，漏一位就等于把这个节点对同线程
     * 之后的所有搜索永久标记成"已访问"）。
     */
    private int searchLayerSlots(float[] query, String[] entryPoints, int ef, int level,
                                 SearchScratch sc) {
        sc.beginLayer();
        try {
            for (String ep : entryPoints) {
                Node n = nodes.get(ep);
                if (n == null) continue;
                sc.visit(n.slot);
                int ord = sc.remember(n, distance.compute(query, n.vector));
                sc.pushNear(ord);
                sc.pushFar(ord);
            }

            while (sc.nearSize > 0) {
                int curr = sc.popNear();
                if (sc.distAt[curr] > sc.farthest()) {
                    break; // 当前最近候选比结果中最远的还远，停止
                }
                Node currNode = sc.nodeAt[curr];
                // neighbors 的长度恰好是 level+1，所以"被某层邻居表引用、自身层数却更低"的节点
                // 直接取 neighbors[level] 会抛 AIOOBE（remove() 不清理反向引用、持久化恢复也不
                // 校验引用双方的层数）。层数不够 ⇒ 这一层没有它的份，跳过扩展即可。
                if (level >= currNode.neighbors.length || currNode.neighbors[level] == null) {
                    continue;
                }

                for (SearchResult neighbor : currNode.neighbors[level]) {
                    Node nbNode = nodes.get(neighbor.getVectorId());
                    // 悬挂引用（邻居表指向早已消失的 id）：健康图里命中 0 次。
                    if (nbNode == null) continue;
                    if (!sc.visit(nbNode.slot)) continue;

                    float dist = distance.compute(query, nbNode.vector);
                    int ord = sc.remember(nbNode, dist);
                    if (sc.farSize < ef || dist < sc.farthest()) {
                        sc.pushNear(ord);
                        sc.pushFar(ord);
                        if (sc.farSize > ef) {
                            sc.popFar();
                        }
                    }
                }
            }
            return sc.sortResultsAscending();
        } finally {
            sc.endLayer();
        }
    }

    /**
     * 构建期用的物化版：把 {@link #searchLayerSlots} 的结果序号换成按距离升序的
     * {@code SearchResult[]}。只有 {@code insertInternal} 走这里（{@code selectNeighbors}
     * 要的就是带 id 和距离的候选数组）；查询路径直接消费序号，不经过这一步。
     */
    private SearchResult[] searchLayer(float[] query, String[] entryPoints, int ef, int level) {
        SearchScratch sc = scratches.get();
        int n = searchLayerSlots(query, entryPoints, ef, level, sc);
        SearchResult[] arr = new SearchResult[n];
        for (int i = 0; i < n; i++) {
            int ord = sc.far[i];
            arr[i] = new SearchResult(sc.nodeAt[ord].id, sc.distAt[ord]);
        }
        return arr;
    }

    /**
     * 邻居选择 —— 论文 Algorithm 4 的启发式（extendCandidates=false、
     * keepPrunedConnections=true）。
     * <p>
     * 判据是"候选离 q 比离任何一个已选邻居更近才留"，而不是"离 q 最近的 M 个"。后者会把图
     * 掰成一堆孤岛：聚簇数据里同簇点彼此更近，任何一条跨簇边都会在修剪时被同簇的近邻挤掉，
     * 于是从入口点出发 layer 0 只能走到极少数节点 —— {@code HnswGraphConnectivityTest} 那套
     * 语料（n=3000、dim=128、50 簇、层级种子固定）下实测可达集只有 57/3000、
     * recall@10@efSearch=64 = 0.647，而且 efSearch 从 64 抬到 1024 逐查询命中一位都不变
     * （那些节点根本不在可达集里，不是 beam 太窄）。
     * <p>
     * 前提：{@code candidates} 已按离 q 的距离升序排列（{@link #searchLayer} 的返回就是这个
     * 顺序，反向修剪用的邻居表 score 同样是"到本节点的距离"）。
     * <p>
     * 代价：每接受一条边要多算几次点间距离，构建期变慢。实测 dim=128、efConstruction=200 的
     * 聚簇语料（n=20000、200 簇）下 {@code build} 约 29~38s。查询侧同样不是免费的：
     * efSearch=64 时每次查询的距离计算从 147 次涨到 251 次（图真的铺开了），换来的是
     * recall@10 从 0.32 到 1.00 —— 修之前无论把 efSearch 抬到多大（试过 1024）都到不了
     * 1.00，所以这不是"多花点数换召回"那种可以用调参抵消的取舍。
     */
    private List<SearchResult> selectNeighbors(Node q, SearchResult[] candidates, int M) {
        List<SearchResult> selected = new ArrayList<>(M);
        List<SearchResult> discarded = new ArrayList<>();
        for (SearchResult cand : candidates) {
            if (selected.size() >= M) break;
            if (q.id.equals(cand.getVectorId())) continue; // upsert 时自己可能出现在自己的候选集里
            Node cn = nodes.get(cand.getVectorId());
            if (cn == null) continue;
            if (redundantToSelected(cn, cand.getScore(), selected)) {
                discarded.add(cand);
                continue;
            }
            selected.add(cand);
        }
        // keepPrunedConnections：图要连通就不能为了"纯度"少留边，不够 M 条时用被丢弃的最近候选补齐
        for (SearchResult c : discarded) {
            if (selected.size() >= M) break;
            selected.add(c);
        }
        return selected;
    }

    /** 候选 cn 到某个已选邻居比到 q 还近 ⇒ 这条边是冗余的，可以让给它俩之间的那条。 */
    private boolean redundantToSelected(Node cn, float distQ, List<SearchResult> selected) {
        for (SearchResult s : selected) {
            Node sn = nodes.get(s.getVectorId());
            if (sn == null) continue;
            if (distance.compute(cn.vector, sn.vector) < distQ) return true;
        }
        return false;
    }


    /** 建立新节点与候选的连接 */
    private void connect(Node node, List<SearchResult> neighbors, int level) {
        if (node.neighbors[level] == null) {
            node.neighbors[level] = new ArrayList<>();
        }
        node.neighbors[level].addAll(neighbors);
    }

    /** 修剪邻居到该层的上限 —— 与正向选择同一套启发式，否则长程边还是会被挤掉。 */
    private void pruneNeighbors(Node node, int level, int M) {
        List<SearchResult> list = node.neighbors[level];
        if (list == null || list.size() <= M) return;
        SearchResult[] byDist = list.toArray(new SearchResult[0]);
        Arrays.sort(byDist, Comparator.comparingDouble(SearchResult::getScore));
        node.neighbors[level] = new ArrayList<>(selectNeighbors(node, byDist, M));
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
        /** 密集槽位号，只给 {@link SearchScratch} 的已访问位图当下标用。 */
        final int slot;
        Map<String, Object> payloadRef;  // 引用 VectorPoint 的 payload（节省内存）
        @SuppressWarnings("unchecked")
        final List<SearchResult>[] neighbors;

        @SuppressWarnings("unchecked")
        Node(String id, float[] vector, int level, int slot) {
            this.id = id;
            this.vector = vector;
            this.level = level;
            this.slot = slot;
            this.neighbors = new List[level + 1];
            for (int i = 0; i <= level; i++) {
                this.neighbors[i] = new ArrayList<>();
            }
        }
    }
}