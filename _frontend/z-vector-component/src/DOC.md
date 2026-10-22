# z-vector-component

z-vector（向量数据库）跨仓唯一前端面：把「集合管理 / 检索调试 / 实例状态」这一整套向量库管理台
页面收敛成一个可复用的 React 组件包。向量库本体是仿 Qdrant 风格 REST 的 Java 服务
（自研 HNSW / FLAT 索引），前端只做管理台。

- **npm 包名**：`@yuku123/z-vector-component`
- **宿主接入**：`@yuku123/z-vector-component/pages`（菜单 + 路由），主壳可只取 `ComponentDocPage`
- **后端**：z-opc 侧的 `VectorProxyController`（`/api/vector/**` → 同 JVM 内已 bind 的 Qdrant 风格 REST 面）
- **技术栈**：React 19 + antd 6 + react-router-dom 7 + axios + vite（library mode，双入口 `.` / `./pages`）

---

## 一、能力概览

路由前缀 `/z-vector`，菜单与路由都在 `src/pages-manifest.jsx` 里声明（菜单数组叫 **`menuConfig`**，
不是 `menuItems`）。

| 页面 | 路由（相对 `/z-vector/*`） | 源文件 | 能力 |
|------|--------------------------|--------|------|
| 首页 | `home` | `pages/HomePage.jsx` | 入口卡片页 |
| 集合列表 | `collections` | `pages/CollectionList.jsx` | 列表 + 新建集合 + 详情 / 检索跳转 + 删除 |
| 集合详情 | `collections/:name` | `pages/CollectionDetail.jsx` | 元信息 + 点数 + upsert 写入向量 |
| 检索调试 | `search` | `pages/SearchPlayground.jsx` | top-k ANN 查询 + payload 过滤 + 原始报文展示 |
| 实例状态 | `instance` | `pages/InstanceStatus.jsx` | port / uptime / data_dir / jvm / 内存 + actuator 端点 |
| 组件文档 | `docs` | `pages/ComponentDoc.jsx` | 本文件渲染页（左侧 240px TOC + 右侧正文） |

`src/pages/VectorApp.jsx` 是域内挂载点，被 `routes` 里的 `/z-vector/:rest*` 承接，
同时从包根具名导出（`VectorApp`）。

## 二、导出面

```js
// 主入口（. 桶）：基座 UI 组件逐个转出 + 域挂载点 + API 装配
import {EmptyState, ListTable, PageHeader, VectorApp, configureVector, vectorApi, ComponentDocPage}
    from '@yuku123/z-vector-component'

// 宿主组装面（./pages 桶）：菜单 + 路由 + apiBase 注入
import {appMeta, menuConfig, routes, configureVector} from '@yuku123/z-vector-component/pages'
```

⚠ 主入口的基座 UI 组件是**按名逐个转出**的，不是 `export *` —— `export *` 会把基座后续新增的导出
（`ListTable` / `ComponentDoc` / `GradientStatCard` …）也带进本仓的公开 API 面，
那是基座的契约变更，不该由本仓替它发布。**新增基座组件时要显式补一行。**

## 三、API 端点

前端调的是 z-opc 侧代理面 `/api/vector/**`（`request` 实例 `baseURL` 由 `configureVector` 注入，
默认 `/api`）。下表左列是前端实际请求，右列是它代理到的上游 Qdrant 风格面。

| 方法 | 前端路径 | 上游路径 | 说明 |
|---|---|---|---|
| GET | `/api/vector/__instance` | `/__instance` | 实例自省（版本 / port / uptime / jvm / 内存） |
| GET | `/api/vector/collections` | `/collections` | 集合列表（**只回名字**） |
| GET | `/api/vector/collections/{name}` | `/collections/{n}` | 集合元信息（dimension / metric / points_count / indexed） |
| GET | `/api/vector/collections/{name}/points/count` | `/collections/{n}/points/count` | 点数 |
| PUT | `/api/vector/collections/{name}` | `/collections/{n}` | 建集合 |
| DELETE | `/api/vector/collections/{name}` | `/collections/{n}` | 删集合 |
| PUT | `/api/vector/collections/{name}/points` | `/collections/{n}/points` | upsert 向量 |
| POST | `/api/vector/collections/{name}/points/search` | `/collections/{n}/points/search` | top-k ANN 检索 |

