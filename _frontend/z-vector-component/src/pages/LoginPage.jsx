import { useState } from 'react'
import { Button, Card, Form, Input, message } from 'antd'
import { LockOutlined, UserOutlined } from '@ant-design/icons'

/**
 * 登录页（lead 008 §16）—— 短期 hard-coded 模式，待统一 SSO 接入前使用。
 *
 * mockAuth 传 { username, password, user } 时跳过真实 API：form 提交直接比对，
 * 命中后写 localStorage（token + userInfo）并跳 redirectUrl。SSO 接入后把 mockAuth
 * 去掉、换 loginApi 走后端即可（接口形状与 z-frontend-common 的 LoginPage 一致）。
 */
export default function LoginPage({
                                      title = '统一管理平台',
                                      loginApi = '/auth/login',
                                      redirectUrl = '/',
                                      onSuccess,
                                      mockAuth,
                                  }) {
    const [loading, setLoading] = useState(false)

    const handleSubmit = async (values) => {
        setLoading(true)
        try {
            if (mockAuth) {
                if (values.username === mockAuth.username && values.password === mockAuth.password) {
                    const user = mockAuth.user || { name: mockAuth.username, role: '管理员' }
                    localStorage.setItem('token', 'mock-' + mockAuth.username)
                    localStorage.setItem('userInfo', JSON.stringify(user))
                    message.success('登录成功（mock 模式，待 SSO 接入）')
                    if (onSuccess) onSuccess({ token: 'mock-' + mockAuth.username, user })
                    else window.location.href = redirectUrl
                } else {
                    message.error('用户名或密码错误（mock 模式默认 admin/123456）')
                }
                return
            }

            const res = await fetch(loginApi, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(values),
            }).then((r) => r.json())
            if (res.token) {
                localStorage.setItem('token', res.token)
                localStorage.setItem('userInfo', JSON.stringify(res.user || res))
                message.success('登录成功')
                if (onSuccess) onSuccess(res)
                else window.location.href = redirectUrl
            } else {
                message.error('登录失败：未获取到 token')
            }
        } catch (err) {
            message.error(err?.message || '登录失败')
        } finally {
            setLoading(false)
        }
    }

    return (
        <div style={{
            height: '100vh', display: 'flex', justifyContent: 'center', alignItems: 'center',
            background: 'linear-gradient(135deg, #eef2ff 0%, #f5f3ff 50%, #eff6ff 100%)',
        }}>
            <Card style={{ width: 400, boxShadow: '0 10px 40px rgba(124,58,237,0.12)' }}>
                <div style={{ textAlign: 'center', marginBottom: 20 }}>
                    <div style={{
                        width: 48, height: 48, borderRadius: 12, margin: '0 auto 12px',
                        background: 'linear-gradient(135deg, #7c3aed, #6d28d9)',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        color: '#fff', fontSize: 24, fontWeight: 700,
                    }}>{(title || '?').slice(0, 1)}</div>
                    <div style={{ fontSize: 18, fontWeight: 600, color: '#0f172a' }}>{title}</div>
                </div>
                <Form onFinish={handleSubmit} size="large">
                    <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
                        <Input prefix={<UserOutlined />} placeholder="用户名" />
                    </Form.Item>
                    <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
                        <Input.Password prefix={<LockOutlined />} placeholder="密码" />
                    </Form.Item>
                    <Form.Item style={{ marginBottom: 8 }}>
                        <Button type="primary" htmlType="submit" loading={loading} block
                                style={{ background: 'linear-gradient(135deg, #7c3aed 0%, #6d28d9 100%)', border: 'none' }}>
                            登 录
                        </Button>
                    </Form.Item>
                    {mockAuth && (
                        <div style={{ textAlign: 'center', color: '#94a3b8', fontSize: 12 }}>
                            Mock 模式：用户名 <b>{mockAuth.username}</b> / 密码 <b>{mockAuth.password}</b>
                        </div>
                    )}
                </Form>
            </Card>
        </div>
    )
}
