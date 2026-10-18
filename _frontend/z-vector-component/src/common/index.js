import axios from 'axios'

/**
 * z-vector-component 内置的最小 request helper。
 *
 * 为什么这里又有 axios 包装：原仓库 `src/services/api.js` 顶部 `import request from '@yuku123/z-frontend-common'`
 * 假设每个组件层/壳层各自在 `@/common` 下挂一个统一的 axios 实例。
 * suit 那边的 vite.config.mjs 把 `@` 指到 `src/`，suit 自带 `src/common/` 下的 request 即可。
 * 但 component 是 library 模式独立 build 的，必须自带一份，否则 rollup 在 vite build 阶段
 * 直接 unresolved import 报错。
 *
 * 与 suit-side 的差异：这里 baseURL 为空，所有路径都是相对路径（如 /collections）。
 * 实际走的是 vite proxy `/api/collections` → 6333/collections（在 suit vite.config.mjs 里配置），
 * 因此 baseURL 必须为空，否则 axios 会拼成 `http://localhost:3001/collections` 这种相对路径发不出去。
 */
export const request = axios.create({
    baseURL: '',
    timeout: 30000,
})

request.interceptors.response.use(
    (r) => r,
    (err) => {
        const data = err?.response?.data
        if (data && typeof data === 'object' && (data.message || data.error)) {
            err.message = `${data.message || data.error}${data.source ? ' (' + data.source + ')' : ''}`
        }
        return Promise.reject(err)
    }
)

export default request