集合名与 point id 在路径上做 `encodeURIComponent` 编码。**没有**单点读取端点
（`GET /collections/{n}/points/{id}` 被前端刻意不暴露，见「已知限制」）。

### 3.1 报文形状（实测）

```ts
// upsert 请求
type UpsertReq = { points: Array<{ id: string | number, vector: number[], payload?: object }> }
// upsert 响应
type UpsertResp = { status: 'ok', count: number }
// search 响应
type SearchResp = { status: 'ok', result: Array<{ id, score, payload? }>, time_ms: number }
// instance 响应
type InstanceResp = {
    status: 'ok', version: string, port: number, uptime_ms: number,
    data_dir: string, jvm: string, java: string,
    collections: number, points_total: number,
    memory: { heap_used, heap_max, free }
}
```

## 四、页面说明

### 4.1 集合列表

- 右上角「刷新」重发 `GET /api/vector/collections`；
- 「新建集合」打开 Modal：name 字段（正则 `[a-zA-Z0-9_-]+`）+ dimension（必填 1–8192）
  + metric（默认 COSINE）+ index_type（默认 FLAT）；
- 列表「详情」→ `/z-vector/collections/:name`；「检索」→ `/z-vector/search?collection=:name`（自动填集合）；
- 「删除」`Popconfirm` 确认后 `DELETE /api/vector/collections/:name`；
- **先列表再并发取详情**：`GET /collections` 只回名字，dimension / metric / points_count 要逐个集合
  再打一次 `GET /collections/{n}`，并发发起；失败的行单独标「详情失败」，不让整张表变空。

### 4.2 集合详情

- 顶部 Descriptions 卡片显示元信息（5 行）+ 跳转「去检索」+ 刷新；
- 右侧两个 Statistic 卡片：集合声明向量数 + 独立 `points/count` 端点验证
  （两者不一致即暴露 store 计数与元信息脱节）；
- 底部「写入向量」卡片：JSON 文本框 + 「填示例结构」（按当前 dimension 一键生成）+ 「提交 upsert」；
- 维度不匹配的前端校验：`每条向量要有 id + 4 维数字数组（维度由集合决定）`。

### 4.3 检索调试

集合 Select + 刷新按钮；展示集合维度（未取到时提示「集合还没选或详情读取失败」）；
「随机生成查询向量」按当前维度生成 `[(i+1)/10 for i in range(dim)]`；top-k 默认 5（可调 1–100）；
「查询前建索引」Checkbox（HNSW 集合生效）；payload 过滤文本（JSON 对象）；
「执行检索」→ `POST /api/vector/collections/{n}/points/search`；
命中结果 Table 展示排名 / 向量 ID / score / payload；底部 `<pre>` 展示原始请求 / 响应
（证明页面渲染的就是后端给的那份）。

### 4.4 实例状态

10s 轮询 `/api/vector/__instance`；显示本仓 UP / DOWN、构建版本（来自 `/build-info.properties`）、
JVM、内存；列出 actuator 端点（health / info / mappings / env）跳转；
后端未起时优雅降级（skeleton + warn），不抛红。

### 4.5 组件文档

本文件。内容以 `?raw` 在编译期内联，零额外请求；左侧 240px TOC 随滚动联动。
**实现复用基座 `@yuku123/z-frontend-common/ui` 的 `ComponentDoc`**（本仓原先有一份 270 行的本地重写，
已按 §7 收口成薄包装）。

