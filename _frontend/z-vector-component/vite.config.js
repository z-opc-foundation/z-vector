import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

// z-vector 组件层 — Vite library mode 配置（lead 005 §1.2 / §9）
// 产物：dist/index.js（ES Module，外部化 react/antd 全家）
// 引用方：同仓 _frontend/z-vector-suit/（file: 协议）或外部项目（npm install）
export default defineConfig({
    plugins: [react()],
    build: {
        lib: {
            entry: path.resolve(__dirname, 'src/index.js'),
            name: 'ZVectorComponent',
            formats: ['es'],
            fileName: () => 'index.js',
        },
        outDir: 'dist',
        emptyOutDir: true,
        sourcemap: false,
        rollupOptions: {
            external: [
                'react',
                'react-dom',
                'react-dom/client',
                'react-router-dom',
                'antd',
                '@ant-design/icons',
            ],
        },
    },
});
