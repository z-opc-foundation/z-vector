package com.zifang.z.vector.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Payload 过滤表达式 — 用于向量搜索时对 payload 进行条件过滤。
 * <p>
 * 设计参考 Qdrant 的 Filter 表达式 + zvec 的 SQL-like filter + Milvus 的 boolean expression.
 * <p>
 * 支持的运算符:
 * <ul>
 *   <li>{@link Op#EQ}: 等于</li>
 *   <li>{@link Op#NE}: 不等于</li>
 *   <li>{@link Op#GT}: 大于</li>
 *   <li>{@link Op#GTE}: 大于等于</li>
 *   <li>{@link Op#LT}: 小于</li>
 *   <li>{@link Op#LTE}: 小于等于</li>
 *   <li>{@link Op#IN}: 在列表内</li>
 *   <li>{@link Op#NOT_IN}: 不在列表内</li>
 *   <li>{@link Op#EXISTS}: 字段存在</li>
 *   <li>{@link Op#CONTAINS}: 字符串包含</li>
 * </ul>
 * 支持组合: {@link #and(Filter...)} / {@link #or(Filter...)} / {@link #not()}
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 简单条件: lang = "zh"
 * Filter f1 = Filter.eq("lang", "zh");
 *
 * // 复合条件: lang = "zh" AND score >= 0.8
 * Filter f2 = Filter.and(
 *     Filter.eq("lang", "zh"),
 *     Filter.gte("score", 0.8)
 * );
 *
 * // 嵌套: (lang = "zh" OR lang = "en") AND category IN ("news", "blog")
 * Filter f3 = Filter.and(
 *     Filter.or(Filter.eq("lang", "zh"), Filter.eq("lang", "en")),
 *     Filter.inValues("category", Arrays.asList("news", "blog"))
 * );
 * }</pre>
 */
public class Filter {

    /** 过滤操作符 */
    public enum Op {
        EQ, NE, GT, GTE, LT, LTE, IN, NOT_IN, EXISTS, CONTAINS
    }

    private final Op op;
    private final String field;
    private final Object value;
    private final List<Filter> children;

    private Filter(Op op, String field, Object value, List<Filter> children) {
        this.op = op;
        this.field = field;
        this.value = value;
        this.children = children == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(children));
    }

    public Op getOp() { return op; }
    public String getField() { return field; }
    public Object getValue() { return value; }
    public List<Filter> getChildren() { return children; }

    public boolean isComposite() {
        return op == Op.EQ && field == null; // 通过构造函数判断；或使用专门的 isAnd/isOr
    }

    // ==================== 工厂方法 ====================

    public static Filter eq(String field, Object value) {
        return new Filter(Op.EQ, Objects.requireNonNull(field), value, null);
    }

    public static Filter ne(String field, Object value) {
        return new Filter(Op.NE, Objects.requireNonNull(field), value, null);
    }

    public static Filter gt(String field, Number value) {
        return new Filter(Op.GT, Objects.requireNonNull(field), value, null);
    }

    public static Filter gte(String field, Number value) {
        return new Filter(Op.GTE, Objects.requireNonNull(field), value, null);
    }

    public static Filter lt(String field, Number value) {
        return new Filter(Op.LT, Objects.requireNonNull(field), value, null);
    }

    public static Filter lte(String field, Number value) {
        return new Filter(Op.LTE, Objects.requireNonNull(field), value, null);
    }

    public static Filter inValues(String field, Collection<?> values) {
        return new Filter(Op.IN, Objects.requireNonNull(field),
                new ArrayList<>(values), null);
    }

    public static Filter notIn(String field, Collection<?> values) {
        return new Filter(Op.NOT_IN, Objects.requireNonNull(field),
                new ArrayList<>(values), null);
    }

    public static Filter exists(String field) {
        return new Filter(Op.EXISTS, Objects.requireNonNull(field), null, null);
    }

    public static Filter contains(String field, String substring) {
        return new Filter(Op.CONTAINS, Objects.requireNonNull(field), substring, null);
    }

    public static Filter and(Filter... children) {
        Objects.requireNonNull(children, "children");
        if (children.length == 0) {
            return new Filter(Op.EQ, null, null, Collections.emptyList()); // 恒真
        }
        return new Filter(Op.EQ, null, null, Arrays.asList(children));
    }

    public static Filter or(Filter... children) {
        Objects.requireNonNull(children, "children");
        if (children.length == 0) {
            return new Filter(Op.NE, null, null, Collections.emptyList()); // 恒假
        }
        // 用 EQ + field=null 表达 AND/OR；通过子节点判断（用 gt/gte 区分 OR）
        // 为了清晰，使用 GTE 表达 OR
        return new Filter(Op.GTE, null, null, Arrays.asList(children));
    }

    public Filter not() {
        return new Filter(Op.NE, null, this, null);
    }

    // ==================== 评估 ====================

    /**
     * 判断给定 payload 是否满足过滤条件
     */
    public boolean evaluate(Map<String, Object> payload) {
        // AND/OR 节点
        if (children != null && !children.isEmpty() && field == null) {
            boolean isAnd = (op == Op.EQ);
            for (Filter child : children) {
                boolean matches = child.evaluate(payload);
                if (isAnd && !matches) return false;
                if (!isAnd && matches) return true;
            }
            return isAnd; // AND 全真才为真；OR 全假才为假
        }
        // NOT 节点（value 包装了子 filter）
        if (op == Op.NE && value instanceof Filter) {
            return !((Filter) value).evaluate(payload);
        }
        // 叶子节点
        Object fieldValue = payload == null ? null : payload.get(field);
        switch (op) {
            case EQ: return Objects.equals(fieldValue, value);
            case NE: return !Objects.equals(fieldValue, value);
            case GT: return compareNumbers(fieldValue, value) > 0;
            case GTE: return compareNumbers(fieldValue, value) >= 0;
            case LT: return compareNumbers(fieldValue, value) < 0;
            case LTE: return compareNumbers(fieldValue, value) <= 0;
            case EXISTS: return fieldValue != null;
            case IN: return inList(fieldValue);
            case NOT_IN: return !inList(fieldValue);
            case CONTAINS:
                return fieldValue instanceof String
                        && ((String) fieldValue).contains(String.valueOf(value));
            default:
                throw new IllegalStateException("Unknown op: " + op);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean inList(Object fieldValue) {
        if (!(value instanceof Collection)) return false;
        for (Object v : (Collection<?>) value) {
            if (Objects.equals(fieldValue, v)) return true;
        }
        return false;
    }

    private static int compareNumbers(Object a, Object b) {
        if (a == null || b == null) return Integer.MIN_VALUE;
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        // 字符串比较
        if (a instanceof String && b instanceof String) {
            return ((String) a).compareTo((String) b);
        }
        return Integer.MIN_VALUE;
    }

    @Override
    public String toString() {
        if (field == null && children != null && !children.isEmpty()) {
            String sep = (op == Op.EQ) ? " AND " : " OR ";
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) sb.append(sep);
                sb.append(children.get(i));
            }
            sb.append(")");
            return sb.toString();
        }
        return String.format("%s %s %s", field, op, value);
    }

    /** 序列化到 Map，方便 REST API 传输 */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (children != null && !children.isEmpty() && field == null) {
            result.put(op == Op.EQ ? "and" : "or",
                    children.stream().map(Filter::toMap).collect(java.util.stream.Collectors.toList()));
            return result;
        }
        if (op == Op.NE && value instanceof Filter) {
            Map<String, Object> not = new LinkedHashMap<>();
            not.put("not", ((Filter) value).toMap());
            return not;
        }
        Map<String, Object> cond = new LinkedHashMap<>();
        Map<String, Object> opSpec = new LinkedHashMap<>();
        opSpec.put("value", value);
        cond.put(op.name().toLowerCase(), opSpec);
        result.put(field, cond);
        return result;
    }

    /**
     * 转换为索引层可用的扁平 Map — 仅保留单层等值匹配（用于简单场景）。
     * <p>
     * 复杂表达式（AND/OR/NOT/范围）应在搜索循环中通过 {@link #evaluate(Map)} 调用。
     */
    public Map<String, Object> toFlatPayload() {
        if (this.field != null && (op == Op.EQ || op == Op.NE || op == Op.IN || op == Op.NOT_IN
                || op == Op.EXISTS || op == Op.CONTAINS)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(field, value);
            return m;
        }
        return null;
    }
}