import { Card, Col, Row, Space, Tag, Typography } from 'antd'
import {
    CloudServerOutlined,
    DatabaseOutlined,
    ReadOutlined,
    ThunderboltOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { useEffect, useState } from 'react'
import { vectorApi } from '../services/api.js'

const { Title, Paragraph } = Typography

/**
 * 首页（lead 008 §16）—— 登录后的着陆页。
 *
 * 极简版：欢迎语 + 当前用户 + 4 张快捷入口卡（集合列表 / 检索调试 / 实例状态 / 组件文档）。
 * 每张卡：图标 + 标题 + 短描述，点击 → navigate 到对应页面。
 *
 * 后续可在"数据概览"位加 4 个 Statistic（集合数 / 总点数 / 端口 / 运行时间），从 /__instance + /collections 取实时数据。
 */
export default function HomePage() {
    const navigate = useNavigate()
    const [user, setUser] = useState(null)
    const [stats, setStats] = useState({ collections: 0, port: 6333, version: '1.0.5' })

    useEffect(() => {
        const raw = localStorage.getItem('userInfo')
        if (raw) {
            try { setUser(JSON.parse(raw)) } catch { setUser({ name: raw }) }
        }
        // 拉取实例自省（不阻塞首屏）
        vectorApi.instance().then((d) => {
            if (d) setStats((s) => ({
                ...s,
                collections: d.collections || 0,
                port: d.port || s.port,
                version: d.version || s.version,
            }))
        }).catch(() => {})
    }, [])

    const cards = [
        { key: '/z-vector/collections', icon: <DatabaseOutlined />,    color: '#7c3aed', title: '集合列表', desc: '新建 / 浏览 / 删除集合' },
        { key: '/z-vector/search',      icon: <ThunderboltOutlined />, color: '#0ea5e9', title: '检索调试', desc: 'top-k ANN 查询与命中预览' },
        { key: '/z-vector/instance',    icon: <CloudServerOutlined />, color: '#10b981', title: '实例状态', desc: 'port / uptime / jvm / 内存' },
        { key: '/z-vector/docs',        icon: <ReadOutlined />,        color: '#f59e0b', title: '组件文档', desc: 'DOC.md 渲染的组件能力说明' },
    ]

    return (
        <div>
            <Card style={{ marginBottom: 16, background: 'linear-gradient(135deg, #7c3aed 0%, #6d28d9 100%)', border: 'none' }}>
                <Space direction="vertical" size={4} style={{ color: '#fff' }}>
                    <Title level={3} style={{ color: '#fff', margin: 0 }}>
                        欢迎{user?.name ? `，${user.name}` : ''}
                    </Title>
                    <Paragraph style={{ color: 'rgba(255,255,255,0.85)', margin: 0 }}>
                        z-vector 管理台 · v{stats.version} · z-vector-server 监听 :{stats.port}
                    </Paragraph>
                    <Space size={6} style={{ marginTop: 8 }}>
                        <Tag color="purple" style={{ margin: 0 }}>共 {stats.collections} 个集合</Tag>
                        {user?.role && <Tag style={{ margin: 0, background: 'rgba(255,255,255,0.2)', color: '#fff', border: 'none' }}>{user.role}</Tag>}
                    </Space>
                </Space>
            </Card>

            <Row gutter={[16, 16]}>
                {cards.map((c) => (
                    <Col key={c.key} xs={24} sm={12} md={12} lg={6}>
                        <Card hoverable onClick={() => navigate(c.key)}
                              style={{ borderTop: `3px solid ${c.color}` }}>
                            <Space align="start" size={12}>
                                <div style={{
                                    width: 44, height: 44, borderRadius: 8,
                                    background: `${c.color}15`,
                                    color: c.color,
                                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                                    fontSize: 22, flexShrink: 0,
                                }}>{c.icon}</div>
                                <div style={{ minWidth: 0 }}>
                                    <div style={{ fontSize: 15, fontWeight: 600, color: '#0f172a' }}>{c.title}</div>
                                    <div style={{ fontSize: 12, color: '#64748b', marginTop: 2 }}>{c.desc}</div>
                                </div>
                            </Space>
                        </Card>
                    </Col>
                ))}
            </Row>
        </div>
    )
}
