/**
 * z-vector-component 桶导出（lead 005 §9.1 独立节点）。
 *
 * 全部 props-only 零 fetch（§9.3）：
 * - ui/*：页面原语（EmptyState / PagedTable / tokens ...）
 * - layout：AdminShell 通用左菜单架子（接 menuItems / appTitle props）
 */

export * from './ui/index.js'
export { AppLayout } from './layout/index.jsx'
