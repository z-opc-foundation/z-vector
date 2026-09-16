package com.zifang.z.vector.core.filter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Payload 倒排索引 — 加速过滤搜索。
 * <p>
 * 设计参考 Qdrant 的 Payload Index + Lucene 的倒排索引 + zvec 的 inverted_column_indexer.
 *
 * <h2>核心思想</h2>
 * <pre>
 * 字段 → 值 → [point id 集合]
 * lang=zh → {d1, d3, d7, ...}
 * lang=en → {d2, d5, ...}
 * score>0.5 → {d3, d4, ...}
 * </pre>
 *
 * <h2>加速效果</h2>
 * <ul>
 *   <li>无 Payload 索引：filter 需要遍历全部向量计算 O(N)</li>
 *   <li>有 Payload 索引：filter 只需在 hit set 上做 ANN，复杂度降到 O(N_filtered * d)</li>
 *   <li>典型 1M 向量 + 10% 过滤命中：100x 加速</li>
 * </ul>
 *
 * <h2>支持的操作符</h2>
 * <ul>
 *   <li>EQ / NE：精确匹配（O(1)）</li>
 *   <li>IN / NOT_IN：集合成员（O(K)）</li>
 *   <li>RANGE（GT/GTE/LT/LTE）：排序数组二分查找（O(log N)）</li>
 *   <li>EXISTS：存在性（O(1)）</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>
 * 使用 ConcurrentHashMap 保护 field→value→ids 的多级映射。
 * 注意：put/remove 操作需在外部加锁（Collection 层）保证一致性。
 */
public class PayloadIndex {

    /** 字段 → (值 → point id 集合) */
    private final ConcurrentHashMap<String, ValueIndex> fieldIndexes = new ConcurrentHashMap<>();

    /** 注册的字段名（哪些字段被索引） */
    private final Set<String> indexedFields = ConcurrentHashMap.newKeySet();

    /**
     * 索引一个值
     */
    public void index(String field, Object value, String pointId) {
        if (field == null || value == null || pointId == null) return;
        ValueIndex vidx = fieldIndexes.computeIfAbsent(field, k -> {
            // 默认精确索引；调用方应通过 indexField 预注册以使用 NumericIndex
            return new ExactIndex();
        });
        if (vidx instanceof ExactIndex) {
            ((ExactIndex) vidx).add(value, pointId);
        } else if (vidx instanceof NumericIndex) {
            ((NumericIndex) vidx).add(((Number) value).doubleValue(), pointId);
        } else {
            throw new IllegalStateException("Unknown index type");
        }
        indexedFields.add(field);
    }

    /**
     * 移除一个值
     */
    public void remove(String field, Object value, String pointId) {
        ValueIndex vidx = fieldIndexes.get(field);
        if (vidx == null) return;
        if (vidx instanceof ExactIndex) {
            ((ExactIndex) vidx).remove(value, pointId);
        } else if (vidx instanceof NumericIndex) {
            ((NumericIndex) vidx).remove(((Number) value).doubleValue(), pointId);
        }
    }

    /**
     * 查询 EQ：返回匹配 point id 集合
     */
    public Set<String> eq(String field, Object value) {
        ValueIndex vidx = fieldIndexes.get(field);
        if (vidx instanceof ExactIndex) {
            return ((ExactIndex) vidx).get(value);
        }
        return Collections.emptySet();
    }

    /**
     * 查询 NE：返回不匹配 point id 集合（全集中去掉匹配）
     */
    public Set<String> ne(String field, Object value, Set<String> universe) {
        Set<String> matched = eq(field, value);
        Set<String> result = new HashSet<>(universe);
        result.removeAll(matched);
        return result;
    }

    /**
     * 查询 IN：value 在列表内的点
     */
    public Set<String> inValues(String field, Collection<?> values) {
        Set<String> result = new HashSet<>();
        for (Object v : values) {
            Set<String> matches = eq(field, v);
            if (matches != null) result.addAll(matches);
        }
        return result;
    }

    /**
     * 查询 RANGE：值在 [min, max] 区间内的点（仅适用于数字字段）
     */
    public Set<String> range(String field, double min, boolean minInclusive,
                             double max, boolean maxInclusive) {
        ValueIndex vidx = fieldIndexes.get(field);
        if (vidx instanceof NumericIndex) {
            return ((NumericIndex) vidx).range(min, minInclusive, max, maxInclusive);
        }
        return Collections.emptySet();
    }

