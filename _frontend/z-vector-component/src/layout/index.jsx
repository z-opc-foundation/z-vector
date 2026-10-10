import { Layout, Menu, Spin, Tooltip } from 'antd'
import { MenuFoldOutlined, MenuUnfoldOutlined } from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useState } from 'react'

const { Header, Sider, Content } = Layout

function shade(hex, amount = 0.85) {
    const m = /^#?([0-9a-f]{6})$/i.exec(hex || '')
    if (!m) return hex
    const n = parseInt(m[1], 16)
    const r = Math.max(0, Math.min(255, Math.round(((n >> 16) & 0xff) * amount)))
    const g = Math.max(0, Math.min(255, Math.round(((n >> 8) & 0xff) * amount)))
    const b = Math.max(0, Math.min(255, Math.round((n & 0xff) * amount)))
    return '#' + ((r << 16) | (g << 8) | b).toString(16).padStart(6, '0')
}

/**
 * 通用左菜单架子 AdminShell —— 阶段一（feature001）从 z-opc 69 行版 1:1 搬运。
 * <p>
 * 用法：作为 layout Route 的 element 挂进 Routes，内部用 &lt;Outlet /&gt; 渲染子路由匹配到的页面：
 * <pre>{@code
 * <Routes>
 *   <Route element={<AppLayout menuItems={...} appTitle="..." />}>
 *     <Route path="/" element={<Page />} />
 *   </Route>
 * </Routes>
 * }</pre>
 * <p>
 * Props:
 * <ul>
 *   <li>{@code menuItems}：antd Menu items 格式（数组）</li>
 *   <li>{@code appTitle}：侧栏展开时的标题（无 appIcon 时生效）</li>
 *   <li>{@code appShort}：折叠时的缩写（无 appIcon 时生效）</li>
 *   <li>{@code appIcon}：品牌图标 {icon: ReactNode, color?: string, label?: string}，有则完全替代文字</li>
 *   <li>{@code headerExtra}：头部右侧额外内容（版本 tag / 主题切换 / 用户菜单等）</li>
 *   <li>{@code loading}：侧栏菜单区域是否显示 loading 占位</li>
 * </ul>
 */
export function AppLayout({
                             menuItems = [],
                             appTitle = 'One Company',
                             appShort = 'OC',
                             appIcon,
                             headerExtra,
                             loading = false,
                         }) {
    const [collapsed, setCollapsed] = useState(false)
    const navigate = useNavigate()
    const location = useLocation()

    const handleMenuClick = ({ key }) => {
        navigate(key)
    }

    const renderBrand = () => {
        if (collapsed) {
            if (appIcon) {
                const { icon, color = '#7c3aed', label = appTitle } = appIcon
                const node = (
                    <div style={{
                        width: 36, height: 36, borderRadius: 10,
                        background: `linear-gradient(135deg, ${color}, ${shade(color, 0.7)})`,
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        color: '#fff', fontSize: 20, boxShadow: '0 2px 6px rgba(0,0,0,0.25)',
                    }}>{icon}</div>
                )
                return <Tooltip title={label} placement="right">{node}</Tooltip>
            }
            return appShort
        }
        // expanded: text only
        return appTitle
    }

    return (
        <Layout style={{ minHeight: '100vh' }}>
            <Sider trigger={null} collapsible collapsed={collapsed}>
                <div style={{
                    height: 64, display: 'flex', alignItems: 'center', justifyContent: 'center',
                }}>
                    {renderBrand()}
                </div>
                {loading ? (
                    <div style={{ padding: 16, textAlign: 'center' }}>
                        <Spin size="small" />
                    </div>
                ) : (
                    <Menu
                        theme="dark"
                        mode="inline"
                        selectedKeys={[location.pathname]}
                        items={menuItems}
                        onClick={handleMenuClick}
                    />
                )}
            </Sider>
            <Layout>
                <Header style={{
                    padding: '0 16px', background: '#fff',
                    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                }}>
                    <span onClick={() => setCollapsed(!collapsed)} style={{ fontSize: 18, cursor: 'pointer' }}>
                        {collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
                    </span>
                    {headerExtra}
                </Header>
                <Content style={{ margin: 16, padding: 24, background: '#fff', minHeight: 280 }}>
                    <Outlet />
                </Content>
            </Layout>
        </Layout>
    )
}

export default AppLayout

