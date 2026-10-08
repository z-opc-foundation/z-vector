# z-vector 管理台前端

> feature001 阶段一（分开发部署）。同 React 19 + Vite 6 + AntD 6 栈 + react-router-dom 7。
> 与 z-opc 主壳同源；目录结构对齐 z-opc AGENTS.md 中「子模块自带 `_frontend/`」约定。
>
> 域前端归属规矩（lead 005 §8）：z-vector 的 console 唯一真源在本仓 `_frontend/`，
> 不挂入 z-opc 主壳的 23 个域目录。组件层 `./console/pages` 导出 routes manifest。

## 快速开始

```bash
# 1) 起后端（独立 z-vector-server 已经在 :6333 跑 Qdrant 风格 REST）
cd z-vector
mvn -pl z-vector-server -am clean install -DskipTests
ZVECTOR_PORT=6333 ZVECTOR_DATA_DIR=./_tmp-zvector ZVECTOR_DEFAULT_INDEX=HNSW \
  ZVECTOR_INDEX_PARAMS='{"M":16,"efConstruction":200}' \
  ZVECTOR_CORS_ORIGINS=http://localhost:3000 \
  java -jar z-vector-server/target/z-vector-server-*.jar

# 2) 起前端（dev 模式：vite dev :3000，proxy /collections /health /__instance → :6333）
cd z-vector/_frontend
npm install
npm run dev
# → http://localhost:3000
```

## 部署

### 阶段一：分开发部署（本 README 覆盖的范围）

dev：`npm run dev`（3000）→ vite proxy → `:6333`。

生产同源：构建产物在 `dist/`，用 `nginx.conf` + `Dockerfile` 部署：

```bash
npm run build
docker build -t z-vector-frontend:local .
docker run -d -p 8080:80 \
  -e BACKEND_HOST=z-vector-server-host \
  z-vector-frontend:local
```

`nginx.conf` 的最长前缀匹配：**先**抽走 `/health`、`/__instance`、`/collections/`
（这三条反代到后端），其余路径 SPA fallback 到 `index.html`。BrowserRouter 的
history 模式需要这一条；否则 `/collections` 会被当 SPA 路由返回 `index.html`
而不是 `/collections` 后端接口。

### 阶段二：一体化部署（本期不实现）

`ZVECTOR_WEB_DIR` 环境变量开启 `z-vector-server` 静态托管 `dist/` + SPA fallback。
详见 `_doc/001_feature/001_管理台前端/feature001.md` §6.2。

## 目录结构

```
_frontend/
├── package.json
├── vite.config.mjs        # dev :3000，proxy → :6333
├── index.html
├── nginx.conf             # 生产分开发部署
├── Dockerfile             # 多阶段 build → nginx
└── src/
    ├── main.jsx           # 入口：ConfigProvider(zhCN + antdTheme)
    ├── App.jsx            # BrowserRouter + AdminShell + routeTable
    ├── common/            # 通用层（从 z-opc 1:1 搬，无 z-opc 业务耦合）
    │   ├── utils/
    │   │   ├── request.js # axios 实例（无 tenant/JWT；本仓阶段一无登录）
    │   │   └── jwt.js     # 预留（未来接登录）
    │   └── components/
    │       ├── Layout/index.jsx   # 通用左菜单架子 AdminShell
    │       ├── LoginPage/index.jsx# 占位
    │       └── ui/                # PageHeader / EmptyState / ErrorState /
    │                              # LoadingState / StatusBadge / SearchInput /
    │                              # PagedTable / SectionHeader / TableToolbar / tokens
    └── console/           # z-vector 管理台（第一个消费 AdminShell 的项目）
        ├── routes.jsx     # menuConfig + routeTable + appMeta
        ├── services/api.js
        └── pages/
            ├── CollectionList.jsx
            ├── CollectionDetail.jsx
            ├── SearchPlayground.jsx
            └── InstanceStatus.jsx
```

## 与 z-opc 关系（搬运台账）

| 来源 | 去向 | 行数 | 改动 |
| --- | --- | --- | --- |
| `z-opc/…/common/components/Layout/index.jsx` | `src/common/components/Layout/index.jsx` | 69 | 0 改动；export 改 named + default 双形式 |
| `z-opc/…/common/utils/request.ts` | `src/common/utils/request.js` | 254 → 67 | 去 TypeScript / 公开 API 白名单 / tenant 注入 / 401 重定向 / 默认 unwrap |
| `z-opc/…/common/utils/jwt.ts` | `src/common/utils/jwt.js` | 63 → 53 | 去 TypeScript，逻辑保留 |
| `z-opc/…/common/components/ui/*`（11 个非 echarts 组件 + tokens） | `src/common/components/ui/*` | ~1136 | 0 改动（index.js 砍 5 个 echarts/feature-card 导出） |
| `z-opc/…/common/components/LoginPage/index.jsx` | `src/common/components/LoginPage/index.jsx` | 67 | 占位改造：登录提交 message.info，未挂路由 |
| `z-opc/…/vector/services/api.js` | `src/console/services/api.js` | 63 → 70 | 路径去 `/api/vector` 前缀，错误文案保留 |
| `z-opc/…/vector/pages/CollectionList.jsx` | `src/console/pages/CollectionList.jsx` | 182 → 187 | 顶部 subtitle 1 处更正（指向 vite proxy :6333 而非 VectorProxyController） |
| `z-opc/…/vector/pages/CollectionDetail.jsx` | `src/console/pages/CollectionDetail.jsx` | 164 → 173 | 顶部 subtitle + 新增 index_params 行 |
| `z-opc/…/vector/pages/SearchPlayground.jsx` | `src/console/pages/SearchPlayground.jsx` | 208 → 222 | 顶部 subtitle + 新增 time_ms tag 渲染 |
| `z-opc/…/vector/pages/InstanceStatus.jsx` | `src/console/pages/InstanceStatus.jsx` | 94 → 86 | 全面重写：删 z-opc proxy 字段，改为读 z-vector-server `/__instance` |
| `z-opc/…/vector/pages/VectorApp.jsx` | — | 21 | 不搬：被 `console/routes.jsx` 取代（数据驱动的 manifest） |

**未搬**：`common/components/ui/{EChartsChart, FeatureCard, GradientStatCard, DarkBanner, CategoryDot}.jsx` —
阶段一 console 用不到，避免拉 echarts 重型依赖。后续真用图表单独加。

## 已知差异

1. **无登录**。z-vector 当前是单机进程，没有用户系统。`request.js` 不读 token、不挂
   401 跳转、`/login` 路由不挂载。`LoginPage/index.jsx` 占位保留。
2. **CORS**：dev 模式 vite proxy 自动处理；生产同源走 nginx proxy_pass 不需要。
   仅当阶段二前端从另一个 origin 访问后端时，启用 `ZVECTOR_CORS_ORIGINS` 环境变量。
3. **SPA history 路由**：刷新 `/collections/demo` 这类深链接时，必须走 nginx 的
   `try_files $uri $uri/ /index.html` 兜底，否则 404。`vite dev` 不需要（dev server 默认兜底）。

## 验证日志

阶段一 E2E 验证见 `_doc/001_feature/001_管理台前端/feature001.md` §7。
