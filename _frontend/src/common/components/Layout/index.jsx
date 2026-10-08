import { Layout, Menu, Spin } from 'antd'
import { MenuFoldOutlined, MenuUnfoldOutlined } from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useState } from 'react'

const { Header, Sider, Content } = Layout

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
 *   <li>{@code appTitle}：侧栏展开时的标题</li>
 *   <li>{@code appShort}：折叠时的缩写</li>
 *   <li>{@code headerExtra}：头部右侧额外内容（版本 tag / 主题切换 / 用户菜单等）</li>
 *   <li>{@code loading}：侧栏菜单区域是否显示 loading 占位</li>
 * </ul>
 */
export function AppLayout({
                             menuItems = [],
                             appTitle = 'One Company',
                             appShort = 'OC',
                             headerExtra,
                             loading = false,
                         }) {
    const [collapsed, setCollapsed] = useState(false)
    const navigate = useNavigate()
    const location = useLocation()

    const handleMenuClick = ({ key }) => {
        navigate(key)
    }

    return (
        <Layout style={{ minHeight: '100vh' }}>
            <Sider trigger={null} collapsible collapsed={collapsed}>
                <div style={{
                    height: 64, display: 'flex', alignItems: 'center', justifyContent: 'center',
                    color: 'white', fontSize: collapsed ? 14 : 18, fontWeight: 'bold',
                }}>
                    {collapsed ? appShort : appTitle}
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

