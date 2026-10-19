# 项目总览

## 1. 项目简介

z-vector 是 z-opc-foundation 的**自研向量数据库**。它要解决的是「要在自己的 JVM 里（或自己的容器里）
做向量近邻检索，但不想为此养一套外部集群」：同一份 `VectorStore` 接口既能嵌进 Spring Boot 应用
（`z-vector-spring-boot-starter`），也能用 `z-vector-server` 那台裸 `main()` 进程对外提供 REST；
数据落在本地磁盘（WAL + Snapshot + 分页存储），重启后自己恢复，不依赖 etcd / Zookeeper / 对象存储。

**对外兼容 Qdrant 的 REST 协议形态**（`QdrantRestServer` 路由表 + `z-vector-protocol` 的 OpenAPI 3.0.3
文本规范），但引擎、索引、持久化三层全部自研。

**盘面实测：** 仓内共 **8** 个 `pom.xml`（根 1 + 模块 7），根 POM 的 `<modules>` 7 条；
受版本控制的 `.java` 共 **137** 个（api 16 / core 52 / storage 42 / protocol 3 / grpc-server 8 /
server 7 / starter 9）。

**坐标与版本：** `io.github.yuku123:z-vector:${revision}`（聚合 POM，`packaging=pom`），
根 POM `<properties><revision>1.0.5</revision>` 是全仓唯一定义点，子模块不写自己的 `<version>`；
`<parent>` 是 `io.github.yuku123:z-boot-parent:1.1.0`（`<relativePath/>` 留空）。

**技术栈：** Java 8（`maven.compiler.source/target=1.8`）+ Maven 多模块 +
`flatten-maven-plugin`（oss 模式，常开）+ `maven-shade-plugin` 打 server 的 fat jar +
Spring Boot 2.7 系（**只出现在 starter 侧**）+ HTTP 用 JDK 自带 `com.sun.net.httpserver.HttpServer`
（两台 server 都不是 Spring MVC）+ React 19 / Vite 6 / AntD 6（`_frontend/` 管理台）

## 2. 模块架构

```
z-vector/
├── pom.xml                          # 聚合根：parent=z-boot-parent，<revision> 单源，flatten 常开
├── Dockerfile                       # 仓根多阶段镜像（EXPOSE 6333）
├── deploy/kubernetes/z-vector.yaml  # k8s 编排：后端 z-vector-server + 前端 z-vector-frontend
├── z-vector-api/                    # 抽象层（16 个 java）
│   └── VectorStore / VectorPoint / SearchResult / VectorCollection / Filter /
│       DistanceMetric / IndexType / QuantizationType / VectorException /
│       VectorStoreFactory / namespace/
├── z-vector-core/                   # 引擎（52 个 java）
│   ├── index/                       #   Index / IndexFactory / FlatIndex / HnswIndex / IvfIndex /
│   │                                #   HnswPersistence / DiskAnnIndex
│   ├── distance/                    #   Distance / DistanceFactory / L2 / InnerProduct / Cosine / Hamming
│   ├── collection/                  #   Collection（集合生命周期与默认索引）
│   ├── filter/                      #   PayloadIndex（HashMap 倒排 + TreeMap 数值范围）
│   ├── quantizer/                   #   Quantizer / Fp16 / Int8 / Product / Binary / QuantizerFactory
│   ├── search/                      #   HybridSearch
│   ├── fts/                         #   FTSIndex / BM25 打分 / 分词
│   └── cluster/                     #   KMeansAdapter（真实聚类只经 IVF 这一条路）
├── z-vector-storage/                # 持久化（42 个 java）
│   ├── PersistentVectorStore.java   #   对外实现
│   ├── wal/                         #   WalFile / AsyncWalFile（Group Commit）
│   ├── page/                        #   Page / PageId / PageType / PageStore / MmapPageReader
│   ├── snapshot/                    #   Snapshot / PageSnapshot / HybridSnapshot / PointPageCodec
│   ├── buffer/                      #   BufferPool（LRU 页缓存）
│   ├── bloom/                       #   BloomFilter / MurmurHash3
│   ├── engine/                      #   StorageEngine
│   └── distributed/                 #   ClusterManager（纯内存记账对象，未接网络）
├── z-vector-protocol/               # 协议描述（3 个 java）
│   └── ProtocolSpec / OpenApiSpec（生成 OpenAPI 3.0.3 文本 / Map）
├── z-vector-grpc-server/            # 模块名与实现不符：QdrantRestServer（HTTP）+ VectorServiceGrpc（POJO 门面）
├── z-vector-server/                 # 独立 server：裸 main + JDK HttpServer + shade fat jar
├── z-vector-spring-boot-starter/    # ZVectorAutoConfiguration / ZVectorProperties(zvector.*) /
│                                     #   VectorHostAutoConfiguration / VectorProxyController
├── _frontend/                       # 管理台（feature001 阶段一）
│   ├── z-vector-component/          #   可复用组件：集合列表/详情、检索试验台、实例状态、组件文档
│   └── z-vector-suit/               #   独立运行壳（nginx + Dockerfile）
└── _doc/                            # 文档，见文末「文档目录」
```

