import { Button, Space } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'

/**
 * 表格上方工具栏 —— 阶段一直接搬 z-opc 版本，0 改动。
 *
 * Props:
 *   - left:    ReactNode   左侧过滤区 (Select, Radio, Checkbox 等)
 *   - right:   ReactNode   右侧操作区 (按钮组)
 *   - extra:   ReactNode   最右侧插入
 */
export default function TableToolbar({ left, right, extra }) {
    return (
        <div
            style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 12,
                marginBottom: 12,
                flexWrap: 'wrap',
            }}
        >
            <Space size={8} wrap>{left}</Space>
            <Space size={8} wrap>
                {right}
                {extra}
            </Space>
        </div>
    )
}

export function RefreshButton({ onClick, loading, title = '刷新' }) {
    return (
        <Button icon={<ReloadOutlined />} onClick={onClick} loading={loading} title={title}>
            {title}
        </Button>
    )
}
