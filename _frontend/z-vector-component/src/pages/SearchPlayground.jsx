import { useEffect, useMemo, useState } from 'react'
import { Alert, Button, Card, Checkbox, Empty, Input, InputNumber, Select, Space, Table, Tag } from 'antd'
import { ThunderboltOutlined } from '@ant-design/icons'
import { useSearchParams } from 'react-router-dom'
import { vectorApi, vectorErrorText } from '../services/api.js'
import { EmptyState, PageHeader } from '@yuku123/z-vector-component'

/**
 * 向量检索 —— 真发一次 POST /collections/{n}/points/search，把后端给的 result 原样渲染。
 *
 * 三条不造假的规定：
 *   1. 命中表只读 response.result，前端不做二次排序、不算相似度（score 是 z-vector 自己给的距离值，
 *      语义是"越小越近"，所以列名叫"距离"，第一行才是最近邻）；
 *   2. 0 命中就渲染空表 + 后端原始 JSON，不拿随机数凑几条出来；
 *   3. 查询向量要么用户自己填，要么点"随机生成"（明确是随机的，用来验证链路通不通）。
 */
export default function SearchPlayground() {
    const [params, setParams] = useSearchParams()
    const [collections, setCollections] = useState([])
    const [collection, setCollection] = useState(params.get('collection') || '')
    const [dimension, setDimension] = useState(null)
    const [vectorText, setVectorText] = useState('')
    const [topK, setTopK] = useState(5)
    const [filterText, setFilterText] = useState('')
    const [buildIndex, setBuildIndex] = useState(false)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)
    const [response, setResponse] = useState(null)
    const [lastRequest, setLastRequest] = useState(null)

    const loadCollections = async () => {
        try {
            const list = await vectorApi.listCollections()
            const names = list?.collections || []
            setCollections(names)
            if (!collection && names.length) {
                setCollection(names[0])
            }
            setError(null)
        } catch (e) {
            setCollections([])
            setError(e)
        }
    }

    useEffect(() => {
        loadCollections()
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    // 换集合就把上一次的结果和查询向量清掉：留着会看起来像"新集合也返回了这几条"
    useEffect(() => {
        setVectorText('')
        setResponse(null)
        setError(null)
        setLastRequest(null)
        if (!collection) {
            setDimension(null)
            return
        }
        setParams({ collection })
        vectorApi.collection(collection)
            .then((m) => setDimension(m?.dimension ?? null))
            .catch(() => setDimension(null))
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [collection])

    const randomVector = () => {
        const d = dimension || 4
        setVectorText(JSON.stringify(
            Array.from({ length: d }, () => Number((Math.random() * 2 - 1).toFixed(4)))))
    }

    const run = async () => {
        let vector
        try {
            vector = JSON.parse(vectorText)
        } catch {
            setError(new Error('查询向量不是合法 JSON 数组'))
            return
        }
        if (!Array.isArray(vector) || vector.length === 0 || vector.some((v) => typeof v !== 'number')) {
            setError(new Error('查询向量必须是形如 [0.1, 0.2, …] 的数字数组'))
            return
        }
        if (dimension && vector.length !== dimension) {
            setError(new Error(`维度不匹配：集合 ${collection} 是 ${dimension} 维，查询向量给了 ${vector.length} 维`))
            return
        }
        const body = { vector, limit: topK, build_index: buildIndex }
        if (filterText.trim()) {
            try {
                body.filter = JSON.parse(filterText)
            } catch {
                setError(new Error('filter 不是合法 JSON，例如 {"lang": "zh"}'))
                return
            }
        }
        setLoading(true)
        setLastRequest({ collection, ...body })
        try {
            const resp = await vectorApi.search(collection, body)
            setResponse(resp)
            setError(null)
        } catch (e) {
            setResponse(null)
            setError(e)
        } finally {
            setLoading(false)
        }
    }

    const hits = Array.isArray(response?.result) ? response.result : []
    const sample = useMemo(() => JSON.stringify({ request: lastRequest, response }, null, 2), [response, lastRequest])

    return (
        <div>
            <PageHeader title="向量检索"
                        subtitle="top-k ANN 查询（POST /collections/{name}/points/search；time_ms 由服务端真实测量，不是占位 0）" />
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
                <Card title="查询条件" size="small">
                    <Space direction="vertical" style={{ width: '100%' }} size={10}>
                        <Space.Compact style={{ width: '100%' }}>
                            <Select style={{ width: '100%' }}
                                    placeholder="选择集合"
                                    loading={!collections.length && !error}
                                    value={collection || undefined}
                                    onChange={setCollection}
                                    options={collections.map((c) => ({ value: c, label: c }))}
                                    notFoundContent={<Empty description="没有集合，先去「向量集合」页建一个" />} />
                            <Button onClick={loadCollections}>刷新</Button>
                        </Space.Compact>
                        <div>
                            <span style={{ color: '#64748b', fontSize: 12 }}>
                                集合维度：{dimension ?? '未取到（集合还没选或详情读取失败）'}
                            </span>
                            <Button size="small" style={{ marginLeft: 8 }} onClick={randomVector}
                                    disabled={!collection}>随机生成查询向量</Button>
                        </div>
                        <Input.TextArea rows={5} spellCheck={false}
                                        value={vectorText}
                                        onChange={(e) => setVectorText(e.target.value)}
                                        placeholder='[0.1, 0.2, 0.3, 0.4]' />
                        <Space>
                            <span>top-k</span>
                            <InputNumber min={1} max={100} value={topK} onChange={(v) => setTopK(v ?? 10)} />
                            <Checkbox checked={buildIndex} onChange={(e) => setBuildIndex(e.target.checked)}>
                                查询前建索引
                            </Checkbox>
                        </Space>
                        <Input spellCheck={false} value={filterText}
                               onChange={(e) => setFilterText(e.target.value)}
                               placeholder='可选 payload 过滤，如 {"lang":"zh"}' />
                        <Button type="primary" icon={<ThunderboltOutlined />} loading={loading}
                                disabled={!collection || !vectorText.trim()} onClick={run}>
                            执行检索
                        </Button>
                    </Space>
                </Card>
                <Card title="命中结果" size="small"
                      extra={
                          response
                              ? <Space size={6}>
                                  <Tag>{`result ${hits.length} 条`}</Tag>
                                  {typeof response.time_ms === 'number' &&
                                      <Tag color="blue">{`耗时 ${response.time_ms} ms`}</Tag>}
                                </Space>
                              : null
                      }>
                    {error ? (
                        <Alert type="error" showIcon message={`检索失败：${vectorErrorText(error)}`}
                               description="后端把 VectorException 统一转成 400 + message，这里的文案就是它原话。" />
                    ) : !response ? (
                        <EmptyState title="还没有发起过检索"
                                    description="选集合 → 填查询向量（或用「随机生成」）→ 执行检索。结果表在后端返回前一直是空的，不会先摆几行占位。" />
                    ) : (
                        <Table size="small"
                               rowKey={(r, i) => `${r.id}-${i}`}
                               loading={loading}
                               dataSource={hits}
                               pagination={false}
                               columns={[
                                   { title: '排名', key: 'rank', width: 60, render: (_, __, i) => i + 1 },
                                   { title: '向量 ID', dataIndex: 'id', key: 'id' },
                                   {
                                       title: '距离 (score)', dataIndex: 'score', key: 'score', width: 140,
                                           // z-vector 内部统一成"越小越近"，这里只格式化不改值
                                           render: (v) => (typeof v === 'number' ? v.toFixed(6) : String(v))
                                       },
                                       {
                                           title: 'payload', dataIndex: 'payload', key: 'payload',
                                           render: (v) => (v ? <code style={{ fontSize: 12 }}>{JSON.stringify(v)}</code>
                                               : <span style={{ color: '#94a3b8' }}>后端未回 payload 字段</span>)
                                       },
                                   ]}
                                   locale={{
                                       emptyText: <EmptyState title="命中 0 条"
                                                              description="接口正常返回 result: [] —— 集合里没有可比对的向量。下方原始响应可以核对这不是前端吞了数据。" />
                                   }}
                            />
                        )}
                </Card>
                <Card title="原始请求 / 响应（证明页面渲染的就是后端给的那份）" size="small">
                    <pre style={{ margin: 0, maxHeight: 260, overflow: 'auto', fontSize: 12 }}>
                        {response || lastRequest ? sample : '（还没有请求）'}
                    </pre>
                </Card>
            </Space>
        </div>
    )
}
