# Feature 001 — z-vector 管理台前端

| 字段 | 值 |
| --- | --- |
| 仓 | `z-vector` |
| 目标版本 | 1.0.5（不动 `<revision>`，本特性纯新增 + 重构） |
| 阶段一交付 | 后端 REST 统一 + `/__instance` + CORS + `_frontend/`（React 19 + AntD 6） |
| 阶段二交付 | 一体化部署：z-vector-server 静态托管前端 dist + SPA fallback |
| 关联文档 | z-opc `bootstraps/z-opc-main-starter-frontend/src/vector/`（被搬走 / 改编为唯一参照） |

---

## 1. 背景与问题

仓库内现存的"管理面"实现分散在三个地方：

- `z-vector-grpc-server/QdrantRestServer` —— Qdrant 风格 REST（`GET /collections`、`PUT /collections/{n}/points`、`POST /collections/{n}/points/search`…），路由 7 条、含完整 filter 解析，是给外部用的正路；
- `z-vector-server/VectorServerApplication` —— 独立 server，但用的是**自创的扁平路由**（`POST /points` / `POST /search`），与 Qdrant 形状不兼容；前端拿不到这里；
- z-opc 主仓的 `bootstraps/z-opc-main-starter-frontend/src/vector/` —— 5 个管理页 + `services/api.js`（`GET /api/vector/__instance`、`/collections`、`/collections/{n}`…），调的是 z-opc 自己的 `VectorProxyController` 把请求转发到本 JVM 内嵌的 Qdrant REST。

直接后果：

1. 独立 server 与内嵌 starter **不是同一个 REST 形状**。两份实现、两份字段命名、两份 OpenAPI 文本，客户端跟着任一边写都会在另一边 404。
2. z-vector 仓没有前端。`README.md` "未接入" 表格里"可视化控制台 / 前端镜像 | 仓内没有 `_frontend/`"那条至今成立。
3. z-opc 那个 5 页前端是整套 z-opc 88 万行仓里唯一可直接复用、按"集合 → 向量 → 检索"模型切好的现成面。它的 `services/api.js` 已经把"name 必须 `^[A-Za-z0-9_-]+$`"、"score 是越小越近的距离"、"error 文案必须读 `response.data.message`"这三条坑钉死了。

## 2. 目标 / 非目标

**目标（阶段一必须达成）**

- 把独立 server 切到与 `QdrantRestServer` **同一套 Qdrant 风格 REST**，一处定义、一处路由表，OpenApiSpec 不再分裂。
- 新增 `GET /__instance` —— 实例自省（version / uptime / 端口 / 数据目录 / 集合数 / 点数 / 内存），前端"实例状态"页直接消费。
- 新增 `ZVECTOR_CORS_ORIGINS` 可选 CORS（含 OPTIONS 预检）—— 浏览器跨域直连可用；走 vite dev proxy / nginx proxy_pass 的部署路径不受影响。
- `QdrantRestServer.handleSearch` 的 `time_ms` 从硬编码 0 改成真实测量；前端检索页"耗时"有真值。
- 在仓内新建 `_frontend/`，栈 React 19 + Vite 6 + AntD 6 + axios + react-router-dom 7（与 z-opc 同栈，逐文件 1:1 搬运，零新决策）。
- 抽出**通用左菜单架子 AdminShell**：菜单 + 路由数据驱动，z-vector console 是第一个消费方；未来 z-* 仓可直接复用。
- 部署先做**分开部署**：vite dev (3000) + z-vector-server (6333)，vite proxy `/collections`、`/health`、`/__instance` → 后端；生产给一份 nginx.conf + 前端 Dockerfile 模板。
- 浏览器走通四个核心动作：建集合 → upsert → 检索 → 实例状态 → 删集合。

**非目标（阶段一不做）**

- 鉴权 / 登录 / 多租户。z-vector 当前是单机进程，没有用户系统；AdminShell 不挂任何登录页，request.ts 不读 localStorage token。
- 实时刷新 / WebSocket / 指标采集。InstanceStatus 页是手动点刷新。
- z-opc 仓内的 `src/zteam/`、`src/ctc/`、`src/agent/`、`src/lc/` 等业务组件不搬；这些不是"通用组件"，搬过来没有消费方。
- `Dockerfile`（仓根那个打不出可运行镜像的多阶段 Dockerfile）修复 —— 已知 backlog，本特性不在范围。
- Central 发布。本特性不触发布件。
- 阶段二一体化部署（server 托管静态文件）—— 仅在本文档第 6 节写方案，不在阶段一实现。

