/**
 * 通用 UI 组件库 - 阶段一桶导出。
 *
 * feature001 阶段一不搬 EChartsChart/FeatureCard/GradientStatCard/DarkBanner/CategoryDot
 * 五个组件 —— 它们拉 echarts 重型依赖，console 暂时用不到。
 * 后续真用图表需求再补，依赖单独讨论。
 *
 * 视觉 token 全部从 tokens.js 单一来源导出，ConfigProvider 在 main.jsx 里直接消费 antdTheme。
 */

export { default as EmptyState } from './EmptyState.jsx'
export { default as ErrorState } from './ErrorState.jsx'
export { default as LoadingState } from './LoadingState.jsx'
export { default as SearchInput } from './SearchInput.jsx'
export { default as PageHeader } from './PageHeader.jsx'
export { default as PagedTable } from './PagedTable.jsx'
export { default as SectionHeader, CategoryDot } from './SectionHeader.jsx'
export { default as StatusBadge } from './StatusBadge.jsx'
export { default as TableToolbar, RefreshButton } from './TableToolbar.jsx'

export { brand, palette, neutral, radius, shadow, lift, space, fontSize, antdTheme } from './tokens.js'