## 3. 核心功能

### 3.1 统一抽象层 `z-vector-api`

- `VectorStore` 是全部能力的入口：集合生命周期（`createCollection` / `getCollection` /
  `deleteCollection` / `listCollections` / `hasCollection` / `setDefaultIndex`）、
  写入（`upsert` / `upsertBatch`）、读取（`getPoint` / `getPointCount`）、
  删除（`deletePoint` / `deletePoints`）、检索（`search` / `searchRange` / `searchBatch`）。
- 另有 `scroll`（游标翻页）与 `deleteByFilter`（按条件批量删）两个 **default 方法** ——
  默认实现抛 `UnsupportedOperationException`，**是否支持由具体 store 决定**。
- `VectorStoreFactory` 只有两个入口：`inMemory()` 与 `persistent(dataDir)`。
- `IndexType` 三个档位：`FLAT` / `HNSW` / `IVF`；`DistanceMetric` 四个档位：`L2` / `IP` /
  `COSINE` / `HAMMING`。
- `Filter` 的工厂方法：`eq` / `ne` / `gt` / `gte` / `lt` / `lte` / `inValues` / `notIn` /
  `exists` / `contains` / `and` / `or`，另有实例方法 `not()`。

### 3.2 索引与距离度量 `z-vector-core`

- `IndexFactory` 按 `IndexType` 分派到 `FlatIndex`（暴力精确检索）/ `HnswIndex`（图索引）/
  `IvfIndex`（倒排 + 聚类）。`HnswPersistence` 负责把 HNSW 图落盘为
  `<dataDir>/hnsw_<collection>.bin`，重启优先 load 而非重建。
- `DistanceFactory` 按 `DistanceMetric` 分派 4 种实现。`HAMMING` 是对 `floatToRawIntBits`
  逐维 popcount 的位汉明距离。
- `PayloadIndex` 支撑 payload 过滤（HashMap 倒排 + TreeMap 数值范围）。
- 聚类的真实唯一路径是 `IvfIndex` → `KMeansAdapter` → z-util-ml 的 KMeans。

### 3.3 持久化 `z-vector-storage`

- `PersistentVectorStore(dataDir)` 默认档位是 `(checkpointInterval=1000, useStorageEngine=true)`，
  `useStorageEngine=false` 退回同步 `WalFile`。
- `StorageEngine` 的构件：Bloom 过滤（期望元素 100,000 / 目标假阳率 0.01，MurmurHash3-x64-128
  + Kirsch-Mitzenmacher 双哈希）、`BufferPool`（256 页 LRU，暴露 hits / misses / evictions / hitRate）、
  `AsyncWalFile`（Group Commit，64 条或 10ms 满足其一即整批一次 fsync）。
- 页层：页大小 64KB，`PageStore.useMmap(true)` 打开 mmap 冷读（默认关）。