## 3. 方案总览

```
┌────────────────────────────── z-vector 仓根 ──────────────────────────────┐
│                                                                            │
│  z-vector-server/                  z-vector-grpc-server/                   │
│  ┌─────────────────────┐           ┌──────────────────────┐                │
│  │ VectorServerApp     │  start    │ QdrantRestServer     │                │
│  │ ─ main()            │ ────────▶ │ ─ 7 Qdrant routes    │                │
│  │ ─ HttpServer        │           │ ─ handler() 公开     │  ◀── refactor   │
│  │   ├ /health         │           │ ─ time_ms 真实测量    │      (本特性)  │
│  │   ├ /__instance     │ NEW       │                      │                │
│  │   └ /               │ ────────▶ │ (handler() 注册到上面/ 上下文)            │
│  │     (CORS+OPTIONS)  │           │                      │                │
│  └─────────────────────┘           └──────────────────────┘                │
│                                                                            │
│  _frontend/  (本特性新建)                                                  │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │ React 19 + Vite 6 + AntD 6 + axios + react-router-dom 7          │    │
│  │                                                                    │    │
│  │ src/                                                               │    │
│  │ ├── main.jsx                                                       │    │
│  │ ├── App.jsx                    ◀── 通用左菜单架子 AdminShell       │    │
│  │ ├── common/                    ◀── 通用层（从 z-opc 1:1 搬运）    │    │
│  │ │   ├── utils/request.js                                          │    │
│  │ │   ├── utils/jwt.js          (预留,本期不接)                      │    │
│  │ │   └── components/                                               │    │
│  │ │       ├── Layout/index.jsx  ← 通用 AdminShell                    │    │
│  │ │       ├── LoginPage/        (预留)                              │    │
│  │ │       └── ui/               ← PageHeader / EmptyState /         │    │
│  │ │                             PagedTable / LoadingState /         │    │
│  │ │                             ErrorState / StatusBadge /          │    │
│  │ │                             SectionHeader / TableToolbar /      │    │
│  │ │                             SearchInput / tokens                │    │
│  │ └── console/                                                       │    │
│  │     ├── routes.js            ◀── 菜单 + 路由（数据驱动）            │    │
│  │     ├── services/api.js      ◀── 从 z-opc 改编（去 /api/vector 前缀）│    │
│  │     └── pages/                                                     │    │
│  │         ├── CollectionList.jsx                                     │    │
│  │         ├── CollectionDetail.jsx                                   │    │
│  │         ├── SearchPlayground.jsx                                   │    │
│  │         └── InstanceStatus.jsx                                     │    │
│  └────────────────────────────────────────────────────────────────────┘    │
│                                                                            │
│  _doc/001_feature/001_管理台前端/feature001.md  ◀── 本文档                 │
└────────────────────────────────────────────────────────────────────────────┘

分开发部署（阶段一）：

  [浏览器]  ──── HTTP  ───▶  [vite dev :3000]  ─── proxy ───▶  [z-vector-server :6333]
   ↑                            (dist 静态)                          (REST + /__instance)
   └────── 走同一 host (生产 nginx 同源 proxy_pass)
```

## 4. 后端优化项

### 4.1 路由统一：独立 server 改挂 Qdrant 风格 REST

现状：独立 server 的 `/health`、`/collections`、`/points`、`/search` 4 条扁平路由是自创形状，与 `QdrantRestServer` 不兼容。

改造：

- `z-vector-server/pom.xml` 加 `<dependency>z-vector-grpc-server</dependency>`（不写 version，父链聚合 pom 的 `${project.version}` 条目兜住）。
- `QdrantRestServer` 新增 `public HttpHandler handler()`，把现有 `private void route(HttpExchange)` 暴露成 `HttpHandler`。`start()` / `stop()` 这条自起服务器路径不变（starter 内嵌 6334 还在用）。
- `VectorServerApplication.start()` 改为最长前缀挂载：`createContext("/health", new HealthHandler())` → `createContext("/__instance", new InstanceHandler())` → `createContext("/", qdrant.handler())`。`HttpServer` 按最长前缀匹配，`/health` 和 `/__instance` 不会被根上下文吃掉。

**REST 形状表（前端消费契约，阶段一冻结）：**

