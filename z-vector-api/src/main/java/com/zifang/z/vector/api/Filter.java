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
            case EQ: return valuesMatch(fieldValue, value);
            case NE: return !valuesMatch(fieldValue, value);
            case GT: { Integer c = compareNumbers(fieldValue, value); return c != null && c > 0; }
            case GTE: { Integer c = compareNumbers(fieldValue, value); return c != null && c >= 0; }
            case LT: { Integer c = compareNumbers(fieldValue, value); return c != null && c < 0; }
            case LTE: { Integer c = compareNumbers(fieldValue, value); return c != null && c <= 0; }
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
            if (valuesMatch(fieldValue, v)) return true;
        }
        return false;
    }

    /**
     * 数值宽容的等值比较 — payload 的值来自 JSON，数字会被解析成 Integer / Long / Double
     * 中"最省"的那一种，而过滤条件里手写的字面量往往是另一种（{@code Filter.eq("views", 1L)}
     * 对上 Jackson 给出的 {@code Integer 1}）。纯粹的 {@code Objects.equals} 会把这类
     * 相等判成不等，搜索静默返回 0 行。
     */
    public static boolean valuesMatch(Object actual, Object expected) {
        if (Objects.equals(actual, expected)) return true;
        if (actual instanceof Number && expected instanceof Number) {
            double a = ((Number) actual).doubleValue();
            double b = ((Number) expected).doubleValue();
            return !Double.isNaN(a) && a == b;
        }
        return false;
    }

    /**
     * 比较两个可排序值。返回 {@code null} 表示"不可比"——字段缺失、类型不同（数字 vs 字符串）
     * 都算不可比。
     * <p>
     * <b>为什么必须用 null 而不是 {@code Integer.MIN_VALUE} 兜底</b>：MIN_VALUE 会让 {@code lt}
     * / {@code lte} 对<b>根本没有这个字段</b>的文档判真，等于"缺字段 = 值最小"，把范围过滤
     * 变成了"所有脏数据都命中"。SQL 里 NULL 参与比较的结果是 UNKNOWN（不命中），这里对齐。
     */
    private static Integer compareNumbers(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        if (a instanceof String && b instanceof String) {
            return ((String) a).compareTo((String) b);
        }
        return null;
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
     * 提取可下推给索引层的"纯等值"条件 — 只有当整个表达式是单个 {@code EQ}，或者
     * "若干个不同字段的 {@code EQ} 的 AND"时才返回 Map；其余一切形状返回 {@code null}，
     * 由调用方在搜索结果上做 {@link #evaluate(Map)}。
     * <p>
     * <b>为什么收紧到只处理 EQ</b>：索引层的扁平 Map 语义是"这些 key 都得等于给定值"，
     * 把 {@code NE / IN / EXISTS / CONTAINS} 也塞进同一个 Map（把 {@code field != v} 写成
     * {@code {field: v}}）会让下推结果与表达式含义相反 — 过滤越严格反而返回不相关的点。
     * <p>
     * 复合 AND 里出现同字段的两个 EQ（{@code a=1 AND a=2}）同样不下推：合并后只剩一条，
     * 会把恒假的条件变成可命中。
     */
    public Map<String, Object> toFlatPayload() {
        if (field != null) {
            if (op != Op.EQ) return null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(field, value);
            return m;
        }
        // field == null：AND / OR / NOT 节点
        if (op != Op.EQ || children.isEmpty()) return null;   // OR / NOT / 空 AND 不可等值下推
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Filter child : children) {
            Map<String, Object> sub = child.toFlatPayload();
            if (sub == null) return null;
            for (Map.Entry<String, Object> e : sub.entrySet()) {
                if (merged.containsKey(e.getKey())) return null;
                merged.put(e.getKey(), e.getValue());
            }
        }
        return merged;
    }
}