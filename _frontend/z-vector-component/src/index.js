/**
 * z-vector-component 桶导出（lead 005 §9.1 独立节点）。
 *
 * 全部 props-only 零 fetch（§9.3）：
 * - ui/*：页面原语（EmptyState / PagedTable / tokens ...）
 * - layout：AdminShell 通用左菜单架子（接 menuItems / appTitle props）
 */

export * from './ui/index.js'
export { AppLayout } from './layout/index.jsx'
export { default as LoginPage } from './pages/LoginPage.jsx'

// §8.7 域目录清退：vector 域 App 挂载点
export { default as VectorApp } from './pages/VectorApp.jsx'