| 方法 | 路径 | 入参 | 出参（核心字段） |
| --- | --- | --- | --- |
| GET | `/health` | — | `{status:"ok", version, collections}` |
| GET | `/__instance` | — | `{status:"ok", version, port, uptime_ms, data_dir, jvm, java, collections, points_total, memory: {heap_used, heap_max, free}}` |
| GET | `/collections` | — | `{status:"ok", collections: [name, …]}` |
| PUT | `/collections/{name}` | `{dimension, metric?, index_type?, index_params?}` | `{status:"ok", name, dimension, metric, index_type, index_params}` |
| GET | `/collections/{name}` | — | `{name, dimension, metric, index_type, index_params, points_count, indexed}` |
| DELETE | `/collections/{name}` | — | `{status:"ok"}` 或 404 |
| PUT | `/collections/{name}/points` | `{points:[{id, vector:[…], payload?}, …]}` | `{status:"ok", count}` |
| GET | `/collections/{name}/points/count` | — | `{count}` |
| POST | `/collections/{name}/points/search` | `{vector, limit?, filter?, build_index?}` | `{result:[{id, score, payload?}, …], status, time_ms}` |
| GET | `/collections/{name}/points/{id}` | — | `{id, vector, payload}` 或 404 |
| DELETE | `/collections/{name}/points/{id}` | — | `{status:"ok"}` 或 404 |

错误统一 `{status:"error", code, message}`，HTTP code 与 `code` 同值（`IllegalArgumentException` → 400，`VectorException` → 400，not found → 404，其他 → 500）。所有错误**不再**被 HttpServer 吞成空响应。

**注意 GET `/collections` 形状变更**：旧独立 server 是裸数组 `[name, …]`，新形状是 `{status, collections}`。这是**破坏性变更**，但旧形状在 README「未接入」段已不作为对外契约，且 OpenApiSpec 自始至终广告的是 `{status, collections}`。任何还在用旧形状的客户端（外部脚本 / README 旧示例）必须改读 `.collections` 字段。

### 4.2 `/__instance` 实例自省

设计原则：全是只读 map 拼装，不抛错，不读 collection 配置细节（点数足够）。目的是让前端"实例状态"页能一眼区分"内嵌 server 起住 / 没起 / 在哪"。

```json
{
  "status": "ok",
  "version": "1.0.5",
  "port": 6333,
  "uptime_ms": 12345,
  "data_dir": "/data/zvector",
  "jvm": "PID@hostname",
  "java": "17.0.10",
  "collections": 3,
  "points_total": 1024,
  "memory": {
    "heap_used": 134217728,
    "heap_max": 2147483648,
    "free": 1610612736
  }
}
```

启动时间戳由 `boot()` 内 `Instant startedAt = Instant.now()` 记录；`uptime_ms` 用 `Duration.between(startedAt, Instant.now()).toMillis()`。`jvm` 用 `ProcessHandle.current().pid() + "@" + InetAddress.getLocalHost().getHostName()`。`points_total` 用 `store.listCollections().stream().mapToLong(store::getPointCount).sum()`，空集合时为 0。

### 4.3 CORS（可选）

环境变量 `ZVECTOR_CORS_ORIGINS`：

- 不设 / 空串 → 不发任何 CORS 头（生产同源走 nginx，不需要）。
- `"*"` → `Access-Control-Allow-Origin: *`，但 `Allow-Credentials` 仍不挂（与本地存储的 `withCredentials` 互斥）。
- `"https://a.com,https://b.com"` → `Allow-Origin: <请求 Origin>`，回声，且 `Vary: Origin`。

实现位置：`VectorServerApplication` 入口包一层 `CorsFilter` 包裹所有 handler；`OPTIONS *` 且 `Access-Control-Request-Method` 存在 → 直接 204 + CORS 头，不下钻业务。Preflight 命中不计入业务日志。

不走 `QdrantRestServer.route()` 内部改 CORS —— starter 内嵌 6334 同进程跑 z-opc，z-opc 自己有 Spring 跨域配置，再叠一层会冲突。

### 4.4 `time_ms` 真实测量

`QdrantRestServer.handleSearch`：

```java
long t0 = System.nanoTime();
// ... 现有逻辑
long ms = (System.nanoTime() - t0) / 1_000_000L;
resp.put("time_ms", ms);
```

仅一处改动，无 API 字段变化。`time_ms` 列名前端用「耗时」。

### 4.5 单测增补

- 新增 `QdrantRestServerSearchTimeMsTest`：跑一次 search，断言 `time_ms >= 0` 且与 `System.nanoTime` 测量同量级（不锁死数字，避免计时抖动假阳性）。
- 复用既有 `QdrantRestServerRoutingTest` 覆盖：health / instance / 单点 GET / 单点 DELETE / 错误 400 / 错误 404 / CORS 预检 / 跨域头。
- 改既有 `VectorServerApplicationTest` / `ServerIndexContractTest` 的旧路由断言为新 Qdrant 形状（如有）。`ServerIndexContractTest` 关注的是模块结构 / 版本号 / PomFiles —— 路由不是它的口径，不必动。

