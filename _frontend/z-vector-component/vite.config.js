import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import {safeEmptyOutDir} from '../../build-tools/safe-vite-plugin.mjs'

// z-vector 组件层 — Vite library mode 配置（lead 005 §1.2 / §9）
// 产物：dist/index.js（ES Module，外部化 react/antd 全家）
// 引用方：同仓 _frontend/z-vector-suit/（file: 协议）或外部项目（npm install）
// AppLayout 已撤：suit 直接走 @yuku123/z-frontend-common@^1.0.0（npm，z-opc-foundation/z-frontend-common 仓）。
export default defineConfig({
    plugins: [safeEmptyOutDir(), react()],
    resolve: {
        alias: {
            '@': path.resolve(__dirname, 'src'),
        },
    },
    build: {
        lib: {
            entry: {
                index: path.resolve(__dirname, 'src/index.js'),
                pages: path.resolve(__dirname, 'src/pages.js'),
            },
            formats: ['es'],
        },
        outDir: 'dist',
        emptyOutDir: false,
        sourcemap: false,
        rollupOptions: {
            external: [
                'react',
                'react-dom',
                'react-dom/client',
                'react-router-dom',
                'antd',
                '@ant-design/icons', 'axios',
                // 基座（含 /ui 子路径）必须 external：rollup 的 external 是**精确字符串匹配**，
                // 只写主桶的话，源码里的 '@yuku123/z-frontend-common/ui' 会被当普通依赖整包打进
                // 本包 dist —— 共享基座就多了一份副本，单一真源被打破，且包体膨胀。
                // 三个子路径必须逐个列出。
                '@yuku123/z-frontend-common',
                '@yuku123/z-frontend-common/ui',
                '@yuku123/z-frontend-common/ui/echarts',
            ],
        },
    },
});
