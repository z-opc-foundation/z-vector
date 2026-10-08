import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'node:path'

/**
 * z-vector 管理台前端 —— 阶段一（feature001）分开发部署。
 * <p>
 * dev 端口 3000：与 z-opc 主壳肌肉记忆一致，避免团队端口漂移。
 * <p>
 * 关键：proxy 用 /api/** 前缀，与浏览器 SPA 路由的 /collections /search /__instance /health
 * 完全隔离。否则浏览器刷新 /collections/demo 时，vite proxy 把它当成后端调用转发，
 * React 永远拿不到 index.html —— 屏幕上一片 JSON。
 * <ul>
 *   <li>/api/collections → z-vector-server :6333/collections（Qdrant 风格 REST）</li>
 *   <li>/api/health → z-vector-server :6333/health（自检）</li>
 *   <li>/api/__instance → z-vector-server :6333/__instance（实例自省）</li>
 * </ul>
 * 前端 services/api.js 里所有调用都走 /api/... 前缀。生产 nginx 同源也是 /api/** 反代 + SPA fallback。
 * <p>
 * 生产部署：通过 vite build 产出纯静态 dist/，由 nginx（或阶段二的 z-vector-server 静态托管）反代。
 */
export default defineConfig({
    plugins: [react()],
    resolve: {
        alias: {
            '@': path.resolve(__dirname, 'src'),
        },
    },
    server: {
        port: 3000,
        host: '0.0.0.0',
        proxy: {
            '/api/collections': { target: 'http://localhost:6333', changeOrigin: true, rewrite: p => p.replace(/^\/api/, '') },
            '/api/health':      { target: 'http://localhost:6333', changeOrigin: true, rewrite: p => p.replace(/^\/api/, '') },
            '/api/__instance':  { target: 'http://localhost:6333', changeOrigin: true, rewrite: p => p.replace(/^\/api/, '') },
        },
    },
})