## 5. 前端架构

### 5.1 栈与版本

`package.json`（与 z-opc 同栈，锁版本号同 z-opc 的 `package.json`）：

| 依赖 | 版本 |
| --- | --- |
| react / react-dom | 19.2.0 |
| react-router-dom | 7.13.x |
| antd | 6.3.x |
| @ant-design/icons | 6.x |
| axios | 1.16.x |
| vite | 6.2.x |
| @vitejs/plugin-react | latest |
| dayjs | latest |
| (no redux / vue / element) | — |

理由：与 z-opc 100% 一致 → z-opc 的 `src/vector/`、`src/common/components/Layout/index.jsx`、`src/common/components/ui/` 直接搬运，**零翻译、零类型修正**。唯一不同：dev 端口 3000（与 z-opc 同，避免团队肌肉记忆漂移）；proxy target `http://localhost:6333`。

### 5.2 目录结构

```
_frontend/
├── package.json
├── vite.config.mjs           # dev 3000；proxy ^/(collections|health|__instance) → 6333
├── index.html
├── .gitignore                # node_modules / dist (根 .gitignore 已含 **/{node_modules,dist}/)
├── nginx.conf                # 生产分开部署示例
├── Dockerfile                # 多阶段：node build → nginx 服务 dist
├── README.md                 # 快速开始 / 部署 / 验证日志 / 与 z-opc 关系
└── src/
    ├── main.jsx              # 入口；ConfigProvider + 主题
    ├── App.jsx               # BrowserRouter + AdminShell + 路由表
    ├── common/
    │   ├── index.js          # 桶导出
    │   ├── utils/
    │   │   ├── request.js    # 从 z-opc common/utils/request.ts 改编（去 tenant/JWT 强依赖）
    │   │   └── jwt.js        # 从 z-opc 搬运（保留接口，本期不接）
    │   └── components/
    │       ├── Layout/index.jsx  # AdminShell：menuItems + 标题 + Outlet
    │       ├── LoginPage/index.jsx  # 占位，不挂路由
    │       └── ui/                # 11 个原子组件（见 5.3）
    └── console/
        ├── routes.js         # 菜单 + 路由配置（数据驱动 AdminShell）
        ├── services/api.js   # 从 z-opc 改编
        └── pages/
            ├── CollectionList.jsx
            ├── CollectionDetail.jsx
            ├── SearchPlayground.jsx
            └── InstanceStatus.jsx
```

### 5.3 通用左菜单架子 AdminShell —— 设计要点

**通用性承诺**：AdminShell 不写死任何 z-vector 特有元素。`App.jsx` 的全部业务配置来自一份 `routes.js`：

```js
// _frontend/src/console/routes.js
import CollectionList from './pages/CollectionList'
import CollectionDetail from './pages/CollectionDetail'
import SearchPlayground from './pages/SearchPlayground'
import InstanceStatus from './pages/InstanceStatus'

export const appMeta = { title: 'z-vector', short: 'ZV', icon: DatabaseOutlined }

export const menuConfig = [
  { key: '/collections', label: '集合管理', icon: AppstoreOutlined,
    children: [
      { key: '/collections', label: '集合列表' },
    ] },
  { key: '/search',       label: '检索调试', icon: ThunderboltOutlined },
  { key: '/instance',     label: '实例状态', icon: DashboardOutlined },
]

export const routeTable = [
  { path: '/collections',         element: <CollectionList /> },
  { path: '/collections/:name',   element: <CollectionDetail /> },
  { path: '/search',              element: <SearchPlayground /> },
  { path: '/instance',            element: <InstanceStatus /> },
]
```

`App.jsx` 仅做：`<BrowserRouter>` → `<AdminShell menuItems={menuItems} appTitle={appMeta.title} ...>` → `<Routes>` 渲染 `routeTable`。下一个 z-* 仓要复用 AdminShell，只需要写自己的 `routes.js`，**改 0 行 App.jsx**。

AdminShell（`common/components/Layout/index.jsx`）从 z-opc 69 行版**直接搬**，参数签名 `menuItems / appTitle / appShort / headerExtra / loading` 全保留。当前阶段一，`headerExtra` 固定传「v1.0.5」tag。

### 5.4 common 通用层搬运清单

