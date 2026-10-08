import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Table } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { EmptyState, ErrorState, PageHeader } from './index.js'

/**
 * 中间件管理台共用的分页表格。阶段一直接搬 z-opc 版本，0 改动。
 *
 * load(current, size) 既可以返回 MyBatis-Plus 的 {records,total,...}，也可以返回裸数组。
 */
export default function PagedTable({ title, description, load, columns, rowKey = 'id', extra, expandable }) {
    const [rows, setRows] = useState([])
    const [page, setPage] = useState({ current: 1, size: 20, total: null })
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)

    const fetch = useCallback(async (current = page.current, size = page.size) => {
        setLoading(true)
        setError(null)
        try {
            const res = await load(current, size)
            if (Array.isArray(res)) {
                setRows(res || [])
                setPage({ current, size, total: res.length })
            } else {
                const records = res?.records || []
                setRows(records)
                setPage({ current, size, total: res?.total == null ? null : Number(res.total) })
            }
        } catch (e) {
            setRows([])
            setError(e)
        } finally {
            setLoading(false)
        }
    }, [load, page.current, page.size])

    useEffect(() => {
        fetch(1)
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    const totalKnown = page.total != null && page.total > 0
    return (
        <div>
            <PageHeader title={title} subtitle={description} />
            <Card
                extra={
                    <Button icon={<ReloadOutlined />} loading={loading}
                            onClick={() => fetch(page.current, page.size)}>刷新</Button>
                }
            >
                {extra}
                {error ? (
                    <ErrorState error={error} onRetry={() => fetch(page.current, page.size)} />
                ) : (
                    <Table
                        size="small"
                        columns={columns}
                        dataSource={rows}
                        loading={loading}
                        rowKey={(r) => r?.[rowKey] ?? r?.key ?? JSON.stringify(r).slice(0, 32)}
                        pagination={{
                            current: page.current,
                            pageSize: page.size,
                            total: totalKnown ? page.total : rows.length,
                            showSizeChanger: true,
                            showTotal: () =>
                                totalKnown
                                    ? `共 ${page.total} 条`
                                    : `本页 ${rows.length} 条（接口 total 未返回有效值）`,
                        }}
                        onChange={(p) => fetch(p.current, p.pageSize)}
                        expandable={expandable}
                        locale={{ emptyText: <EmptyState title="暂无数据" description="接口返回空列表" /> }}
                    />
                )}
            </Card>
        </div>
    )
}
