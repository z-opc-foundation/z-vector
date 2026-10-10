import { useCallback, useEffect, useState } from 'react'
import { Alert, Button, Card, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Tag } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { INDEX_TYPES, METRICS, NAME_PATTERN, vectorApi, vectorErrorText } from '../services/api.js'
import { EmptyState, PageHeader } from '@yuku123/z-vector-component'

/**
 * 集合列表 —— GET /collections 只回名字，维度/点数/索引要逐个集合再打一次 GET /collections/{n}，
 * 所以这里是"先列表再并发取详情"。取详情失败的行单独标错，不让整张表变空。
 */
export default function CollectionList() {
    const navigate = useNavigate()
    const [rows, setRows] = useState([])
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)
    const [filter, setFilter] = useState('')
    const [creating, setCreating] = useState(false)
    const [submitting, setSubmitting] = useState(false)
    const [form] = Form.useForm()

    const fetch = useCallback(async () => {
        setLoading(true)
        try {
            const list = await vectorApi.listCollections()
            const names = list?.collections
            if (!Array.isArray(names)) {
                throw new Error(`collections 字段不是数组，拿到的是 ${JSON.stringify(list)}`)
            }
            const details = await Promise.all(names.map(async (name) => {
                try {
                    return { name, ...(await vectorApi.collection(name)), detailError: null }
                } catch (e) {
                    return { name, detailError: vectorErrorText(e) }
                }
            }))
            setRows(details)
            setError(null)
        } catch (e) {
            setRows([])
            setError(e)
        } finally {
            setLoading(false)
        }
    }, [])

    useEffect(() => {
        fetch()
    }, [fetch])

    const onSubmit = async () => {
        const values = await form.validateFields()
        setSubmitting(true)
        try {
            await vectorApi.createCollection(values.name, {
                dimension: values.dimension,
                metric: values.metric,
                index_type: values.index_type,
            })
            setCreating(false)
            form.resetFields()
            await fetch()
        } catch (e) {
            // 创建失败留在弹窗里，关掉会把原因一起丢掉
            Modal.error({ title: '集合创建失败', content: vectorErrorText(e) })
        } finally {
            setSubmitting(false)
        }
    }

    const onDelete = async (name) => {
        try {
            await vectorApi.deleteCollection(name)
            await fetch()
        } catch (e) {
            Modal.error({ title: `删除集合 ${name} 失败`, content: vectorErrorText(e) })
        }
    }

    return (
        <div>
            <Card
                extra={
                    <Space>
                        <Input.Search
                            allowClear
                            placeholder="按集合名过滤"
                            style={{width: 220}}
                            onChange={(e) => setFilter(e.target.value.trim().toLowerCase())}
                        />
                        <Button icon={<ReloadOutlined />} loading={loading} onClick={fetch}>刷新</Button>
                        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>新建集合</Button>
                    </Space>
                }
            >
                {error ? (
                    <Alert type="error" showIcon
                           message={`集合列表读取失败：${vectorErrorText(error)}`}
                           description="后端没在 6333 端口 bind 成功（不是「没有集合」）。先确认 z-vector-server 已起，或去「实例状态」页看 port / uptime。"/>
                ) : (
                    <Table
                        size="small"
                        rowKey={(r) => r.name}
                        loading={loading}
                        dataSource={rows.filter((r) => !filter || r.name.toLowerCase().includes(filter))}
                        pagination={{
                            pageSize: 20,
                            showSizeChanger: true,
                            pageSizeOptions: ['10', '20', '50', '100'],
                            showTotal: (t) => `共 ${t} 条`,
                        }}
                        columns={[
                            {
                                title: '集合名', dataIndex: 'name', key: 'name',
                                render: (v, r) => r.detailError
                                    ? <span>{v} <Tag color="warning">详情失败</Tag></span>
                                    : <a onClick={() => navigate(`/z-vector/collections/${encodeURIComponent(v)}`)}>{v}</a>
                            },
                            {
                                title: '维度', dataIndex: 'dimension', key: 'dimension', width: 90,
                                render: (v, r) => r.detailError ? <Tip text={r.detailError}>-</Tip> : (v ?? '-')
                            },
                            { title: '距离度量', dataIndex: 'metric', key: 'metric', width: 120, render: (v) => v ? <Tag>{v}</Tag> : '-' },
                            { title: '索引类型', dataIndex: 'index_type', key: 'index_type', width: 120, render: (v) => v || '-' },
                            { title: '向量数', dataIndex: 'points_count', key: 'points_count', width: 100, render: (v) => v ?? '-' },
                            {
                                title: '已建索引', dataIndex: 'indexed', key: 'indexed', width: 100,
                                render: (v) => (v === true ? <Tag color="success">是</Tag> : v === false ? <Tag>否</Tag> : '-')
                            },
                            {
                                title: '操作', key: 'action', width: 190,
                                render: (_, r) => (
                                    <Space size={0}>
                                        <Button type="link" size="small"
                                                onClick={() => navigate(`/z-vector/collections/${encodeURIComponent(r.name)}`)}>
                                            详情
                                        </Button>
                                        <Button type="link" size="small"
                                                onClick={() => navigate(`/z-vector/search?collection=${encodeURIComponent(r.name)}`)}>
                                            检索
                                        </Button>
                                        <Popconfirm title={`删除集合 ${r.name}？`}
                                                    description="集合内所有向量一并丢弃"
                                                    onConfirm={() => onDelete(r.name)}>
                                            <Button type="link" size="small" danger>删除</Button>
                                        </Popconfirm>
                                    </Space>
                                )
                            },
                        ]}
                        locale={{
                            emptyText: <EmptyState title="当前没有向量集合"
                                                   description="GET /collections 返回 collections: [] —— 接口是通的，server 里确实还没有集合。点「新建集合」建一个。" />
                        }}
                    />
                )}
            </Card>

            <Modal title="新建向量集合" open={creating} confirmLoading={submitting}
                   okText="创建" cancelText="取消"
                   onOk={onSubmit}
                   onCancel={() => { setCreating(false); form.resetFields() }}>
                <Form form={form} layout="vertical"
                      initialValues={{ dimension: 4, metric: 'COSINE', index_type: 'FLAT' }}>
                    <Form.Item name="name" label="集合名"
                               rules={[
                                   { required: true, message: '请输入集合名' },
                                   { pattern: NAME_PATTERN, message: '只允许字母、数字、下划线和短横线（后端路由按这个正则匹配）' },
                               ]}>
                        <Input placeholder="例如 demo_docs" />
                    </Form.Item>
                    <Form.Item name="dimension" label="向量维度"
                               rules={[{ required: true, message: '请输入维度' }]}
                               extra="查询向量长度必须与之相等，否则后端报维度校验错误。服务端对缺省 dimension 退 128；前端必填，避免意外建出 128 维集合。">
                        <InputNumber min={1} max={8192} style={{ width: '100%' }} />
                    </Form.Item>
                    <Form.Item name="metric" label="距离度量" extra="取值来自 z-vector 的 DistanceMetric 枚举">
                        <Select options={METRICS.map((v) => ({ value: v, label: v }))} />
                    </Form.Item>
                    <Form.Item name="index_type" label="索引类型" extra="取值来自 z-vector 的 IndexType 枚举">
                        <Select options={INDEX_TYPES.map((v) => ({ value: v, label: v }))} />
                    </Form.Item>
                </Form>
            </Modal>
        </div>
    )
}

function Tip({ text, children }) {
    return <span title={text}>{children}</span>
}
