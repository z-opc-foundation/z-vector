import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import 'antd/dist/reset.css'
import App from './App.jsx'
import { antdTheme, configureVector } from '@yuku123/z-vector-component'

/**
 * 阶段一（feature001）入口：
 * <ul>
 *   <li>ConfigProvider 用 z-opc 同款 antdTheme（品牌色 + Layout 主题），与 z-opc 后台视觉一致</li>
 *   <li>zhCN 本地化：时间/数字/空文案都走中文，前端不挂 i18n</li>
 *   <li>不挂任何 auth provider：z-vector 当前无登录，admin shell 直接渲染</li>
 * </ul>
 */
// 注入 axios baseURL = '/api'，与 vite proxy / nginx 反代的同源前缀对齐；
// 组件侧默认就是 '/api'，这里显式调一次以便宿主改前缀时只改这一行。
configureVector('/api')

createRoot(document.getElementById('root')).render(
    <StrictMode>
        <ConfigProvider locale={zhCN} theme={antdTheme}>
            <App />
        </ConfigProvider>
    </StrictMode>
)