| 目标文件 | 来源 | 行数 | 改动 |
| --- | --- | --- | --- |
| `common/utils/request.js` | z-opc `request.ts` | 254 → 80 | 去 TypeScript → JSX；去 PUBLIC_API_PATTERNS 数组（无鉴权）；去 tenant/domainCode 注入；去 401 → /login 重定向；去 defaultUnwrap（QdrantRestServer 返回的是原始 body，不是 `{code,data,message}` 包装）；保留 setupInterceptors 骨架留白；createRequest 默认 `baseURL=''`、`withCredentials=false` |
| `common/utils/jwt.js` | z-opc `jwt.ts` | 63 | 保留；本期不消费 |
| `common/components/Layout/index.jsx` | z-opc 同名 | 69 | 0 改动 |
| `common/components/LoginPage/index.jsx` | z-opc 同名 | 67 | 0 改动（占位） |
| `common/components/ui/PageHeader.jsx` | z-opc 同名 | 143 | 0 改动 |
| `common/components/ui/EmptyState.jsx` | z-opc 同名 | 94 | 0 改动 |
| `common/components/ui/LoadingState.jsx` | z-opc 同名 | 116 | 0 改动 |
| `common/components/ui/ErrorState.jsx` | z-opc 同名 | 114 | 0 改动 |
| `common/components/ui/StatusBadge.jsx` | z-opc 同名 | 65 | 0 改动 |
| `common/components/ui/SearchInput.jsx` | z-opc 同名 | 111 | 0 改动 |
| `common/components/ui/PagedTable.jsx` | z-opc 同名 | 83 | 0 改动（console 本期不用，留给后续） |
| `common/components/ui/SectionHeader.jsx` | z-opc 同名 | 76 | 0 改动 |
| `common/components/ui/TableToolbar.jsx` | z-opc 同名 | 57 | 0 改动 |
| `common/components/ui/tokens.js` | z-opc 同名 | 224 | 0 改动 |
| `common/components/ui/index.js` | z-opc 同名 | 36 | 砍掉 EChartsChart/FeatureCard/GradientStatCard/DarkBanner/CategoryDot 五个导出（本期不搬，引入 echarts 是为这五个，不该带的包不带） |
| **小计** | — | 1 562 行（搬） + 80 行（改） | — |

**不搬的 ui 组件**（理由：本期无消费方 + 会拉 echarts 重型依赖）：`EChartsChart`、`FeatureCard`、`GradientStatCard`、`DarkBanner`、`CategoryDot`。`index.js` 不再 re-export 这五个，后续真有图表需求再补。

### 5.5 console 服务层适配

`_frontend/src/console/services/api.js`（从 z-opc 搬 + 改 baseURL）：

```js
import request from '@/common/utils/request'

// 阶段一：直连 Qdrant 形状（无 /api/vector 前缀，由 vite proxy 把 /collections/** 转到 6333）
// 错误体形状同 z-opc 实测：{status:"error", code, message}
export const vectorApi = {
  instance:        ()                  => request.get('/__instance'),
  listCollections: ()                  => request.get('/collections'),
  collection:      (name)              => request.get(`/collections/${encodeURIComponent(name)}`),
  pointCount:      (name)              => request.get(`/collections/${encodeURIComponent(name)}/points/count`),
  createCollection:(name, body)        => request.put(`/collections/${encodeURIComponent(name)}`, body),
  deleteCollection:(name)              => request.delete(`/collections/${encodeURIComponent(name)}`),
  upsertPoints:    (name, points)      => request.put(`/collections/${encodeURIComponent(name)}/points`, { points }),
  search:          (name, body)        => request.post(`/collections/${encodeURIComponent(name)}/points/search`, body),
  getPoint:        (name, id)          => request.get(`/collections/${encodeURIComponent(name)}/points/${encodeURIComponent(id)}`),
  deletePoint:     (name, id)          => request.delete(`/collections/${encodeURIComponent(name)}/points/${encodeURIComponent(id)}`),
}

// name 必须在 URL 里只允许 [A-Za-z0-9_-]；QdrantRestServer 路由 regex 也是这个集合，前端提前挡住
export const NAME_PATTERN = /^[A-Za-z0-9_-]+$/

// 错误文案：axios 默认只给 "Request failed with status code 400"，真因在 response.data.message
export function vectorErrorText(e) {
  const data = e?.response?.data
  if (data && typeof data === 'object') {
    const message = data.message || data.error
    if (message) return String(message).slice(0, 300)
  }
  if (typeof data === 'string' && data.trim()) return data.slice(0, 200)
  return e?.message || String(e)
}

export const METRICS = ['L2', 'IP', 'COSINE', 'HAMMING']
export const INDEX_TYPES = ['FLAT', 'HNSW', 'IVF']
```

