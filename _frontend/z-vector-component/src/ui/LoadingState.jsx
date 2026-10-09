import { Skeleton } from 'antd'
import { radius } from './tokens.js'

/**
 * LoadingState - 统一骨架屏。阶段一直接搬 z-opc 版本，0 改动。
 *
 * 预设：
 *   - cards：网格卡片列表占位
 *   - table：表格式占位
 *   - list：垂直列表占位
 *   - detail：详情页占位
 */
export default function LoadingState({
                                         variant = 'cards',
                                         rows = 4,
                                         active = true,
                                         style,
                                     }) {
    if (variant === 'cards') {
        return (
            <div
                role="status"
                aria-busy="true"
                aria-label="加载中"
                style={{
                    display: 'grid',
                    gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))',
                    gap: 16,
                    ...style,
                }}
            >
                {Array.from({ length: rows }).map((_, i) => (
                    <div
                        key={i}
                        style={{
                            background: '#ffffff',
                            borderRadius: radius.lg,
                            overflow: 'hidden',
                            border: '1px solid #f1f5f9',
                        }}
                    >
                        <Skeleton.Image
                            active={active}
                            style={{ width: '100%', height: 140, borderRadius: 0 }}
                        />
                        <div style={{ padding: 16 }}>
                            <Skeleton active={active} paragraph={{ rows: 2 }} title={{ width: '60%' }} />
                        </div>
                    </div>
                ))}
            </div>
        )
    }

    if (variant === 'table') {
        return (
            <div
                role="status"
                aria-busy="true"
                aria-label="加载中"
                style={{
                    background: '#ffffff',
                    padding: 24,
                    borderRadius: radius.md,
                    ...style,
                }}
            >
                <Skeleton active={active} paragraph={{ rows }} />
            </div>
        )
    }

    if (variant === 'list') {
        return (
            <div role="status" aria-busy="true" aria-label="加载中" style={style}>
                {Array.from({ length: rows }).map((_, i) => (
                    <div
                        key={i}
                        style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: 12,
                            padding: '12px 0',
                            borderBottom: i < rows - 1 ? '1px solid #f1f5f9' : 'none',
                        }}
                    >
                        <Skeleton.Avatar active={active} size="default" />
                        <Skeleton active={active} paragraph={{ rows: 1 }} title={{ width: 120 }} style={{ flex: 1 }} />
                    </div>
                ))}
            </div>
        )
    }

    return (
        <div
            role="status"
            aria-busy="true"
            aria-label="加载中"
            style={{
                background: '#ffffff',
                padding: 32,
                borderRadius: radius.md,
                ...style,
            }}
        >
            <Skeleton active={active} title={{ width: '40%' }} paragraph={{ rows }} />
        </div>
    )
}
