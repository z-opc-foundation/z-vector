import axios from 'axios'

/**
 * z-vector 前端的 axios 实例。
 * <p>
 * feature001 阶段一从 z-opc common/utils/request.ts 1:1 改编，砍掉所有跟登录/多租户/防循环跳转
 * 相关的钩子：z-vector 当前是单机管理台，没有用户系统，没有 /login 页面，没有 tenant 注入。
 * 保留的骨架只有三件事：
 * <ul>
 *   <li>默认 baseURL = 空串：所有路径写全（如 /collections），与 vite proxy / nginx proxy_pass 配合</li>
 *   <li>响应拦截器只做错误归一化（HTTP code + 服务端 message），不打 console.warn 噪音</li>
 *   <li>超时 10 秒：与 z-opc 同</li>
 * </ul>
 * 任何一条错误体形如 {@code {status:"error", code:404, message:"Collection not found: …"}}：
 * 业务侧用 {@code e.response.data.message} 取文案（axios 默认错误对象不展开 body）。
 */
function createRequest(options = {}) {
    const instance = axios.create({
        baseURL: options.baseURL ?? '',
        timeout: options.timeout ?? 10000,
        withCredentials: false, // 同源 vite proxy / nginx proxy_pass，不需要 Cookie 跨域
    })

    instance.interceptors.response.use(
        (response) => response.data,
        (error) => {
            // 不在拦截器里 console.warn：所有错误由调用方决定要不要打日志，
            // 这里只是 reject 不丢失 axios 错误对象（response.data.message 在 axios 默认错误对象上仍可读）。
            return Promise.reject(error)
        }
    )

    return instance
}

const request = createRequest()

export { createRequest }
export default request
