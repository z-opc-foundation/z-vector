package com.zifang.z.vector.core.index;

import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.core.cluster.KMeansAdapter;
import com.zifang.z.vector.core.distance.Distance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IVF 索引（Inverted File Index）— 倒排文件 + K-means 聚类。
 * <p>
 * 算法思想：
 * <ol>
 *   <li>用 K-means 将所有向量聚类成 nlist 个簇，每簇一个质心</li>
 *   <li>每个向量归属到最近的质心所在的倒排列表</li>
 *   <li>搜索时只探测 nprobe 个最近的簇的倒排列表（粗筛）</li>
 *   <li>在倒排列表内做精确距离计算（精排）</li>
 * </ol>
 *
 * <h2>复杂度</h2>
 * <ul>
 *   <li>训练（build）: O(N * nlist * iter * d)</li>
 *   <li>搜索: O(nprobe * (N/nlist) * d) — 通常 nprobe=8 ~ 64</li>
 * </ul>
 *
 * <h2>关键参数</h2>
 * <ul>
 *   <li>nlist: 簇数量（默认 sqrt(N)，推荐 100 ~ 10000）</li>
 *   <li>nprobe: 搜索时探测的簇数（默认 8，可调）</li>
 *   <li>maxIter: K-means 最大迭代次数（默认 20）</li>
 * </ul>
 *
 * <h2>召回率调优</h2>
 * nprobe 越大，召回率越高，搜索越慢。nprobe=64 通常能达 95%+ 召回率。
 *
 * <h2>注意</h2>
 * <ul>
 *   <li>nlist 必须小于等于数据量，否则会自动调整为 N</li>
 *   <li>K-means 随机初始化，结果可能不稳定</li>
 *   <li>数据规模变化大时需重建索引</li>
 * </ul>
 */
public class IvfIndex implements Index {

    private static final Logger LOG = LoggerFactory.getLogger(IvfIndex.class);

    /** 默认参数 */
    public static final int DEFAULT_NLIST = 64;
    public static final int DEFAULT_NPROBE = 8;
    public static final int DEFAULT_MAX_ITER = 20;

    private final Distance distance;
    private final int dimension;
    private final int nlist;
    private final int maxIter;
    private volatile int nprobe;

    /** 质心数组 — 每个质心是 dim 维向量 */
    private volatile float[][] centroids;
    /** 倒排列表 — 每个簇对应的 VectorPoint id 列表 */
    private volatile List<List<String>> invertedLists;
    /** 所有点存储（用于倒排列表读取和删除） */
    private final Map<String, VectorPoint> points = new ConcurrentHashMap<>();
    /** 逻辑删除的 id */
    private final Set<String> tombstones = ConcurrentHashMap.newKeySet();
    private volatile boolean built = false;

    public IvfIndex(Distance distance, int dimension) {
        this(distance, dimension, DEFAULT_NLIST, DEFAULT_NPROBE, DEFAULT_MAX_ITER);
    }

    public IvfIndex(Distance distance, int dimension, int nlist, int nprobe, int maxIter) {
        this.distance = Objects.requireNonNull(distance, "distance");
        if (dimension <= 0) throw new IllegalArgumentException("dimension must be > 0");
        this.dimension = dimension;
        if (nlist <= 0) throw new IllegalArgumentException("nlist must be > 0");
        this.nlist = nlist;
        if (nprobe <= 0) throw new IllegalArgumentException("nprobe must be > 0");
        this.nprobe = nprobe;
        if (maxIter <= 0) throw new IllegalArgumentException("maxIter must be > 0");
        this.maxIter = maxIter;
    }

    @Override
    public String type() { return "IVF"; }

    @Override
    public int dimension() { return dimension; }

    @Override
    public int size() { return points.size() - tombstones.size(); }

    @Override
    public boolean isBuilt() { return built; }

    public int getNprobe() { return nprobe; }
    public void setNprobe(int nprobe) {
        if (nprobe > 0 && nprobe <= nlist) this.nprobe = nprobe;
    }

