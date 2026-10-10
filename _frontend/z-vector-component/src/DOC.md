# z-vector 组件文档

> z-opc 基金会「向量数据库」前端组件（`_frontend/z-vector-component`）的能力说明。

## 1. 功能

z-vector 是一个仿 Qdrant 风格 REST 的向量数据库（Java 1.8 + JDK HttpServer + 自研 HNSW/FLAT 索引），前端组件提供完整管理台。

| 页面 | 路径 | 作用 |
|---|---|---|
| 集合列表 | `/collections` | 列表 + 新建 + 详情跳转 + 检索跳转 + 删除 |
| 集合详情 | `/collections/:name` | 元信息 + 点数 + upsert（PUT /points） |
| 检索调试 | `/search` | top-k ANN 查询（POST /points/search） |
| 实例状态 | `/instance` | port / uptime / data_dir / jvm / 内存 |

## 2. 后端 API（Qdrant 风格）

```
GET    /__instance                   → 实例自省
GET    /collections                  → 集合列表
GET    /collections/{n}              → 集合元信息
GET    /collections/{n}/points/count → 点数
PUT    /collections/{n}              → 建集合
DELETE /collections/{n}              → 删集合
PUT    /collections/{n}/points       → upsert 向量
GET    /collections/{n}/points/{id}  → 单点读
DELETE /collections/{n}/points/{id}  → 单点删
POST   /collections/{n}/points/search → top-k ANN
```

字段形状（实测）：

```ts
// upsert 请求
type UpsertReq = { points: Array<{ id: string|number, vector: number[], payload?: object }> }
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

## 3. 页面说明

### 3.1 集合列表

- 右上角"刷新"重发 `GET /collections`
- "新建集合"打开 Modal：name 字段（正则 `[a-zA-Z0-9_-]+`）+ dimension（必填 1-8192）+ metric（默认 COSINE）+ index_type（默认 FLAT）
- 列表"详情" → `/collections/:name`
- 列表"检索" → `/search?collection=:name`（自动填入集合）
- 列表"删除" Popconfirm 确认后 `DELETE /collections/:name`
- 列表第一行**先列表再并发取详情**：GET /collections 只回名字，dimension/metric/points_count 要逐个集合再打一次 GET /collections/{n}，并发发起，失败的行单独标"详情失败"不让整张表变空

### 3.2 集合详情

- 顶部 Descriptions 卡片显示元信息（5 行）+ 跳转"去检索"按钮 + 刷新
- 右侧两个 Statistic 卡片：集合声明向量数 + 独立 points/count 端点验证（两者不一致即暴露 store 计数与元信息脱节）
- 底部"写入向量"卡片：JSON 文本框 + "填示例结构"（按当前 dimension 一键生成）+ "提交 upsert"
- 维度不匹配的前端校验：`每条向量要有 id + 4 维数字数组（维度由集合决定）`

### 3.3 检索调试

- 集合 Select + 刷新按钮
- 集合维度展示（未取到时"集合还没选或详情读取失败"）
- "随机生成查询向量"按当前维度生成 `[(i+1)/10 for i in range(dim)]` 格式
- top-k 默认 5，可调 1-100
- "查询前建索引" Checkbox（HNSW 集合生效）
- payload 过滤文本（JSON 对象）
- "执行检索" → `POST /collections/{n}/points/search`
- 命中结果 Table：排名 / 向量 ID / score / payload
- 原始请求/响应 `<pre>`：证明页面渲染的就是后端给的那份

### 3.4 实例状态

- 10s 轮询 `/__instance`
- 显本仓 UP/DOWN、构建版本（来自 `/build-info.properties`）、JVM、内存
- 列出 actuator 端点（health/info/mappings/env）跳转
- 后端未起时优雅降级（skeleton + warn），不抛红

## 4. 配置

- `apiBase`：默认 `/api`（vite dev proxy / nginx 反代同源），宿主换前缀时 `configureVector({ apiBase: '/其他前缀' })`。
- 请求实例：`<input>` 拼接 `apiBase + path`，`timeout: 10000ms`，`withCredentials: false`。
- 错误体解析：`{status:'error', code, message}` 三段，HTTP code 与 code 同值；前端 `vectorErrorText(err)` 抽 message。

## 5. 限制

- **HNSW upsert 撞图风险**：第 0 层出度按 `2M`（M=16 → 32）。旧版本曾因撞坏图导致 crash，已在 0.1.0 修复。
- **payload 过滤**：仅支持顶层 key 等值；不支持范围查询、嵌套字段、AND/OR 组合。
- **维度固定**：建集合后 dimension 不可改（后端不提供 alter 接口），只能删了重建。
- **FLAT 索引**：点数 > 10k 时 ANN 检索走未建索引路径（O(N) 暴力扫描），响应会变慢。前端会在元信息标"否 — 查询会走未建索引路径"作为提醒。
- **HTTP 短连接**：后端是 `com.sun.net.httpserver.HttpServer`（非 Netty），并发受限，单实例适合中小流量。

## 6. 已知坑

- `getPoint` 与 `search` 都不返回 `vector` 字段（只 id + score + payload），前端 Table 不显示向量数值。
- `time_ms` 是服务端真实测量，不是占位 0（实测 z-vector-server 1.0.5）。
- 创建集合时如果 name 已存在，PUT 不会 idempotent 返回 200，会报 400。
- 后端默认数据目录 `/data/zvector` 容器外不可写，本地 dev 需要 `ZVECTOR_DATA_DIR=/tmp/zvector-data` 覆盖。

## 7. 依赖与版本

| 依赖 | 版本 | 备注 |
|---|---|---|
| react | 19.2+ | pin 19.2.7 兼容 react-dom 19.2.7 |
| antd | 6.3+ | 折叠 sider 弹 popup 菜单 |
| react-router-dom | 7.13+ | v7 数据 API |
| axios | 1.16+ | 默认 10s 超时 |
| react-markdown | 9+ | 本页用 |
| remark-gfm | 4+ | 表格/任务列表 |
| rehype-slug | 5+ | H 标题 id |
| react-syntax-highlighter | 15+ | Prism 主题 |

## 8. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| 0.1.0 | 2026-10-09 | feature001 双包首发（lead 005 §9.1 终版） |
| 0.1.1 | 2026-10-10 | 主壳 default 导入语义补 default 导出 |
| 0.1.2 | 2026-10-10 | suit 顶部分页 + 查询；AppLayout 折叠态纯 icon；菜单扁平化 |
