import { Button } from 'antd'
import { ExclamationCircleOutlined, ReloadOutlined } from '@ant-design/icons'
import { neutral, radius } from './tokens.js'

/**
 * 统一错误状态 —— 阶段一直接搬 z-opc 版本，0 改动。
 * 优先取后端响应体里的 message，再退回 axios/字符串。
 */
export default function ErrorState({
                                       error,
                                       title = '加载失败',
                                       description,
                                       onRetry,
                                       retryText = '重新加载',
                                       variant = 'inline',
                                       style,
                                   }) {
    const isFull = variant === 'full'

    const body = error?.response?.data
    const backendMsg = (body && typeof body === 'object')
        ? (typeof body.message === 'string' && body.message) || (typeof body.msg === 'string' && body.msg) || null
        : null
    const errMsg = backendMsg
        || (typeof error === 'string'
            ? error
            : (error?.message || error?.msg || (error ? String(error) : null)))

    const finalDescription = description
        || (backendMsg
            ? `原因: ${backendMsg}`
            : (errMsg ? `原因: ${errMsg}。请检查网络或稍后重试。` : '请检查网络或稍后重试。'))

    return (
        <div
            role="alert"
            aria-live="assertive"
            style={{
                padding: isFull ? '48px 24px' : '20px 24px',
                background: isFull ? '#ffffff' : '#fef2f2',
                border: isFull ? `1px dashed ${neutral.border}` : '1px solid #fecaca',
                borderRadius: radius.md,
                textAlign: isFull ? 'center' : 'left',
                display: 'flex',
                flexDirection: isFull ? 'column' : 'row',
                alignItems: isFull ? 'center' : 'flex-start',
                gap: isFull ? 12 : 16,
                ...style,
            }}
        >
            <div style={{
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: isFull ? 56 : 36, height: isFull ? 56 : 36,
                borderRadius: '50%',
                background: isFull ? '#fef2f2' : '#ffffff',
                color: '#dc2626',
                fontSize: isFull ? 28 : 18,
                flexShrink: 0,
            }}>
                <ExclamationCircleOutlined />
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{
                    fontSize: isFull ? 16 : 14,
                    fontWeight: 600,
                    color: '#991b1b',
                    marginBottom: 4,
                }}>
                    {title}
                </div>
                <div style={{
                    fontSize: 13,
                    color: '#7f1d1d',
                    lineHeight: 1.6,
                    wordBreak: 'break-word',
                }}>
                    {finalDescription}
                </div>
                {onRetry && (
                    <Button
                        type="primary"
                        danger
                        icon={<ReloadOutlined />}
                        onClick={onRetry}
                        size={isFull ? 'middle' : 'small'}
                        style={{ marginTop: 12 }}
                    >
                        {retryText}
                    </Button>
                )}
            </div>
        </div>
    )
}
