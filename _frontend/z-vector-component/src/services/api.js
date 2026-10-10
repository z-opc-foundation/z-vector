import request from '@/common'

/**
 * z-vector 管理面数据源。
 *
 * 实测结论（2026-09-24）：Qdrant 风格的 REST 面不是 Spring MVC，而是 z-vector-grpc-server 的
 * `QdrantRestServer` 用 JDK HttpServer 自己起的（内嵌在 z-opc JVM 的 6334），
 * 所以它永远不出现在 `/actuator/mappings` 里，也不在 vite 的 `/api` 代理范围内。
 * 本模块全部走 z-opc 侧的 `VectorProxyController`（`/api/vector/**` → 本 JVM 已 bind 的那个端口），
 * 响应形状就是 QdrantRestServer 自己拼的那份 JSON，没有第二套字段命名：
 *   GET    /collections                     → {status:"ok", collections:["name", …]}   ← 只有名字
 *   GET    /collections/{n}                 → {name, dimension, metric, index_type, points_count, indexed}
 *   GET    /collections/{n}/points/count    → {count}
 *   POST   /collections/{n}/points/search   → {result:[{id, score, payload?}], status, time_ms}
 *   PUT    /collections/{n}                 → {status:"ok", name}
 *   PUT    /collections/{n}/points          → {status:"ok", count}
 *   DELETE /collections/{n}                 → {status:"ok"}
 * 失败时后端返回 {status:"error", code, message} 且 HTTP 状态码同 code（axios 会走 reject 分支）。
 */
export const vectorApi = {
    instance: () => request.get('/vector/__instance'),
    listCollections: () => request.get('/vector/collections'),
    collection: (name) => request.get(`/vector/collections/${encodeURIComponent(name)}`),
    pointCount: (name) => request.get(`/vector/collections/${encodeURIComponent(name)}/points/count`),
    createCollection: (name, body) => request.put(`/vector/collections/${encodeURIComponent(name)}`, body),
    deleteCollection: (name) => request.delete(`/vector/collections/${encodeURIComponent(name)}`),
    upsertPoints: (name, points) =>
        request.put(`/vector/collections/${encodeURIComponent(name)}/points`, {points}),
    search: (name, body) => request.post(`/vector/collections/${encodeURIComponent(name)}/points/search`, body),
}

/**
 * 单点读取没有可用接口，所以这里不暴露 GET /collections/{n}/points/{id}：
 * QdrantRestServer 的 extractCollectionName(path, "/points/") 会把后缀按 8 个字符切掉，
 * `/collections/demo/points/p3` 被切成集合名 `demo/p` ⇒ 稳定回 "Collection not found: demo/p"。
 * 实测 8888（经 proxy）与 6334（直连）返回**同一份错误**，即这不是转发问题而是 L3 路由缺陷；
 * 按大需求 004 约束不在本轮改 z-vector 源码，页面上也不给这个入口。
 */

/** 集合名/upsert 的 id 在后端只接受 [A-Za-z0-9_-]，前端提前挡住，别把 404 当"集合不存在"渲染 */
export const NAME_PATTERN = /^[A-Za-z0-9_-]+$/

/** 后端错误体是 {status:"error", code, message}；不透出 message 的话页面只剩一句"Request failed with status code 400" */
export function vectorErrorText(e) {
    const data = e?.response?.data
    if (data && typeof data === 'object') {
        const message = data.message || data.error
        const source = data.source ? ` (${data.source})` : ''
        if (message) return `${message}${source}`
    }
    if (typeof data === 'string' && data.trim()) return data.slice(0, 200)
    return e?.message || String(e)
}

/** 度量/索引/排序是枚举，值域以 z-vector-api 的 DistanceMetric / IndexType 为准（不是 Qdrant 的取值） */
export const METRICS = ['L2', 'IP', 'COSINE', 'HAMMING']
export const INDEX_TYPES = ['FLAT', 'HNSW', 'IVF']

/**
 * score 的语义：z-vector 把所有度量统一成"越小越近"的距离（COSINE 是 1 - cos_sim，IP 已取负），
 * 索引层一律 `sort(comparingDouble(getScore))` 升序。所以命中的第一行是最近的、数字最小的那个 —
 * 列名只能写"距离"，写成"相似度"会把排序读反。
 */
