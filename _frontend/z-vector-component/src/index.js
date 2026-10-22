/**
 * z-vector-component 桶导出（lead 005 §9.1 独立节点）。
 *
 * 全部 props-only 零 fetch（§9.3）：
 * - ui primitives 走共享基座 @yuku123/z-frontend-common/ui（唯一真源）。
 *   本仓原先在 src/ui/ 下整份拷贝了一份基座组件（11 个文件），基座改了这边不跟着改，
 *   同一个 EmptyState / PagedTable 会出现两种口径 —— 那份拷贝已删除。
 *   这里**按名逐个转出**而不是 export *：export * 会把基座后续新增的导出
 *   （ListTable / ComponentDoc / GradientStatCard …）也带进本仓的公开 API 面，
 *   那是基座的契约变更，不该由本仓替它发布。
 * - AppLayout 走 z-frontend-common 独立仓（npm @yuku123/z-frontend-common），
 *   suit 直接 import，本仓不再保留副本（2026-10-11 收口）。
 */

export {
    CategoryDot,
    EmptyState,
    ErrorState,
    LoadingState,
    PagedTable,
    PageHeader,
    RefreshButton,
    SearchInput,
    SectionHeader,
    StatusBadge,
    TableToolbar,
} from '@yuku123/z-frontend-common/ui'
export {
    antdTheme,
    brand,
    fontSize,
    lift,
    neutral,
    palette,
    radius,
    shadow,
    space,
} from '@yuku123/z-frontend-common/ui'
export { default as LoginPage } from './pages/LoginPage.jsx'

// 组件文档页（lead 008 §7）：主壳按统一名字取，各仓一致
export { default as ComponentDocPage } from './pages/ComponentDoc.jsx'

// §8.7 域目录清退：vector 域 App 挂载点
export { default as VectorApp } from './pages/VectorApp.jsx'

// API 装配：宿主（suit / 主壳 domainRoutes）启动时调一次 configureVector('/api' 或其他)，
// 让所有 vectorApi 的相对路径都拼到同一前缀，与 vite proxy / nginx 反代同源。
export { configureVector, vectorApi, vectorErrorText, NAME_PATTERN, METRICS, INDEX_TYPES } from './services/api.js'
