import { Tag } from 'antd'

/**
 * 通用状态 Badge —— 阶段一直接搬 z-opc 版本，0 改动。
 * 替代散落在各页面的 Tag 颜色定义。
 */
const TASK_STATUS = {
    0: { label: '待办', color: 'default' },
    1: { label: '进行中', color: 'blue' },
    2: { label: '已完成', color: 'green' },
}

const MODEL_STATUS = {
    ACTIVE: { label: '正常', color: 'green' },
    INACTIVE: { label: '禁用', color: 'red' },
    ENABLED: { label: '启用', color: 'green' },
    DISABLED: { label: '禁用', color: 'red' },
}

const PRIORITY = {
    low: { label: '低', color: 'default' },
    medium: { label: '中', color: 'orange' },
    high: { label: '高', color: 'red' },
    1: { label: '低', color: 'default' },
    2: { label: '中', color: 'orange' },
    3: { label: '高', color: 'red' },
}

const APPROVAL = {
    PENDING: { label: '待审批', color: 'orange' },
    APPROVED: { label: '已通过', color: 'green' },
    REJECTED: { label: '已拒绝', color: 'red' },
    CANCELLED: { label: '已撤回', color: 'default' },
}

const SCHEDULE = {
    RUNNING: { label: '运行中', color: 'green' },
    PAUSED: { label: '已暂停', color: 'orange' },
    STOPPED: { label: '已停止', color: 'default' },
    FAILED: { label: '失败', color: 'red' },
    SUCCESS: { label: '成功', color: 'green' },
}

const MAP = {
    task: TASK_STATUS,
    model: MODEL_STATUS,
    priority: PRIORITY,
    approval: APPROVAL,
    schedule: SCHEDULE,
}

export default function StatusBadge({ status, type = 'task', mapping, ...rest }) {
    const m = mapping || MAP[type] || {}
    const cfg = m[status]
    if (!cfg) return <Tag {...rest}>{String(status)}</Tag>
    return (
        <Tag color={cfg.color} {...rest}>
            {cfg.label}
        </Tag>
    )
}
