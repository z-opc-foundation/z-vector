package com.zifang.z.vector.core.index;

import com.zifang.z.vector.api.SearchResult;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.core.distance.Distance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 暴力精确索引（Flat Index）— 最简单但保证 100% 召回率。
 *
 * <h2>复杂度</h2>
 * <ul>
 *   <li>插入: O(1)</li>
 *   <li>搜索: O(N * d) — 与所有点计算距离</li>
 * </ul>
 *
 * <h2>适用场景</h2>
 * <ul>
 *   <li>小规模数据（&lt; 10K）</li>
 *   <li>需要 100% 召回率的精确场景</li>
 *   <li>作为 ANN 索引召回率验证的 ground truth</li>
 * </ul>
 *
 * <h2>性能优化</h2>
 * <ul>
 *   <li>使用 ConcurrentHashMap 存储点，支持并发读写</li>
 *   <li>搜索时使用 Set 记录已删除 id，避免脏数据</li>
 *   <li>topK 通过 max-heap 优化（Java 用 Collections.sort 替代）</li>
 * </ul>
 */
public class FlatIndex implements Index {

    private static final Logger LOG = LoggerFactory.getLogger(FlatIndex.class);

    private final Distance distance;
    private final int dimension;
    private final Map<String, VectorPoint> points = new ConcurrentHashMap<>();
    private final Set<String> tombstones = ConcurrentHashMap.newKeySet();
    private volatile boolean built = false;

    public FlatIndex(Distance distance, int dimension) {
        this.distance = Objects.requireNonNull(distance, "distance");
        if (dimension <= 0) throw new IllegalArgumentException("dimension must be > 0");
        this.dimension = dimension;
    }

    @Override
    public String type() { return "FLAT"; }

    @Override
    public int dimension() { return dimension; }

    @Override
    public int size() { return points.size(); }

    @Override
    public boolean isBuilt() { return built; }

    @Override
    public void build(List<VectorPoint> buildPoints) {
        points.clear();
        tombstones.clear();
        for (VectorPoint p : buildPoints) {
            validate(p);
            points.put(p.getId(), p);
        }
        built = true;
        LOG.info("FlatIndex built: {} points, dim={}", points.size(), dimension);
    }

    @Override
    public void add(VectorPoint point) {
        validate(point);
        points.put(point.getId(), point);
        tombstones.remove(point.getId());
        // FLAT 无需 build，但标记为 ready
        built = true;
    }

    @Override
    public boolean remove(String id) {
        VectorPoint removed = points.remove(id);
        tombstones.add(id);
        return removed != null;
    }

    @Override
    public VectorPoint get(String id) {
        return points.get(id);
    }

    @Override
    public void clear() {
        points.clear();
        tombstones.clear();
        built = false;
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
        if (topK <= 0) return Collections.emptyList();
        validateQuery(query);

        List<SearchResult> candidates = new ArrayList<>(Math.min(topK * 2, points.size()));
        for (VectorPoint p : points.values()) {
            if (tombstones.contains(p.getId())) continue;
            if (filterPayload != null && !matchesFilter(p, filterPayload)) continue;

            float dist = distance.compute(query, p.getVector());
            if (dist > maxDistance) continue;
            candidates.add(new SearchResult(p.getId(), dist, p.getPayload()));
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

    // ==================== 内部方法 ====================

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

    private boolean matchesFilter(VectorPoint p, Map<String, Object> filter) {
        Map<String, Object> payload = p.getPayload();
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            if (!Objects.equals(payload.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }
}