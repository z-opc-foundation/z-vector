import { useState } from 'react'
import { Button, Card, Form, Input, Tabs, Typography, message } from 'antd'
import { UserOutlined, LockOutlined } from '@ant-design/icons'

/**
 * 通用登录页 —— 阶段一占位。
 * <p>
 * 从 z-opc 67 行版 1:1 搬运。z-vector 当前不挂登录路由（App.jsx 里没有指向这里），
 * 但保留作为「未来接用户系统时直接用」的备件。Form 用 antd 默认校验 + 假 onFinish，
 * 点了登录只是 message.info 占位，不会真发请求。
 */
export default function LoginPage() {
    const [form] = Form.useForm()
    const [submitting, setSubmitting] = useState(false)

    const onFinish = async () => {
        setSubmitting(true)
        try {
            // 占位：未来接 z-ctc-ac 的登录接口时换成 request.post('/api/ctc/ac/login', ...)
            message.info('z-vector 当前未启用登录（feature001 阶段一占位）')
        } finally {
            setSubmitting(false)
        }
    }

    return (
        <div style={{
            minHeight: '100vh',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            background: '#f8fafc',
        }}>
            <Card style={{ width: 380 }}>
                <Typography.Title level={3} style={{ textAlign: 'center', marginBottom: 24 }}>
                    z-vector 管理台
                </Typography.Title>
                <Tabs
                    items={[
                        {
                            key: 'password',
                            label: '账号密码',
                            children: (
                                <Form form={form} onFinish={onFinish} layout="vertical">
                                    <Form.Item name="username" rules={[{ required: true }]}>
                                        <Input prefix={<UserOutlined />} placeholder="用户名" />
                                    </Form.Item>
                                    <Form.Item name="password" rules={[{ required: true }]}>
                                        <Input.Password prefix={<LockOutlined />} placeholder="密码" />
                                    </Form.Item>
                                    <Button type="primary" htmlType="submit" loading={submitting} block>
                                        登录
                                    </Button>
                                </Form>
                            ),
                        },
                    ]}
                />
            </Card>
        </div>
    )
}
