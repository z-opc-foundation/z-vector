import {ComponentDoc} from '@yuku123/z-frontend-common/ui'
import docMarkdown from '../DOC.md?raw'

/**
 * 组件文档页（lead 008 §7）—— 文档内容来自本包 src/DOC.md。
 *
 * 为什么用 `?raw`：vite 编译期把 DOC.md 内联成字符串，零额外请求；
 * 文档随包发布，宿主不用管文件怎么放。
 *
 * 为什么不再本地重写 TOC / 两栏 / 滚动联动：这套逻辑在 25 个仓里逐字重复，
 * 基座 `@yuku123/z-frontend-common/ui` 的 ComponentDoc 已经是成品件
 * （240px TOC + 两栏 + 零依赖 markdown 引擎 + 滚动联动）。
 * 本仓原先那份 270 行重写已删除，改成这个薄包装 —— 单一真源。
 */
export default function ComponentDocPage() {
    return <ComponentDoc markdown={docMarkdown} title="组件文档 — z-vector" />
}
