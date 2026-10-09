import { Button } from 'antd'
import { InboxOutlined } from '@ant-design/icons'
import { neutral, radius } from './tokens.js'

/**
 * 统一空状态 —— 阶段一直接搬 z-opc 版本，0 改动。
 *
 * 原则：
 *   - An empty screen is an invitation to act.
 *   - 不要只画一个插画加一行字，要有明确的"下一步动作"。
 *   - 默认尺寸 44×44 满足触摸目标下限。
 */
export default function EmptyState({
                                       icon,
                                       title = '暂无数据',
                                       description,
                                       actionText,
                                       onAction,
                                       size = 'md',
                                       style,
                                   }) {
    const padMap = { sm: 24, md: 40, lg: 64 }
    const titleSize = { sm: 14, md: 16, lg: 20 }

    return (
        <div
            role="status"
            aria-live="polite"
            style={{
                padding: padMap[size] || padMap.md,
                textAlign: 'center',
                background: '#ffffff',
                borderRadius: radius.md,
                border: '1px dashed #e5e7eb',
                ...style,
            }}
        >
            <div style={{
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: 56, height: 56,
                borderRadius: '50%',
                background: neutral.surfaceAlt,
                marginBottom: 16,
                fontSize: 28,
                color: '#9ca3af',
            }}>
                {icon || <InboxOutlined />}
            </div>
            <div style={{
                fontSize: titleSize[size],
                fontWeight: 600,
                color: neutral.text,
                marginBottom: description ? 6 : 16,
            }}>
                {title}
            </div>
            {description && (
                <div style={{
                    fontSize: 13,
                    color: neutral.textMuted,
                    lineHeight: 1.6,
                    maxWidth: 360,
                    margin: '0 auto 16px',
                }}>
                    {description}
                </div>
            )}
            {actionText && onAction && (
                <Button
                    type="primary"
                    onClick={onAction}
                    style={{
                        background: 'linear-gradient(135deg, #7c3aed 0%, #6d28d9 100%)',
                        border: 'none',
                        minHeight: 36,
                    }}
                >
                    {actionText}
                </Button>
            )}
        </div>
    )
}