    /**
     * EXISTS：返回所有存在该字段的点
     */
    public Set<String> exists(String field) {
        ValueIndex vidx = fieldIndexes.get(field);
        if (vidx == null) return Collections.emptySet();
        if (vidx instanceof ExactIndex) {
            return ((ExactIndex) vidx).allIds();
        }
        if (vidx instanceof NumericIndex) {
            return ((NumericIndex) vidx).allIds();
        }
        return Collections.emptySet();
    }

    /**
     * 该字段是否已建索引
     */
    public boolean isIndexed(String field) {
        return indexedFields.contains(field);
    }

    /**
     * 已索引字段列表
     */
    public Set<String> indexedFields() {
        return new HashSet<>(indexedFields);
    }

    /**
     * 清空全部索引
     */
    public void clear() {
        fieldIndexes.clear();
        indexedFields.clear();
    }

    public int size() {
        return indexedFields.size();
    }

    // ==================== 内部索引实现 ====================

    /**
     * 注册字段索引（自动选择索引类型）
     */
    public PayloadIndex indexField(String field, Class<?> valueType) {
        ValueIndex vidx;
        if (Number.class.isAssignableFrom(valueType) || valueType == int.class
                || valueType == long.class || valueType == double.class
                || valueType == float.class) {
            vidx = new NumericIndex();
        } else {
            vidx = new ExactIndex();
        }
        fieldIndexes.put(field, vidx);
        indexedFields.add(field);
        return this;
    }

    /**
     * 字段索引抽象类 — ExactIndex 或 NumericIndex
     */
    private abstract static class ValueIndex {}

    /**
     * 精确值索引（字符串、布尔等）— 倒排表
     */
    private static class ExactIndex extends ValueIndex {
        private final ConcurrentHashMap<Object, Set<String>> valueToIds = new ConcurrentHashMap<>();
        // 反向索引：id → 值（用于删除时定位）
        private final ConcurrentHashMap<String, Object> idToValue = new ConcurrentHashMap<>();

        void add(Object value, String id) {
            valueToIds.computeIfAbsent(value, k -> ConcurrentHashMap.newKeySet()).add(id);
            idToValue.put(id, value);
        }

        void remove(Object value, String id) {
            Set<String> ids = valueToIds.get(value);
            if (ids != null) ids.remove(id);
            if (ids != null && ids.isEmpty()) valueToIds.remove(value);
            idToValue.remove(id, value);
        }

        Set<String> get(Object value) {
            Set<String> ids = valueToIds.get(value);
            return ids == null ? Collections.emptySet() : new HashSet<>(ids);
        }

        Set<String> allIds() {
            Set<String> all = new HashSet<>();
            for (Set<String> ids : valueToIds.values()) all.addAll(ids);
            return all;
        }
    }

    /**
     * 数字范围索引（int, double 等）— 排序数组 + 二分查找
     */
    private static class NumericIndex extends ValueIndex {
        /** 已排序的 (value, id) 对，用 TreeMap 便于范围查询 */
        private final java.util.TreeMap<Double, Set<String>> valueToIds = new java.util.TreeMap<>();
        private final ConcurrentHashMap<String, Double> idToValue = new ConcurrentHashMap<>();

        synchronized void add(double value, String id) {
            valueToIds.computeIfAbsent(value, k -> ConcurrentHashMap.newKeySet()).add(id);
            idToValue.put(id, value);
        }

        synchronized void remove(double value, String id) {
            Set<String> ids = valueToIds.get(value);
            if (ids != null) ids.remove(id);
            if (ids != null && ids.isEmpty()) valueToIds.remove(value);
            idToValue.remove(id, value);
        }

        synchronized Set<String> range(double min, boolean minInclusive,
                                       double max, boolean maxInclusive) {
            Set<String> result = new HashSet<>();
            // 找到范围内的所有 entries
            // 使用 navigableKeySet 迭代
            java.util.NavigableMap<Double, Set<String>> sub;
            if (minInclusive) {
                sub = valueToIds.tailMap(min, true);
            } else {
                sub = valueToIds.tailMap(min, false);
            }
            // 再用 headMap 截断上界
            java.util.NavigableMap<Double, Set<String>> bounded;
            if (maxInclusive) {
                bounded = sub.headMap(max, true);
            } else {
                bounded = sub.headMap(max, false);
            }
            for (Set<String> ids : bounded.values()) result.addAll(ids);
            return result;
        }

        Set<String> allIds() {
            Set<String> all = new HashSet<>();
            for (Set<String> ids : valueToIds.values()) all.addAll(ids);
            return all;
        }

        private Double findNextValue(double v) {
            return valueToIds.ceilingKey(v);
        }

        private Double findPrevValue(double v) {
            return valueToIds.floorKey(v);
        }
    }
}