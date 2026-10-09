/**
 * JWT 工具 —— 从 z-opc common/utils/jwt.ts 1:1 搬运，逻辑保留但本期不接。
 * <p>
 * z-vector 当前没有登录、没有 token 存储，request.js 也不读 Authorization 头。
 * 这一份是「未来接入登录时直接用」的备件：
 * <ul>
 *   <li>parseJwtClaims(token) —— 解析 base64url claim 段，处理中文乱码</li>
 *   <li>isTokenExpired(token) —— 检查 exp 字段是否过期</li>
 * </ul>
 * 启用时机：阶段一 console 不接，阶段二或后续有用户系统时再读。
 */

function base64UrlDecode(s) {
    let padded = s.replace(/-/g, '+').replace(/_/g, '/')
    while (padded.length % 4 !== 0) padded += '='
    // 中文 / emoji 的 base64url 解码用 TextDecoder 处理 UTF-8 字节
    try {
        const binary = atob(padded)
        const bytes = new Uint8Array(binary.length)
        for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
        return new TextDecoder('utf-8').decode(bytes)
    } catch (e) {
        return null
    }
}

/** 解析 JWT 的 claims（payload 段）。解析失败返 null。 */
export function parseJwtClaims(token) {
    if (!token || typeof token !== 'string') return null
    const parts = token.split('.')
    if (parts.length !== 3) return null
    const json = base64UrlDecode(parts[1])
    if (!json) return null
    try {
        return JSON.parse(json)
    } catch (e) {
        return null
    }
}

/** exp 是 unix 秒；当前秒 ≥ exp 即视为过期。无效 token 也视为过期。 */
export function isTokenExpired(token) {
    const claims = parseJwtClaims(token)
    if (!claims || typeof claims.exp !== 'number') return true
    return Math.floor(Date.now() / 1000) >= claims.exp
}
