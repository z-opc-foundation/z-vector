import { Component } from 'react'
import { BrowserRouter, Navigate, Outlet, Route, Routes } from 'react-router-dom'
import { AppLayout } from '@yuku123/z-vector-component'
// 2026-10-09 pages 归位（lead 005 §9.3）：页面+api 迁 component 的 ./pages entry，
// suit 只留壳——从 '@yuku123/z-vector-component/pages' 读 manifest。
import { menuConfig, routeTable, appMeta } from '@yuku123/z-vector-component/pages'
import { EmptyState } from '@yuku123/z-vector-component'

class ErrorBoundary extends Component {
    constructor(props) { super(props); this.state = { err: null } }
    static getDerivedStateFromError(err) { return { err } }
    componentDidCatch(err, info) { console.error('[App ErrorBoundary]', err, info) }
    render() {
        if (this.state.err) return <pre style={{padding: 24, color: 'red', whiteSpace: 'pre-wrap'}}>{String(this.state.err?.stack || this.state.err)}</pre>
        return this.props.children
    }
}

function NotFound() {
    return (
        <EmptyState
            title="页面不存在"
            description="路由表里没有这条路径。回到集合管理开始：左侧菜单「集合管理 → 集合列表」。"
            actionText="去集合列表"
            onAction={() => { window.location.href = '/collections' }}
        />
    )
}

/**
 * 通用左菜单架子 AdminShell 的组装点。
 * <ul>
 *   <li>菜单 + 路由表全部数据驱动（来自 ./console/routes.jsx）</li>
 *   <li>App.jsx 本身零业务：换 z-* 仓只要改 routes.jsx 即可，App.jsx 不动</li>
 *   <li>侧栏折叠、loading 占位、header extra（版本 tag）都在 AdminShell 里完成</li>
 * </ul>
 */
export default function App() {
    return (
        <ErrorBoundary>
            <BrowserRouter>
                <Routes>
                    <Route element={
                        <AppLayout
                            menuItems={menuConfig}
                            appTitle={appMeta.title}
                            appShort={appMeta.short}
                            appVersion="1.0.5"
                            appIcon={{ icon: <img src="/icon.png" alt={appMeta.title} style={{ width: '100%', height: '100%', objectFit: 'cover', borderRadius: 8 }} />, color: '#7c3aed', label: appMeta.title }}
                        />
                    }>
                        <Route path="/" element={<Navigate to="/collections" replace />} />
                        {routeTable.map((r) => (
                            <Route key={r.path} path={r.path} element={r.element} />
                        ))}
                        <Route path="*" element={<NotFound />} />
                    </Route>
                </Routes>
            </BrowserRouter>
        </ErrorBoundary>
    )
}
