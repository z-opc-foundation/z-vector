import axios from 'axios'

// lead 005 §9.3 终版：component 自洽——自带本域接口调用，前缀参数注入。
// 默认 '/api'（vite proxy / nginx 反代同源），宿主换前缀时 configureVector({ apiBase })。
let apiBase = '/api'

export function configureVector({ apiBase: base } = {}) {
    if (base !== undefined) apiBase = base
}

// 轻实例：unwrap response.data + 10s 超时（原 suit request.js 语义原样带走）
const request = axios.create({ timeout: 10000, withCredentials: false })
request.interceptors.response.use(
    (response) => response.data,
    (error) => Promise.reject(error),
)

/**
 * z-vector 管理面数据源（feature001 阶段一）。
 *
 * 与 z-opc src/vector/services/api.js 的差异：
 * <ul>
 *   <li>路径加 /api/ 前缀 —— vite dev proxy（/api/... → :6333/...）与浏览器 SPA 路由
 *       （/collections, /search, /instance, /health）完全隔离。否则浏览器刷新 /collections/demo
 *       时 proxy 会把 SPA 路径当后端调用转发，React 永远拿不到 index.html。</li>
 *   <li>错误体形状与 z-opc 实测一致：{status:"error", code, message}，HTTP code 与 code 同值。</li>
 * </ul>
 *
 * 字段映射（来自 z-opc 实测：response 是 Qdrant 风格）：
 * <pre>
 *   GET    /api/collections                  → {status:"ok", collections:["name", …]}
 *   GET    /api/collections/{n}              → {name, dimension, metric, index_type, points_count, indexed}
 *   GET    /api/collections/{n}/points/count → {count}
 *   POST   /api/collections/{n}/points/search → {result:[{id, score, payload?}], status, time_ms}
 *   PUT    /api/collections/{n}              → {status:"ok", name, dimension, metric, index_type, index_params}
 *   PUT    /api/collections/{n}/points       → {status:"ok", count}
 *   DELETE /api/collections/{n}              → {status:"ok"} 或 404
 *   GET    /api/collections/{n}/points/{id}  → {id, vector, payload} 或 404
 *   DELETE /api/collections/{n}/points/{id}  → {status:"ok"} 或 404
 *   GET    /api/__instance                   → {version, port, uptime_ms, data_dir, jvm, java, collections, points_total, memory}
 * </pre>
 */
const api = (path) => `${apiBase}${path}`

export const vectorApi = {
    instance:           ()                  => request.get(api('/__instance')),
    listCollections:    ()                  => request.get(api('/collections')),
    collection:         (name)              => request.get(api(`/collections/${encodeURIComponent(name)}`)),
    pointCount:         (name)              => request.get(api(`/collections/${encodeURIComponent(name)}/points/count`)),
    createCollection:   (name, body)        => request.put(api(`/collections/${encodeURIComponent(name)}`), body),
    deleteCollection:   (name)              => request.delete(api(`/collections/${encodeURIComponent(name)}`)),
    upsertPoints:       (name, points)      => request.put(api(`/collections/${encodeURIComponent(name)}/points`), { points }),
    search:             (name, body)        => request.post(api(`/collections/${encodeURIComponent(name)}/points/search`), body),
    getPoint:           (name, id)          => request.get(api(`/collections/${encodeURIComponent(name)}/points/${encodeURIComponent(id)}`)),
    deletePoint:        (name, id)          => request.delete(api(`/collections/${encodeURIComponent(name)}/points/${encodeURIComponent(id)}`)),
}

/**
 * 集合名/upsert 的 id 在后端只接受 [A-Za-z0-9_-]，
 * QdrantRestServer 路由 regex 也是这个集合；前端提前挡住，别把 404 当"集合不存在"渲染。
 */
export const NAME_PATTERN = /^[A-Za-z0-9_-]+$/

/**
 * 错误文案：axios 默认只给 "Request failed with status code 400"，真因在 response.data.message。
 * 不透出 message 的话页面只剩一句"Request failed with status code 400"。
 */
export function vectorErrorText(e) {
    const data = e?.response?.data
    if (data && typeof data === 'object') {
        const message = data.message || data.error
        const source = data.source ? ` (${data.source})` : ''
        if (message) return `${message}${source}`.slice(0, 300)
    }
    if (typeof data === 'string' && data.trim()) return data.slice(0, 200)
    return e?.message || String(e)
}

/** 度量/索引是枚举，值域以 z-vector-api 的 DistanceMetric / IndexType 为准（不是 Qdrant 的取值）。 */
export const METRICS = ['L2', 'IP', 'COSINE', 'HAMMING']
export const INDEX_TYPES = ['FLAT', 'HNSW', 'IVF']

/**
 * score 的语义：z-vector 把所有度量统一成"越小越近"的距离（COSINE 是 1 - cos_sim，IP 已取负），
 * 索引层一律 sort(comparingDouble(getScore)) 升序。所以命中的第一行是最近的、数字最小的那个 —
 * 列名只能写"距离"，写成"相似度"会把排序读反。
 */