## 五、配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `configureVector(apiBase)` | `'/api'` | 改写请求实例的 `baseURL`（vite dev proxy / nginx 反代同源） |
| 请求超时 | 10000ms | axios 实例默认 |
| `withCredentials` | `false` | 同上 |
| 错误体解析 | `{status:'error', code, message}` | HTTP code 与 body 的 `code` 同值；`vectorErrorText(err)` 抽 message |
| `NAME_PATTERN` | `/^[A-Za-z0-9_-]+$/` | 集合名 / point id 前置校验，避免把 404 渲染成「集合不存在」 |

## 六、已知限制

- **单点读取不可用**：上游 `QdrantRestServer` 的 `extractCollectionName(path, "/points/")` 会把后缀
  按 8 个字符切掉，`/collections/demo/points/p3` 被切成集合名 `demo/p` ⇒ 稳定回
  `Collection not found: demo/p`。实测 8888（经 proxy）与 6334（直连）返回**同一份错误**，
  即这不是转发问题而是 L3 路由缺陷；前端因此**刻意不暴露**该端点。
- **HNSW upsert 撞图风险**：第 0 层出度按 `2M`（M=16 → 32）。旧版本曾因撞坏图导致 crash，
  已在 0.1.0 修复。
- **payload 过滤**：仅支持顶层 key 等值；不支持范围查询、嵌套字段、AND / OR 组合。
- **维度固定**：建集合后 dimension 不可改（后端不提供 alter 接口），只能删了重建。
- **FLAT 索引**：点数 > 10k 时 ANN 检索走未建索引路径（O(N) 暴力扫描），响应会变慢。
  前端会在元信息标「否 — 查询会走未建索引路径」作为提醒。
- **HTTP 短连接**：后端是 `com.sun.net.httpserver.HttpServer`（非 Netty），并发受限，
  单实例适合中小流量。
- `getPoint` 与 `search` 都不返回 `vector` 字段（只 id + score + payload），前端 Table 不显示向量数值。
- `time_ms` 是服务端真实测量，不是占位 0（实测 z-vector-server 1.0.5）。
- 创建集合时如果 name 已存在，PUT 不会 idempotent 返回 200，会报 400。
- 后端默认数据目录 `/data/zvector` 容器外不可写，本地 dev 需要 `ZVECTOR_DATA_DIR=/tmp/zvector-data` 覆盖。
- **Qdrant 风格 REST 面不出现在 `/actuator/mappings`**（它不是 Spring MVC，而是 JDK HttpServer 自起的），
  也不在 vite 的 `/api` 代理范围内 —— 本仓全部走 z-opc 侧 `VectorProxyController` 中转。
- 组件包必须 external 基座；`vite.config.js` 的 `external` 是精确字符串匹配，
  `@yuku123/z-frontend-common/ui` 与 `/ui/echarts` 两个子路径必须逐个列出，漏了基座会被复制进 dist。

## 七、变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| 0.1.2 | 2026-10-10 | suit 顶部分页 + 查询；AppLayout 折叠态纯 icon；菜单扁平化 |
| 0.1.1 | 2026-10-10 | 主壳 default 导入语义补 default 导出 |
| 0.1.0 | 2026-10-09 | feature001 双包首发（lead 005 §9.1 终版） |
| 0.1.0 | 2026-10-23 | 组件文档页收口：删掉 270 行本地 ComponentDoc 重写，改用基座 `ComponentDoc` 薄包装；DOC.md 按 §7 重构章节（补「导出面」） |

## 八、依赖与版本

| 依赖 | 版本 | 备注 |
|---|---|---|
| react | 19.2+ | pin 19.2.7 兼容 react-dom 19.2.7 |
| antd | 6.3+ | 折叠 sider 弹 popup 菜单 |
| react-router-dom | 7.13+ | v7 数据 API |
| axios | 1.16+ | 默认 10s 超时 |
| @yuku123/z-frontend-common | ^1.1.4 | UI 基座（`/ui` 子路径必须 >= 1.1.1，`ComponentDoc` 需要 1.1.5+） |
