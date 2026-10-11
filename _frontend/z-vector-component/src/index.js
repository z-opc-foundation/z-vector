/**
 * z-vector-component 桶导出（lead 005 §9.1 独立节点）。
 *
 * 全部 props-only 零 fetch（§9.3）：
 * - ui/*：页面原语（EmptyState / PagedTable / tokens ...）
 * - AppLayout 走 lead 008 §6 单一真源 _shared/z-frontend-common-local/dist/，
 *   suit 直接 import _shared/，本仓不再保留副本（2026-10-11 收口）。
 */

export * from './ui/index.js'
export { default as LoginPage } from './pages/LoginPage.jsx'

// §8.7 域目录清退：vector 域 App 挂载点
export { default as VectorApp } from './pages/VectorApp.jsx'

// API 装配：宿主（suit / 主壳 domainRoutes）启动时调一次 configureVector('/api' 或其他)，
// 让所有 vectorApi 的相对路径都拼到同一前缀，与 vite proxy / nginx 反代同源。
export { configureVector, vectorApi, vectorErrorText, NAME_PATTERN, METRICS, INDEX_TYPES } from './services/api.js'