四个页面从 z-opc 1:1 搬运，改动只有两处：

1. 顶部 `import {…} from '../services/api'` 不变；导入路径在 _frontend 里是 `./services/api`，无需改。
2. `PageHeader subtitle` 字符串里 `由 VectorProxyController 转发…` 一句改成 `vite dev proxy 转发到 z-vector-server :6333（详见部署文档）`。这是事实更正，不是行为变化。

### 5.6 vite 配置

```js
// _frontend/vite.config.mjs
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'node:path'

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { '@': path.resolve(__dirname, 'src') } },
  server: {
    port: 3000,
    host: '0.0.0.0',
    proxy: {
      '/collections':  { target: 'http://localhost:6333', changeOrigin: true },
      '/health':       { target: 'http://localhost:6333', changeOrigin: true },
      '/__instance':   { target: 'http://localhost:6333', changeOrigin: true },
    },
  },
})
```

生产构建（`npm run build`）产物在 `dist/`，纯静态。**不在 vite 构建时塞后端 URL**，让部署期通过 nginx / 后端代管决定。

## 6. 部署

### 6.1 阶段一：分开发部署（本期实现）

**开发模式**

```bash
# 终端 A：起后端
cd z-vector
mvn -pl z-vector-server -am clean install -DskipTests
ZVECTOR_PORT=6333 ZVECTOR_DATA_DIR=./_tmp-zvector ZVECTOR_DEFAULT_INDEX=HNSW \
  ZVECTOR_INDEX_PARAMS='{"M":16,"efConstruction":200}' \
  ZVECTOR_CORS_ORIGINS=http://localhost:3000 \
  java -jar z-vector-server/target/z-vector-server-*.jar

# 终端 B：起前端
cd z-vector/_frontend
npm install
npm run dev   # → http://localhost:3000
```

**生产同源**（一份 `nginx.conf` 模板）：

```nginx
server {
  listen 80;
  server_name _;

  root /var/www/zvector/dist;     # _frontend/dist
  index index.html;

  # SPA fallback（AdminShell 路由 history 模式需要）
  location / {
    try_files $uri $uri/ /index.html;
  }

  # 后端反代（最长前缀优先级最高 → /__instance 不会被 SPA fallback 吃掉）
  location /collections/ { proxy_pass http://127.0.0.1:6333; proxy_set_header Host $host; }
  location = /health     { proxy_pass http://127.0.0.1:6333; proxy_set_header Host $host; }
  location = /__instance { proxy_pass http://127.0.0.1:6333; proxy_set_header Host $host; }

  # 后端可选 gzip / 超时，按需
}
```

**前端 Dockerfile 模板**（多阶段：node:22-alpine 构建 → nginx:alpine 服务）：