### 3.4 两台 HTTP server

| | 独立 server `z-vector-server` | Qdrant 形态 REST `QdrantRestServer` |
|---|---|---|
| 形态 | 裸 `main()` + JDK HttpServer | JDK HttpServer（**不是** Spring MVC） |
| 端口 | `6333`（环境变量 `ZVECTOR_PORT`） | `6334`（starter 侧 `zvector.server.port`，`0` = 不起） |
| 路由风格 | 扁平路径，集合名走请求体 | 路径里带集合名 |
| 装配 | `z-vector-server`（shade fat jar） | `z-vector-grpc-server`（starter 依赖它） |

两台 server 共享同一份路由表定义（`QdrantRestServer`），不会分裂成两套形状。

### 3.5 Spring Boot 装配 `z-vector-spring-boot-starter`

- 配置前缀**只有 `zvector`**（`ZVectorProperties`）：`storage-type`（默认 `in-memory`）、
  `data-dir`（默认 `/tmp/zvector`）、`default-index.{type,params}`、
  `server.{port=6334, auto-start=true}`。
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 登记两条：
  `ZVectorAutoConfiguration` 与 `VectorHostAutoConfiguration`（后者带 `VectorProxyController` /
  `VectorKBPrefixConfig` / `ZVectorEmbeddedServerConfig`）。

### 3.6 管理台 `_frontend/`

- `z-vector-component`：8 个页面（`CollectionList` / `CollectionDetail` / `SearchPlayground` /
  `InstanceStatus` / `ComponentDoc` / `HomePage` / `LoginPage` / `VectorApp`）+ 10 个通用 UI 组件
  （`EmptyState` / `ErrorState` / `LoadingState` / `PageHeader` / `PagedTable` / `SearchInput` /
  `SectionHeader` / `StatusBadge` / `TableToolbar` / `tokens.js`）。
- `z-vector-suit`：独立运行壳，自带 `nginx.conf` / `nginx.conf.template` / `Dockerfile`，
  开发期 vite dev proxy 指向后端 6333。
- 规格与路由见 `_doc/001_arch/08-design-manage-console-frontend.md`。

## 4. 对外集成 / 上下游

**上游依赖：** `io.github.yuku123:z-util-{core,math,ml}`（KMeans 聚类来自 `z-util-ml`）；
`z-vector-server` 额外用 `maven-shade-plugin` 打包，starter 侧引 Spring Boot 2.7 系
（`spring-boot-autoconfigure` / `-starter-web` / `-starter-actuator` / `-starter-test`）。

**端口（本仓可 grep 到的）：**

| 端口 | 出处 | 含义 |
|---|---|---|
| `6333` | `Dockerfile` 的 `EXPOSE 6333` 与 `ENV ZVECTOR_PORT="6333"`；`deploy/kubernetes/z-vector.yaml` 的 `ZVECTOR_PORT: "6333"` | 独立 server 的 HTTP 端口 |
| `6334` | `ZVectorProperties.Server.port` 默认值 | starter 内嵌 Qdrant 形态 REST 的端口 |
| `80` | `deploy/kubernetes/z-vector.yaml` 注释 | 前端 nginx 端口 |

**下游消费方：** 两种嵌入方式 —— ① 业务应用引 `io.github.yuku123:z-vector-spring-boot-starter`
（配置前缀 `zvector`）；② 独立部署 `z-vector-server` 的 fat jar，客户端直连 Qdrant 形态 REST。
`z-vector-storage` **不被 starter 依赖** —— `storage-type=persistent` 靠
`Class.forName("com.zifang.z.vector.storage.PersistentVectorStore")` 反射装配，缺件时只 `log.warn`
并退回 `InMemoryVectorStore`；要落盘必须自己额外引 `z-vector-storage`。

**本仓无 gRPC 服务端**：`z-vector-grpc-server` 模块零 gRPC 依赖，`VectorServiceGrpc` 是 protobuf
**风格**的 Builder POJO 门面，仓内无 `.proto` 文件、无进程监听 gRPC 端口。

