import { useCallback, useEffect, useState } from 'react'
import { Alert, Button, Card, Descriptions, Space, Tag } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { vectorApi, vectorErrorText } from '../services/api.js'
import { PageHeader } from '@yuku123/z-vector-component'

/**
 * 实例状态 —— GET /__instance 的可视化。
 *
 * 与 z-opc 版差异：z-opc 当时挂在 z-opc 主 JVM 内的 QdrantRestServer，需要判断
 * "embeddedRunning / lifecycleBean / acceptingNow" 三件事；z-vector-server 是独立
 * 进程，自检就是要看 "server 本身" —— 走 /__instance 这一条路，字段全在那。
 *
 * 这一页存在的理由是同一个：让用户一眼区分"server 真在跑"和"vite 起来了但 z-vector
 * 没绑到 6333"这两件事。
 */
export default function InstanceStatus() {
    const [data, setData] = useState(null)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)

    const fetch = useCallback(async () => {
        setLoading(true)
        try {
            setData(await vectorApi.instance())
            setError(null)
        } catch (e) {
            setData(null)
            setError(e)
        } finally {
            setLoading(false)
        }
    }, [])

    useEffect(() => {
        fetch()
    }, [fetch])

    const memFmt = (bytes) => {
        if (bytes == null) return '-'
        const mb = bytes / (1024 * 1024)
        if (mb < 1024) return `${mb.toFixed(1)} MB`
        return `${(mb / 1024).toFixed(2)} GB`
    }

    return (
        <div>
            <PageHeader title="实例与端口"
                        subtitle="z-vector-server 自检接口 GET /__instance —— 字段全在这一条，不依赖 /actuator/mappings" />
            <Card extra={
                <Space>
                    <Button icon={<ReloadOutlined />} loading={loading} onClick={fetch}>刷新</Button>
                </Space>
            }>
                {error ? (
                    <Alert type="error" showIcon
                           message={`自省接口调用失败：${vectorErrorText(error)}`}
                           description="请求没到 server。先确认 z-vector-server 已起（ZVECTOR_PORT=6333），或检查 vite proxy / nginx 转发。" />
                ) : (
                    <Descriptions bordered size="small" column={2}
                                  items={[
                                      { key: 'version', label: '版本', children: <code>{data?.version ?? '-'}</code> },
                                      { key: 'port', label: '监听端口', children: <code>{data?.port ?? '-'}</code> },
                                      {
                                          key: 'uptime', label: '运行时长',
                                          children: typeof data?.uptime_ms === 'number'
                                              ? `${(data.uptime_ms / 1000).toFixed(1)} s`
                                              : '-'
                                      },
                                      { key: 'data_dir', label: '数据目录', children: <code>{data?.data_dir ?? '-'}</code> },
                                      { key: 'jvm', label: '应答的 JVM (pid@host)', children: <code>{data?.jvm ?? '-'}</code> },
                                      { key: 'java', label: 'Java 版本', children: data?.java ?? '-' },
                                      {
                                          key: 'collections', label: '集合数',
                                          children: <Tag color="blue">{data?.collections ?? '-'}</Tag>
                                      },
                                      {
                                          key: 'points_total', label: '总点数（Σ points/count）',
                                          children: <Tag color="blue">{data?.points_total ?? '-'}</Tag>
                                      },
                                      {
                                          key: 'heap', label: '堆内存 (used / max / free)',
                                          children: data?.memory ? (
                                              <code>
                                                  {memFmt(data.memory.heap_used)} / {memFmt(data.memory.heap_max)} / {memFmt(data.memory.free)}
                                              </code>
                                          ) : '-'
                                      },
                                  ]} />
                )}
            </Card>
        </div>
    )
}