    @Override
    public void build(List<VectorPoint> buildPoints) {
        if (buildPoints.isEmpty()) {
            centroids = new float[0][];
            invertedLists = new ArrayList<>();
            built = false;
            return;
        }
        int actualNlist = Math.min(nlist, buildPoints.size());
        for (VectorPoint p : buildPoints) {
            validate(p);
            points.put(p.getId(), p);
        }

        // 委托给 z-util-ml 的 KMeans（通过 KMeansAdapter 桥接 float[][]）
        float[][] matrix = new float[buildPoints.size()][dimension];
        for (int i = 0; i < buildPoints.size(); i++) {
            System.arraycopy(buildPoints.get(i).getVector(), 0, matrix[i], 0, dimension);
        }
        float[][] finalCentroids = KMeansAdapter.cluster(matrix, actualNlist, maxIter);

        // 分配每个点到最近的质心
        List<List<String>> lists = new ArrayList<>(actualNlist);
        for (int i = 0; i < actualNlist; i++) lists.add(new ArrayList<>());
        for (VectorPoint p : buildPoints) {
            int nearest = nearestCentroid(p.getVector(), finalCentroids);
            lists.get(nearest).add(p.getId());
        }

        this.centroids = finalCentroids;
        this.invertedLists = lists;
        this.built = true;
        LOG.info("IVF built: {} points, nlist={}, avgListSize={}",
                buildPoints.size(), actualNlist,
                buildPoints.size() / Math.max(1, actualNlist));
    }

    @Override
    public void add(VectorPoint point) {
        validate(point);
        points.put(point.getId(), point);
        tombstones.remove(point.getId());
        if (built) {
            // 动态插入：分配到最近质心
            int nearest = nearestCentroid(point.getVector(), centroids);
            synchronized (invertedLists) {
                invertedLists.get(nearest).add(point.getId());
            }
        }
        // 未构建时只存储，不分配
    }

    @Override
    public boolean remove(String id) {
        VectorPoint removed = points.remove(id);
        if (removed != null) {
            tombstones.add(id);
            // 从倒排列表中移除
            if (built) {
                synchronized (invertedLists) {
                    for (List<String> list : invertedLists) {
                        list.remove(id);
                    }
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        points.clear();
        tombstones.clear();
        centroids = null;
        invertedLists = null;
        built = false;
    }

    @Override
    public VectorPoint get(String id) {
        return points.get(id);
    }

    @Override
    public java.util.List<VectorPoint> entries() {
        java.util.List<VectorPoint> all = new java.util.ArrayList<>(points.size());
        for (VectorPoint p : points.values()) {
            if (!tombstones.contains(p.getId())) all.add(p);
        }
        return all;
    }

    @Override
    public List<SearchResult> search(float[] query, int topK,
                                     Map<String, Object> filterPayload,
                                     float maxDistance) {
        if (topK <= 0 || !built) return Collections.emptyList();
        validateQuery(query);

        // 找到 nprobe 个最近的质心
        int[] probes = findNearestCentroids(query, nprobe);

        // 在这些簇内做精确搜索
        List<SearchResult> candidates = new ArrayList<>();
        for (int ci : probes) {
            List<String> list = invertedLists.get(ci);
            for (String id : list) {
                if (tombstones.contains(id)) continue;
                VectorPoint p = points.get(id);
                if (p == null) continue;
                if (filterPayload != null && !matchesFilter(p, filterPayload)) continue;

                float dist = distance.compute(query, p.getVector());
                if (dist > maxDistance) continue;
                candidates.add(new SearchResult(id, dist, p.getPayload()));
            }
        }

        candidates.sort(Comparator.comparingDouble(SearchResult::getScore));
        if (candidates.size() > topK) {
            candidates = candidates.subList(0, topK);
        }
        return candidates;
    }

    @Override
    public List<SearchResult> searchRange(float[] query, float maxDistance, int topK,
                                          Map<String, Object> filterPayload) {
        return search(query, topK, filterPayload, maxDistance);
    }

    // ==================== K-means 委托（z-util-ml） ====================
    // 注：实际聚类由 build() 通过 KMeansAdapter 委托给 z-util-ml 的 KMeans 实现。
    // 这里只保留 nearestCentroid（热路径：用于 add / search 时分配向量到最近簇）。

    private int nearestCentroid(float[] vector, float[][] centroids) {
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < centroids.length; i++) {
            float d = distance.compute(vector, centroids[i]);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /** 找到 nprobe 个最近质心的索引 */
    private int[] findNearestCentroids(float[] query, int nprobe) {
        int k = centroids.length;
        int actualProbe = Math.min(nprobe, k);

        // 计算到所有质心的距离
        float[] dists = new float[k];
        for (int i = 0; i < k; i++) {
            dists[i] = distance.compute(query, centroids[i]);
        }
        // 取最小的 actualProbe 个
        Integer[] indices = new Integer[k];
        for (int i = 0; i < k; i++) indices[i] = i;
        final float[] fdists = dists;
        Arrays.sort(indices, (a, b) -> Float.compare(fdists[a], fdists[b]));

        int[] result = new int[actualProbe];
        for (int i = 0; i < actualProbe; i++) result[i] = indices[i];
        return result;
    }

    private boolean matchesFilter(VectorPoint p, Map<String, Object> filter) {
        Map<String, Object> payload = p.getPayload();
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
}