## 5. 技术特点

1. **一份 `VectorStore` 两种形态**：嵌入式（starter）与独立进程（server）共用同一个接口与同一份
   路由表定义，REST 形状不分裂。
2. **HTTP 用 JDK 自带 `HttpServer` 而非 Spring MVC**：两台 server 都是
   `com.sun.net.httpserver.HttpServer`，JSON 序列化走 Jackson（近期提交把 REST / 存储 / 快照
   三处改为 `JsonUtil` + `toPlainValue`，去掉了 Jackson 依赖）。
3. **持久化三层分工**：WAL（`WalFile` / `AsyncWalFile`）扛写入，Snapshot（`Snapshot` /
   `PageSnapshot` / `HybridSnapshot`）扛全量，`PageStore` + `MmapPageReader` 扛读；
   `BufferPool` 在中间做页级 LRU。
4. **索引参数双形态接受**：`IndexFactory.intParam` 同时接受 `Number` 与 `"32"` / `"32.0"` 字符串，
   否则每次重启索引参数会静默回落。
5. **starter 与 storage 解耦**：starter 不引 storage，持久化走反射装配 + 降级告警 ——
   代价是"配了 persistent 却没引件"会静默退回内存实现，只在日志里留 `warn`。
6. **版本单源**：根 POM `<revision>` 是全仓唯一定义点，子模块不写自己的 `<version>`，
   兄弟依赖版本由父链的 `dependencyManagement` 下发。

## 6. 目录结构

本仓是 Java 后端 + 一个 React 管理台的混合仓，源码按「Maven 模块」组织：

```
z-vector/
├── pom.xml
├── z-vector-api/src/main/java/com/zifang/z/vector/api/
│   ├── VectorStore.java  VectorPoint.java  SearchResult.java  VectorCollection.java
│   ├── Filter.java  DistanceMetric.java  IndexType.java  QuantizationType.java
│   ├── VectorException.java  VectorStoreFactory.java
│   └── namespace/{Namespace, NamespaceManager}.java
├── z-vector-core/src/main/java/com/zifang/z/vector/core/
│   ├── index/       # Index / IndexFactory / Flat / Hnsw / Ivf / HnswPersistence / DiskAnn
│   ├── distance/    # Distance / DistanceFactory / L2 / InnerProduct / Cosine / Hamming
│   ├── collection/  filter/  quantizer/  search/  fts/  cluster/
├── z-vector-storage/src/main/java/com/zifang/z/vector/storage/
│   ├── PersistentVectorStore.java
│   ├── wal/  page/  snapshot/  buffer/  bloom/  engine/  distributed/
├── z-vector-protocol/src/main/java/com/zifang/z/vector/protocol/
│   └── ProtocolSpec.java  OpenApiSpec.java
├── z-vector-grpc-server/src/main/java/com/zifang/z/vector/grpc/
│   └── QdrantRestServer.java  VectorServiceGrpc.java
├── z-vector-server/src/main/java/com/zifang/z/vector/server/
│   └── VectorServerApplication.java
├── z-vector-spring-boot-starter/src/main/java/com/zifang/z/vector/starter/
│   ├── autoconfigure/  # ZVectorAutoConfiguration / ZVectorProperties
│   ├── host/           # VectorHostAutoConfiguration / VectorProxyController /
│   │                   #   VectorKBPrefixConfig / ZVectorEmbeddedServerConfig
│   └── resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
├── _frontend/
│   ├── z-vector-component/src/{index.js, pages.js, pages-manifest.jsx, pages/, ui/, services/}
│   └── z-vector-suit/{Dockerfile, nginx.conf, nginx.conf.template, src/{App.jsx, main.jsx}}
├── deploy/kubernetes/z-vector.yaml
└── _doc/
    ├── 001_arch/08-design-manage-console-frontend.md
    ├── 003_script/{deploy_maven_center.sh, verify_central.sh}
    └── 007_backlog/feature001_hnsw_v2/TASK.md
```