```dockerfile
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

后端继续走 `z-vector-server/Dockerfile`（仓内已有，eclipse-temurin:8-jre，HEALTHCHECK 打 `/health` —— 现在 `/health` 形状没变，本特性不触发后端镜像变更）。

### 6.2 阶段二：一体化部署（本期文档化，不实现）

目标：单容器单端口出整栈。`ZVECTOR_WEB_DIR=/var/www/zvector/dist` 环境变量开启静态托管，否则行为完全等同当前。

骨架（待实现阶段）：

- `VectorServerApplication.start()` 检测 `ZVECTOR_WEB_DIR` 环境变量：
  - 未设 → 与阶段一相同（只 REST）；
  - 已设 → 在 `/` 上下文上挂 `StaticFileHandler`（优先级低于 `/health`、`/__instance`、`/collections/**`），其余路径 SPA fallback 到 `index.html`。
- 该 handler 需读盘 + 判断 mime + 处理 ETag + 处理 `Cache-Control: no-cache` for `index.html`。约 80~120 行 JDK HttpHandler 代码。
- 不引 Spring MVC、不引 servlet 容器。

阶段二不在本期实现 —— 当前任务文档先行，实施时机由后续 feature 拍板。

## 7. 验收标准（阶段一必须全部过）

### 7.1 后端

- `mvn -pl z-vector-server -am clean test` 全绿，新增 + 既有测试一起跑。
- `curl -s localhost:6333/health` → `{status:"ok", version:"1.0.5", collections:N}`。
- `curl -s localhost:6333/__instance` → 7.2 §字段全在，`uptime_ms >= 0`，`collections == listCollections().size()`，`points_total == Σ getPointCount`。
- `curl -X PUT localhost:6333/collections/demo -d '{"dimension":4,"metric":"COSINE","index_type":"HNSW","index_params":{"M":16}}'` → 200，回读 `index_type:"HNSW"`，`index_params` 含 M=16。
- `curl -X PUT localhost:6333/collections/demo/points -d '{"points":[{"id":"p1","vector":[0.1,0.2,0.3,0.4],"payload":{"lang":"zh"}}]}'` → 200, `count:1`。
- `curl -s localhost:6333/collections/demo/points/count` → `{count:1}`。
- `curl -X POST localhost:6333/collections/demo/points/search -d '{"vector":[0.1,0.2,0.3,0.4],"limit":5,"filter":{"lang":"zh"}}'` → `{result:[{id:"p1",score:0,…,payload:{lang:"zh"}}], status:"ok", time_ms:0..50}`。
- `curl -s localhost:6333/collections/demo/points/p1` → `{id, vector, payload}`（单点 GET 修好后的回归验证）。
- `curl -i -X OPTIONS localhost:6333/collections -H 'Origin: http://localhost:3000' -H 'Access-Control-Request-Method: GET'`（`ZVECTOR_CORS_ORIGINS=http://localhost:3000` 已设）→ 204，`Access-Control-Allow-Origin: http://localhost:3000`，`Vary: Origin`。
- 错误：`curl -X PUT localhost:6333/collections/bad -d '{"name":"bad","dimensions":4}'`（**注意**：旧字段名 `dimensions` ≠ 新字段名 `dimension`）→ 400 `{status:"error", code:400, message:"dimension must be a positive integer..."}`。这是**字面错误命中**测试：旧字段名直接 400，不是 200 加静默建集合。

### 7.2 前端

- `cd _frontend && npm install` 不报 peer 冲突（antd 6 + React 19 当前稳定）。
- `npm run build` 退出码 0，产物 `dist/index.html` 存在，体积参考 z-opc（不加 echarts 后应 < 3 MB gzip）。
- `npm run dev` 起住 `http://localhost:3000`，同源 `vite proxy` 不报 504。
- 浏览器走查（用 browser-use MCP 实操，DOM + console 双向核对）：
  1. 进入 `/collections` → 空状态卡片「当前没有向量集合」渲染，console 0 error。
  2. 点「新建集合」→ 弹窗表单 → 填 `name=demo, dimension=4, metric=COSINE, index_type=HNSW` → 提交 → 列表多一行 `demo` 链接，4 维 COSINE HNSW，已建索引 = 否（HNSW 要显式 buildIndex 才建）→ 0 console error。
  3. 点 `demo` 进详情 → 元信息卡显示，points_count = 0。
  4. 「写入向量」→ 点「填示例结构」→ 点「提交 upsert」→ 卡片下方出「写入成功」Alert，points_count 变 1，count 端点独立查询 = 1。
  5. 顶部菜单「检索调试」→ 集合下拉自动选 `demo` → 维度 = 4 → 点「随机生成查询向量」→ top-k 5 → 执行 → 命中结果表第一行 `p1`，距离 ≈ 0。
  6. 顶部菜单「实例状态」→ 渲染出 version / port / uptime_ms / data_dir / collections / points_total / heap 内存；`embeddedRunning` 概念删掉（独立 server 本身就是服务）。
  7. 返回集合列表 → 「删除」→ 确认 → 行消失，空状态重新出现。
- 所有 axios 请求的 console.error / console.warn 数 = 0；`grep '403\\|404\\|500' dist/` 后端错误数 = 0（curl 抓 6333 + 3000 两次核对）。

### 7.3 文档收口

- 本仓根 `README.md` 「未接入」表「可视化控制台 / 前端镜像」一条改为「已接入」。
- 「架构」段的「本仓没有 `_frontend/`」措辞改为指向 `_frontend/README.md`。
- 「文档目录」段增 `_doc/001_feature/001_管理台前端/` 链接。
- `_frontend/README.md` 必须有「快速开始」「部署」「验证日志」「与 z-opc 关系」「已知差异」五段（z-opc 哪些文件搬过来、哪些改了、哪些没搬）。

## 8. 任务分解

| # | 任务 | 状态 |
| --- | --- | --- |
| 1 | 写本 feature 文档 | 进行中 |
| 2 | 后端：QdrantRestServer 暴露 handler + VectorServerApplication 切到 Qdrant REST + /__instance + CORS + time_ms + 测试 | 待 |
| 3 | 前端：搭 `_frontend/`，搬运 common + AdminShell + 通用 ui | 待 |
| 4 | 前端：console 4 页 + api.js 适配 + routes.js 数据驱动 | 待 |
| 5 | E2E：mvn test + npm build + 浏览器走查 + README 收口 | 待 |

## 9. 风险与边界

| 风险 | 触发条件 | 缓解 |
| --- | --- | --- |
| `GET /collections` 形状破坏旧脚本 | 任何外部脚本读 `array` 而不是 `{collections: array}` | README「未接入」段已声明对外协议是 OpenApiSpec 广告的形状；本特性把独立 server 拉齐到同一形状 |
| `z-vector-grpc-server` 模块名与内容不符（实为 Qdrant REST） | 后续有人 grep `grpc` 找不到端口 9090 | README 已在「未接入」段说明；本特性再补一行注释到 `pom.xml` |
| 浏览器 console 报错找不到 `echarts`（因不搬 `EChartsChart`） | 任何 import 路径残留 | `common/components/ui/index.js` 已不 re-export；后续真用图表再加依赖 |
| AntD 6 与 React 19 兼容回归 | `npm install` 后 peer 警告 | 用 z-opc 已验证过的相同版本号；保留 npm peer 警告可读，不阻断 |
| `getPointCount` 大集合下扫所有集合的 sum 很慢 | 集合数 / 点数极大 | 本期是单机 console 场景，规模在管理台量级（百级集合、十万级点），不构成瓶颈；若成瓶颈，阶段二改为 `/__instance` 异步采样 |
| 单进程 JVM 内 `__instance.jvm` 在容器里拿到容器 PID | 容器化部署 | `ProcessHandle.current().pid()` 在容器内就是容器 PID，符合预期；如需宿主机 PID，由部署侧补环境变量 `ZVECTOR_HOST_PID` 覆盖 |

## 10. 与 z-opc 关系（一份搬运台账）

| 来源（z-opc） | 去向（z-vector） | 行数 | 改动 |
| --- | --- | --- | --- |
| `bootstraps/z-opc-main-starter-frontend/src/vector/pages/VectorApp.jsx` | `_frontend/src/console/pages/VectorApp.jsx` (本期不挂路由，由 routes.js 接管) | 21 | 弃用，但保留作迁移说明 |
| `…/vector/pages/CollectionList.jsx` | `_frontend/src/console/pages/CollectionList.jsx` | 182 | 顶部 subtitle 文案 1 处更正 |
| `…/vector/pages/CollectionDetail.jsx` | `…/CollectionDetail.jsx` | 164 | 顶部 subtitle 文案 1 处更正；`/__instance` 字段对齐独立 server |
| `…/vector/pages/SearchPlayground.jsx` | `…/SearchPlayground.jsx` | 208 | 顶部 subtitle 文案 1 处更正 |
| `…/vector/pages/InstanceStatus.jsx` | `…/InstanceStatus.jsx` | 94 | 全面重写（删 z-opc proxy `embeddedRunning/acceptingNow/lifecycleBean/boundPort`，改为直读独立 server `/__instance` 的 jvm/uptime/data_dir/memory） |
| `…/vector/services/api.js` | `_frontend/src/console/services/api.js` | 63 | 改 baseURL：去掉 `/api/vector` 前缀，错误文案保留 |
| `…/common/components/Layout/index.jsx` | `_frontend/src/common/components/Layout/index.jsx` | 69 | 0 改动 |
| `…/common/utils/request.ts` | `_frontend/src/common/utils/request.js` | 254 → 80 | 去 TypeScript / 公开 API 白名单 / tenant 注入 / 401 重定向 / 默认 unwrap |
| `…/common/utils/jwt.ts` | `_frontend/src/common/utils/jwt.js` | 63 → ~50 | 去 TypeScript，逻辑保留 |
| `…/common/components/ui/{PageHeader, EmptyState, LoadingState, ErrorState, StatusBadge, SearchInput, PagedTable, SectionHeader, TableToolbar, tokens, index}.{jsx,js}` | `_frontend/src/common/components/ui/...` | 1 136 | 0 改动（index.js 砍 5 个不搬组件的 export） |
| `…/common/components/LoginPage/index.jsx` | `_frontend/src/common/components/LoginPage/index.jsx` | 67 | 0 改动（占位） |
| **未搬** | — | — | `common/components/ui/{EChartsChart, FeatureCard, GradientStatCard, DarkBanner, CategoryDot}.jsx`（本期无消费方 + 避免拉 echarts） |
| **未搬** | — | — | `src/zteam/`、`src/ctc/`、`src/agent/`、`src/lc/`、`src/wf/`、`src/...` 全仓业务组件 |

— 完 —
