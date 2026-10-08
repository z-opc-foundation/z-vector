import { useCallback, useEffect, useState } from 'react'
import { Alert, Button, Card, Col, Descriptions, Input, Row, Space, Statistic, Tag } from 'antd'
import { ArrowLeftOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import { vectorApi, vectorErrorText } from '../services/api.js'
import { PageHeader } from '@/common/components/ui/index.js'

/**
 * 集合详情 —— GET /collections/{n} + /points/count 两次取数。
 *
 * 两个数都取，是因为两者不一致时能直接暴露 store 的计数与集合元信息脱节 —
 * 而不是让页面替后端圆过去。
 */
export default function CollectionDetail() {
    const { name } = useParams()
    const navigate = useNavigate()
    const [meta, setMeta] = useState(null)
    const [count, setCount] = useState(null)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)
    const [pointsText, setPointsText] = useState('')
    const [writeResult, setWriteResult] = useState(null)
    const [writing, setWriting] = useState(false)

    const fetch = useCallback(async () => {
        if (!name) return
        setLoading(true)
        try {
            const [m, c] = await Promise.all([
                vectorApi.collection(name),
                vectorApi.pointCount(name).catch(() => null),
            ])
            setMeta(m)
            setCount(c?.count ?? null)
            setError(null)
        } catch (e) {
            setMeta(null)
            setCount(null)
            setError(e)
        } finally {
            setLoading(false)
        }
    }, [name])

    useEffect(() => {
        fetch()
    }, [fetch])

    const upsert = async () => {
        let parsed
        try {
            parsed = JSON.parse(pointsText)
        } catch {
            setWriteResult({ ok: false, text: '输入不是合法 JSON' })
            return
        }
        const points = Array.isArray(parsed) ? parsed : [parsed]
        const dim = meta?.dimension
        const bad = points.find((p) => !p || typeof p !== 'object'
            || p.id == null || !Array.isArray(p.vector) || p.vector.some((v) => typeof v !== 'number')
            || (dim && p.vector.length !== dim))
        if (bad) {
            setWriteResult({
                ok: false,
                text: dim ? `每条向量要有 id + ${dim} 维数字数组（维度由集合决定），这条不合：${JSON.stringify(bad).slice(0, 120)}`
                    : '每条向量要有 id + vector 数字数组',
            })
            return
        }
        setWriting(true)
        try {
            const resp = await vectorApi.upsertPoints(name, points)
            setWriteResult({ ok: true, text: `后端回执 ${JSON.stringify(resp)}` })
            setPointsText('')
            await fetch()
        } catch (e) {
            setWriteResult({ ok: false, text: vectorErrorText(e) })
        } finally {
            setWriting(false)
        }
    }

    return (
        <div>
            <PageHeader title={`集合详情：${name || '-'}`}
                        subtitle="z-vector 集合的维度 / 度量 / 索引配置与向量规模"
                        extra={<Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/collections')}>返回列表</Button>} />
            {error ? (
                <Card>
                    <Alert type="error" showIcon
                           message={`集合详情读取失败：${vectorErrorText(error)}`}
                           description="404 = 集合不存在（名字来自列表页的话，说明它在期间被删了）；503 = server 没起。"/>
                </Card>
            ) : (
                <Row gutter={12}>
                    <Col span={16}>
                        <Card title="集合元信息" loading={loading}
                              extra={
                                  <Space>
                                      <Button icon={<ReloadOutlined />} loading={loading} onClick={fetch}>刷新</Button>
                                      <Button type="primary"
                                              onClick={() => navigate(`/search?collection=${encodeURIComponent(name)}`)}>
                                          去检索
                                      </Button>
                                  </Space>
                              }>
                            <Descriptions size="small" column={2} bordered
                                          items={[
                                              { key: 'name', label: '集合名', children: meta?.name ?? '-' },
                                              { key: 'dimension', label: '向量维度', children: meta?.dimension ?? '-' },
                                              { key: 'metric', label: '距离度量', children: meta?.metric ? <Tag>{meta.metric}</Tag> : '-' },
                                              { key: 'index_type', label: '索引类型', children: meta?.index_type ? <Tag>{meta.index_type}</Tag> : '-' },
                                              {
                                                  key: 'indexed', label: '索引已构建',
                                                  children: meta?.indexed === true
                                                      ? <Tag color="success">是</Tag>
                                                      : <Tag color="warning">否 — 查询会走未建索引路径</Tag>
                                              },
                                              {
                                                  key: 'index_params', label: '索引参数',
                                                  children: meta?.index_params
                                                      ? <code style={{ fontSize: 12 }}>{JSON.stringify(meta.index_params)}</code>
                                                      : '-'
                                              },
                                          ]} />
                        </Card>
                    </Col>
                    <Col span={8}>
                        <Space direction="vertical" style={{ width: '100%' }}>
                            <Card loading={loading}>
                                <Statistic title="集合声明的向量数 (points_count)" value={meta?.points_count ?? '-'} />
                            </Card>
                            <Card loading={loading}>
                                <Statistic title="count 端点独立查询 (points/count)" value={count ?? '-'} />
                            </Card>
                        </Space>
                    </Col>
                </Row>
            )}
            <Card title="写入向量 (PUT /points)" size="small" style={{ marginTop: 12 }}
                  extra={
                      <Space>
                          <Button size="small" disabled={writing}
                                  onClick={() => setPointsText(
                                      JSON.stringify([{ id: 'p1', vector: Array.from({ length: meta?.dimension || 4 }, (_, i) => (i + 1) / 10), payload: { src: 'manual' } }], null, 2))}>
                              填示例结构
                          </Button>
                          <Button type="primary" size="small" icon={<UploadOutlined />} loading={writing}
                                  disabled={!pointsText.trim()} onClick={upsert}>
                              提交 upsert
                          </Button>
                      </Space>
                  }>
                <Input.TextArea rows={6} spellCheck={false} value={pointsText}
                                onChange={(e) => setPointsText(e.target.value)}
                                placeholder='[{"id":"p1","vector":[0.1,0.2,0.3,0.4],"payload":{"lang":"zh"}}]' />
                {writeResult && (
                    <Alert style={{ marginTop: 10 }} type={writeResult.ok ? 'success' : 'error'} showIcon
                           message={writeResult.ok ? '写入成功' : '写入失败'}
                           description={writeResult.text} />
                )}
            </Card>
        </div>
    )
}
