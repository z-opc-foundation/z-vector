import { Component, useEffect, useState } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AppLayout, EmptyState, LoginPage } from '@yuku123/z-vector-component'
// 2026-10-09 pages 归位（lead 005 §9.3）：页面+api 迁 component 的 ./pages entry，
// suit 只留壳——从 '@yuku123/z-vector-component/pages' 读 manifest。
import { menuConfig, routeTable, appMeta } from '@yuku123/z-vector-component/pages'

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
            description="路由表里没有这条路径。回到 z-vector 首页：左侧菜单「首页」。"
            actionText="去首页"
            onAction={() => { window.location.href = '/z-vector/home' }}
        />
    )
}

// lead 008 §16：短期 hard-coded 登录（admin / 123456），待 SSO 接入前使用
const MOCK_AUTH = { username: 'admin', password: '123456', user: { name: 'admin', role: '管理员' } }

function LoginRoute() {
    return (
        <LoginPage
            title="z-vector 管理台"
            mockAuth={MOCK_AUTH}
            redirectUrl="/z-vector/home"
        />
    )
}

function ProtectedShell() {
    const [user, setUser] = useState(null)
    const [ready, setReady] = useState(false)

    useEffect(() => {
        const token = localStorage.getItem('token')
        if (!token) {
            window.location.replace('/z-vector/login')
            return
        }
        const raw = localStorage.getItem('userInfo')
        if (raw) {
            try { setUser(JSON.parse(raw)) } catch { setUser({ name: raw }) }
        }
        setReady(true)
    }, [])

    if (!ready) return null

    return (
        <AppLayout
            menuItems={menuConfig}
            appTitle={appMeta.title}
            appShort={appMeta.short}
            appVersion="1.0.5"
            appUser={user}
            appIcon={{ icon: <img src="/icon.png" alt={appMeta.title} style={{ width: '100%', height: '100%', objectFit: 'cover', borderRadius: 8 }} />, color: '#7c3aed', label: appMeta.title }}
        />
    )
}

/**
 * 通用左菜单架子 AdminShell 的组装点。
 * <ul>
 *   <li>菜单 + 路由表全部数据驱动（来自 menuConfig / routeTable）</li>
 *   <li>App.jsx 本身零业务：换 z-* 仓只要改 manifest 即可，App.jsx 不动</li>
 *   <li>登录 + 鉴权 + user 区域全部走 lead 008 §16 模板</li>
 * </ul>
 */
export default function App() {
    return (
        <ErrorBoundary>
            <BrowserRouter>
                <Routes>
                    <Route path="/z-vector/login" element={<LoginRoute />} />
                    <Route element={<ProtectedShell />}>
                        <Route path="/" element={<Navigate to={menuConfig[0].key} replace />} />
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
