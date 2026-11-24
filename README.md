# z-vector

> 自研 Java 向量数据库 —— in-process 嵌入式 + 可独立部署，ANN 检索 + Payload 过滤 + WAL/Snapshot 持久化，对外兼容 **Qdrant REST** 的协议形态。

它解决的是"要在 JVM 里（或自己的容器里）做向量近邻检索，但不想为此养一套外部集群"的问题：
同一份 `VectorStore` 接口既能嵌进 Spring Boot 应用（`z-vector-spring-boot-starter`），也能用
`z-vector-server` 那台裸 `main()` 进程对外提供 REST；数据落在本地磁盘（WAL + Snapshot + 分页存储），
重启后自己恢复，不需要 etcd / Zookeeper / 对象存储这类外部依赖。

⚠ 本 README 在 2026-09-30 做了一次**纠偏重写**：旧版广告过的 Milvus gRPC、DiskANN 索引、量化压缩、
混合检索 / FTS、多租户 Namespace、"基于 etcd 的集群"、8181/8182 端口、构件版本 1.0.1 ——
逐条回到代码里量过一遍，量不到的改写成下面「未接入」那一档的如实描述。

---

## 📋 基本信息

| 字段 | 值 |
|------|-----|
| **仓库** | `z-vector` |
| **Maven 坐标** | `io.github.yuku123:z-vector:${revision}`（聚合 POM，`packaging=pom`） |
| **当前版本** | `1.0.5`（根 POM `<properties><revision>1.0.5</revision>` 是全仓唯一定义点，子模块不写自己的 `<version>`） |
| **父项目** | `io.github.yuku123:z-boot-parent:1.2.4`（`<relativePath/>` 留空，parent 在 repo1 不在磁盘；2026-09-29 之前是 `com.zifang:z-opc:1.0.0-SNAPSHOT`，那个 pom 只在作者本机 `~/.m2` 里） |
| **Maven Central** | 已发布（repo1 逐坐标 ranged GET 实测 200）：`z-vector` / `-api` / `-core` / `-storage` / `-protocol` / `-grpc-server` / `-spring-boot-starter` / `-server` 八个坐标的 `1.0.4` 与 `1.0.5` 全部 200；`z-vector-api` 的 maven-metadata 显示 `latest = release = 1.0.5`，历史 1.0.1–1.0.5 |
| **默认端口** | **只有 HTTP，没有 gRPC**：独立 server `6333`（`ZVECTOR_PORT`）、starter 嵌入式 REST `6334`（`zvector.server.port`，`0` = 不起） |
| **运行口径** | Java 17（`maven.compiler.source/target=17`；JDK 9+ 上 `jdk9-plus-release-gate` profile 追加 `release=8` 真闸）；Spring Boot 只出现在 starter 侧 |
| **Reactor 模块** | 7 个（见结构树）；全仓 grep 不到任何 `maven.deploy.skip` ⇒ 8 个坐标（含聚合 POM）一起发 |
| **最近更新** | 2026-09-30 |

---

## ✅ 能力清单（每一条都能指到实现）

| 能力 | 实现位置 | 实测口径 |
|------|----------|----------|
| ANN 索引 | `core/index/FlatIndex` / `HnswIndex` / `IvfIndex`，由 `IndexFactory` 按 `IndexType` 分派 | **3 种**：`IndexType = {FLAT, HNSW, IVF}`（枚举里就这三个值） |
| 距离度量 | `core/distance/{L2,InnerProduct,Cosine,Hamming}Distance` + `DistanceFactory` | 4 种：`DistanceMetric = {L2, IP, COSINE, HAMMING}`；`HAMMING` 是对 `floatToRawIntBits` 逐维 popcount 的位汉明距离，不是二进制向量专用通道 |
| Payload 过滤 | `api/Filter` + `core/filter/PayloadIndex`（HashMap 倒排 + TreeMap 数值范围） | 工厂方法 `eq / ne / gt / gte / lt / lte / inValues / notIn / exists / contains / and / or`，另有实例方法 `not()` |
| 检索形态 | `api/VectorStore` | `search(topK, Filter)` / `searchRange(distance_threshold)` / `searchBatch(多 query)` / `buildIndex` / `isIndexed` / `getIndexType` / `flush` |
| 集合生命周期 | `core/collection/Collection` + `api/VectorCollection` | 建 / 查 / 删 / 列集合，`setDefaultIndex` 决定"没显式传 `IndexType`"时吃哪一种 |
| 持久化 | `storage/PersistentVectorStore` + `storage/engine/StorageEngine` | WAL + Snapshot + 分页存储 + BufferPool + Bloom；**WAL 默认同步落盘（ack 即持久，RPO = 0）**，`async` 为显式性能选项，见「本地存储」一节 |
| HNSW 图持久化 | `core/index/HnswPersistence`，由 `PersistentVectorStore#tryLoadHnswFromDisk` 调用 | 落 `<dataDir>/hnsw_<collection>.bin`，重启优先 load 而非重建 |
| mmap 冷读 | `storage/page/MmapPageReader`（`PageStore.useMmap(true)` 打开，默认关） | 读路径走 mmap，写后失效重建视图 |
| Qdrant 形态 REST | `grpc-server/QdrantRestServer`（JDK `com.sun.net.httpserver`，非 Spring MVC） | 路由与字段见「API 一览」B 表 |
| 独立 server | `server/VectorServerApplication`（全仓**唯一**一处 `public static void main`） | 4 条扁平路由 + 环境变量旋钮 |
| Spring Boot 装配 | `starter/ZVectorAutoConfiguration` + `ZVectorProperties` + `META-INF/spring/...AutoConfiguration.imports` | 配置前缀只有一个：`zvector` |
| OpenAPI 3.0.3 文本规范 | `protocol/ProtocolSpec` + `protocol/OpenApiSpec#generateSpec()` / `#getApiDocs()` | 生成 YAML / Map；**没有任何端点服务这份文档**（`OpenApiSpecRoutingTest` 只拿它核对路由表） |

### ❌ 未接入 / 只有类、没有可达路径（旧 README 当成"已实现"的那一批）

| 旧广告 | 实测结论 |
|--------|----------|
| **Milvus gRPC 服务** | `z-vector-grpc-server` 里 **零 gRPC 依赖**：模块 POM 只引 api / core / jackson / slf4j，`z-vector-protocol` 是 `test` scope。`VectorServiceGrpc` 是 protobuf **风格**的 Builder POJO 门面（`getOrCreateStore` 甚至按集合名各 new 一个 `InMemoryVectorStore`），生产代码零调用方；仓内**没有任何 `.proto` 文件**，也没有进程监听 gRPC 端口。根 POM 的 `grpc.version=1.65.1` / `protobuf.version=3.25.5` 只活在 `dependencyManagement` 里，没有模块声明它们 |
| **DiskANN 磁盘索引** | `core/index/DiskAnnIndex` + `DiskAnnIndexTest` 在树上，但它**不 `implements Index`**，`IndexType` 与 `IndexFactory` 也没有对应档位 ⇒ 无论 API 还是 REST 都建不出这种索引 |
| **量化压缩（FP16 / INT8 / PQ / BINARY）** | `core/quantizer/*` 五个类 + `api/QuantizationType` 枚举齐全，但量化器没有进 `Collection` / `VectorStore` / REST 任何一条路径：`VectorCollection` 上没有 `setQuantization`，`QuantizerFactory` 只被 `QuantizerTest` 引用。唯一真实用到聚类的是 `IvfIndex` → `KMeansAdapter` → z-util-ml 的 KMeans |
| **混合检索（RRF + Weighted）** | `core/search/HybridSearch` 只被 `HybridSearchTest` 引用；`store.hybridSearch(...)`、`SearchRequest.builder()`、`HybridConfig.rrf(...)` 这些 API **在仓库里不存在** |
| **全文检索 FTS / BM25 / 中文分词** | `core/fts/{FTSIndex,BM25Scorer,SimpleTokenizer,JiebaTokenizer,TextAnalyzer}` 存在，但除自身测试外无人调用：`createCollection` 没有 `enableFullTextSearch` 这一档，REST 也没有文本查询入口 |
| **多租户 Namespace** | `api/namespace/{Namespace,NamespaceManager}`（权限位掩码 + 配额）只被 `NamespaceTest` 引用；`VectorStore` 每个方法签名里都没有 namespace 参数 |
| **集群模式（"基于 etcd 的元数据协调 + shard 分片"）** | `storage/distributed/ClusterManager` 是一个纯 `ConcurrentHashMap` 记账对象：仓内 grep 不到 etcd / zookeeper / socket / HTTP 客户端，只有 `ClusterManagerTest` 用它。全仓没有网络通信实现 |
| **REST 的 scroll / delete-by-filter** | `QdrantRestServer` 路由表里没有这两条（`scroll`、`points/delete` 无命中）；`/collections/{name}/points` 只认 `PUT`（upsert） |
| **可视化控制台 / 前端** | `_frontend/` 阶段一（feature001）已接入：React 19 + Vite 6 + AntD 6，分开发部署（vite dev proxy → :6333），文档与快速开始见 [_frontend/README.md](_frontend/README.md)。一体化部署（server 自托管 dist）作阶段二 backlog |
| **示例里的 `Map.of` / `List.of` / `.toList()`** | 本仓 target 是 Java 8，这些 Java 9+ API 在 `-release 8` 闸下当场编译失败（commit `03ea768` 就是把它变成真闸）；旧 README 的示例因此**跑不通**，本文件示例已改成 Java 8 写法 |

---

## 🏗️ 项目结构

```
z-vector/
├── pom.xml                          # 聚合 POM：parent=z-boot-parent:1.2.4，<revision> 单源，flatten 常开
├── Dockerfile                       # 仓根多阶段镜像（当前打不出可运行件，见「部署」）
├── LICENSE                          # MIT
├── z-vector-api/                    # 抽象层：VectorStore / VectorPoint / SearchResult / VectorCollection /
│                                    #   Filter / DistanceMetric / IndexType / QuantizationType / VectorException /
│                                    #   VectorStoreFactory（只有 inMemory() 与 persistent(dataDir)）/ namespace/
├── z-vector-core/                   # 引擎：distance(4) / index(Flat,HNSW,IVF + HnswPersistence + DiskAnnIndex) /
│                                    #   collection / filter(PayloadIndex) / quantizer(4 + factory) /
│                                    #   search(HybridSearch) / fts(BM25…) / cluster(KMeansAdapter→z-util-ml)
├── z-vector-storage/                # 持久化：PersistentVectorStore + engine(StorageEngine) + wal(WalFile,AsyncWalFile) /
│                                    #   page(Page,PageId,PageType,PageStore,MmapPageReader) / buffer(BufferPool) /
│                                    #   bloom(BloomFilter,MurmurHash3) / snapshot(Snapshot,PageSnapshot,
│                                    #   HybridSnapshot,PointPageCodec) / distributed(ClusterManager，未接网络)
├── z-vector-protocol/               # 协议描述：ProtocolSpec + OpenApiSpec（OpenAPI 3.0.3 文本 / Map 生成）
├── z-vector-grpc-server/            # 模块名与实现不符：QdrantRestServer（HTTP）+ VectorServiceGrpc（POJO 门面），
│                                    #   零 gRPC / protobuf 依赖
├── z-vector-spring-boot-starter/    # ZVectorAutoConfiguration / ZVectorProperties(zvector.*) / REST 生命周期
├── z-vector-server/                 # 独立 server（裸 main + JDK HttpServer）+ shade fat jar + 自己的 Dockerfile
└── _doc/                            # 文档，见文末「文档目录」
```

依赖方向（逐个模块 POM 实测）：`api ← core ← storage`、`api ← core ← grpc-server ← starter`、
`server → core + storage + grpc-server`，`protocol` **谁也不引**（只在 `grpc-server` 的测试里以 `test` scope 出现）。
注意 feature001 之后 `z-vector-server` **直接依赖 `z-vector-grpc-server`** —— 独立 server 与内嵌
starter 共享同一份 Qdrant 风格 REST（路由表定义只有一份），OpenApiSpec 与真实路由形状不再分裂。
具体路由见 [`_doc/001_arch/08-design-manage-console-frontend.md`](_doc/001_arch/08-design-manage-console-frontend.md) §4.1。
一个容易踩的点：starter **不依赖 `z-vector-storage`** —— `zvector.storage-type: persistent` 是靠
`Class.forName("com.zifang.z.vector.storage.PersistentVectorStore")` 反射装配的，缺件时只 `log.warn`
并退回 `InMemoryVectorStore` ⇒ 要持久化必须自己额外引 `z-vector-storage`。

---

## 🔧 技术栈

| 层级 | 技术（全部取自 POM 实测） |
|------|---------------------------|
| 语言 / 运行时 | Java 17（`maven.compiler.source/target=17`；JDK 9+ 另有 `maven.compiler.release=17` 闸） |
| 构建 | Maven 多模块；`flatten-maven-plugin` **钉在 1.5.0**（1.6.0 起要求 Maven 3.6.3，而门禁机是 3.6.0，flatten 已常开 ⇒ 抬号会打死全仓构建，PomVersionContractTest 的 P5 尺盯着这条）；`maven-shade-plugin:3.5.1` 打 `z-vector-server` 的 fat jar |
| 版本供给 | `z-boot-parent:1.2.4` → 第三方地板 + 兄弟仓版本槽位均由 parent 自身下发（parent 1.1.0 起**不再经 `z-boot-dependencies` / `z-boot-fleet` 两层间接件** —— 第三方地板直接 inline 在 parent 里（`gen_train_bom.py` 生成），兄弟仓版本槽位也移进了 parent 自身）；netty 4.1.138.Final、jackson 2.18.6 已改由父链下发，本仓删掉了自钉的 `<netty.version>` / `<jackson.version>` |
| 本仓仍自钉的第三方 | `slf4j 2.0.13`、`junit-jupiter 5.10.2`（父链面值更低）、`snakeyaml 2.0`（直接条目，防被父链降到 1.30）、`grpc 1.65.1` + `protobuf 3.25.5`（**仅 dependencyManagement，无模块引用**） |
| 工具库复用 | `io.github.yuku123:z-util-{core,math,ml}`：版本 1.0.13 由 `z-boot-parent` 的兄弟仓版本槽位 `${z-util.version}` 下发（本仓已删自有的 `zutil.version` 键），并显式排除 `log4j-slf4j2-impl` 以免与 Spring Boot 的 `log4j-to-slf4j` 抢日志后端 |
| Spring Boot | 仅 starter：`spring-boot-autoconfigure` / `-starter-web` / `-starter-actuator` / `-starter-test`，模块 POM 里写的是 **`2.7.12`**（`<optional>true</optional>`），与父链地板的 2.7.18 是两套面值 —— 实际运行版本由消费方自己的 Spring Boot 决定 |
| HTTP 服务 | JDK 自带 `com.sun.net.httpserver.HttpServer`（两台 server 都是它，不是 Spring MVC），JSON 用 Jackson |
| 测试 | JUnit 5（`junit-jupiter 5.10.2`）+ `maven-surefire-plugin 3.2.5` |

---

## 🚀 快速开始

### 编译 / 安装

```bash
mvn clean install -DskipTests
```

版本只在根 POM 的 `<revision>` 出现一次、子模块一律继承，所以**模块级构建少写 `-am` 会静默拿本地
仓库里的上一发布件编译**（P3 尺就是为了这条）。

### 嵌入式（同一 JVM，Java 8 写法）

```xml
<dependency>
    <groupId>io.github.yuku123</groupId>
    <artifactId>z-vector-core</artifactId>   <!-- 需要落盘就再加 z-vector-storage -->
    <version>1.0.5</version>
</dependency>
```

```java
// VectorStoreFactory 只有 inMemory() / persistent(dataDir) 两个入口
VectorStore store = VectorStoreFactory.persistent("/tmp/z-vector-data");

store.createCollection("products", 768, DistanceMetric.COSINE,
        IndexType.HNSW, Collections.singletonMap("M", 16));

Map<String, Object> payload = new HashMap<String, Object>();
payload.put("name", "iPhone 15 Pro");
payload.put("price", 999);
List<VectorPoint> batch = new ArrayList<VectorPoint>();
batch.add(new VectorPoint("p1", embedText("iPhone 15 Pro"), payload));
store.upsertBatch("products", batch);

List<SearchResult> hits = store.search("products", embedText("Apple 手机"), 10,
        Filter.and(Filter.eq("name", "iPhone 15 Pro"), Filter.lt("price", 3000)));
for (SearchResult h : hits) {
    System.out.println(h.getVectorId() + " " + h.getScore() + " " + h.getPayload());
}

store.flush("products");   // checkpoint：写 snapshot + HNSW 图，并截断 WAL
store.close();             // close() 是 VectorStore 接口上的方法，PersistentVectorStore 会落盘
```

索引参数键名（实测 `IndexFactory`）：HNSW 认 `M` / `efConstruction` / `efSearch`（默认 16 / 200 / 50），
IVF 认 `nlist` / `nprobe` / `maxIter`（默认 64 / 8 / 20）；持久化层把参数存成字符串，
`IndexFactory.intParam` 同时接受 `Number` 与 `"32"` / `"32.0"` 两种形态，否则每次重启索引参数会静默回落。

`VectorStoreFactory.persistent(dataDir)` 走的是**同步 WAL 默认档**：`upsert()` 返回即持久，**RPO = 0**。
要换异步（性能换 RPO）用 `ZVECTOR_WAL_MODE=async`（或 `-Dzvector.wal.mode=async`），
数字与公式见 [WAL 落盘模式与 RPO](#wal-落盘模式与-rpo默认同步rpo--0)。

### Spring Boot 应用

```xml
<dependency>
    <groupId>io.github.yuku123</groupId>
    <artifactId>z-vector-spring-boot-starter</artifactId>
    <version>1.0.5</version>
</dependency>
<!-- storage-type=persistent 时还要显式补一件：io.github.yuku123:z-vector-storage:1.0.5 -->
```

配置面前缀**只有 `zvector`**（下面是 `ZVectorProperties` 的全部键；`z.vector.enabled/mode/host/port`
那一族在代码里不存在，Spring Boot 会连警告都不打地静默忽略）：

```yaml
zvector:
  storage-type: in-memory     # in-memory（默认）/ persistent
  data-dir: /tmp/zvector      # 默认 /tmp/zvector，仅 persistent 时用到
  checkpoint-interval: 1000    # 每多少条 WAL 记录做一次快照 + 截断 WAL；默认 1000
  wal:
    mode: sync                # sync（默认，ack 即持久，RPO=0）/ async（性能选项，RPO≠0）
  server:
    port: 6334                # 嵌入式 Qdrant 形态 REST 端口；0 = 不启动 REST
    auto-start: true          # false = 连 REST 生命周期 Bean 都不建
  default-index:
    type: HNSW                # 留空 = 不覆盖，走 store 内置的 FLAT
    params:
      M: 16
      efConstruction: 200
```

`default-index` 只管"没说用哪种索引"的那一支（`createCollection(name, dim, metric)`）；显式传了
`IndexType` 的调用照本宣科。写一个不存在的索引名会让应用启动失败（`IllegalStateException` 里点名
`zvector.default-index.type`），不会静默退回 FLAT。

`wal.mode` 是**持久性语义**（不是调优开关）：`sync` = 每条写入当场 fsync，`upsert()` 返回即持久，
**RPO = 0**；`async` = 入队即返回、后台整批 fsync，崩溃会丢「队列中尚未落盘的那一段」
（数字与公式见 [WAL 落盘模式与 RPO](#wal-落盘模式与-rpo默认同步rpo--0)）。配 `async` 时启动日志会打
**警告文案**把代价说清楚；`wal.mode` 写错值会让应用启动失败（点名 `zvector.wal.mode`），
显式配了却兑现不了（比如 `z-vector-storage` 不在 classpath 上）也**拒绝启动** ——
退回内存存储会让显式的持久性要求静默失效。

### 独立 server（REST，默认 6333）

```bash
mvn -pl z-vector-server -am clean package -DskipTests
ZVECTOR_DATA_DIR=/tmp/zvector java -jar z-vector-server/target/z-vector-server-1.0.5.jar
curl -s localhost:6333/health
# {"status":"ok","version":"1.0.5","collections":0}
```

`z-vector-server` **不是 Spring Boot 应用**，是裸 `main()` + JDK HttpServer（shade 出的 fat jar；
瘦身后那份叫 `original-z-vector-server-<ver>.jar`）。旋钮只有环境变量
（`--z.vector.port` 一类命令行属性它压根不解析）：

| 环境变量 | 默认 | 说明 |
|----------|------|------|
| `ZVECTOR_PORT` | `6333` | HTTP 监听端口；`0` = 让内核挑一个空闲端口（测试用）；越界直接启动失败 |
| `ZVECTOR_DATA_DIR` | `/data/zvector` | 数据目录（WAL + Snapshot + HNSW 图） |
| `ZVECTOR_DEFAULT_INDEX` | 空 | 不带 `index_type` 的建集合请求用哪个索引：`FLAT` / `HNSW` / `IVF`，大小写不敏感；空串 = 不覆盖，走内置 `FLAT` |
| `ZVECTOR_INDEX_PARAMS` | 空 | 上面那个索引的参数，一个 JSON 对象，例如 `{"M":16,"ef_construction":200}` |
| `ZVECTOR_WAL_MODE` | `sync` | WAL 落盘模式：`sync` = 每条写入当场 fsync（ack 即持久，**RPO = 0**）；`async` = 后台整批 fsync（性能更好，**RPO ≠ 0**，崩溃会丢已 ack 的一段）。拼错直接启动失败 |

拼错不降级：`ZVECTOR_DEFAULT_INDEX=SPARSE` 或 `ZVECTOR_INDEX_PARAMS='M=7'` 会让进程启动即失败，
日志里点名是哪一个变量。`ZVECTOR_WAL_MODE=asnyc` 同理。`/health` 的 `version` 来自构建期资源过滤写进
`build-info.properties` 的 `${project.version}`，不是手抄字面量。SIGTERM 走 shutdown hook：
`server.stop` + 关线程池 + `store.close()`。

---

## 🔌 API 一览

仓里有**两台语义不同的 HTTP server**，别把字段混用（`OpenApiSpec` 头部也专门写了这条警告）。

### A. 独立 server `z-vector-server`（默认 6333，扁平路径，集合名走 body）

| 方法 | 路径 | 请求体 / 说明 |
|------|------|---------------|
| `GET` | `/health` | `{status, version, collections}` |
| `GET` | `/collections` | 集合名列表 |
| `PUT` / `POST` | `/collections` | `{name, dimensions, metric?, index_type?, index_params?}`：`name` 必填、`dimensions` 必填且为正整数（`4` 与 `4.0` 都收）、`metric` 默认 `COSINE`；响应**回读真正建出来的** `index_type` / `index_params` |
| `POST` / `PUT` | `/points` | `{collection, points:[{id, vector, payload}]}` → `upsertBatch`，返回 `{status, upserted}` |
| `POST` | `/search` | `{collection, vector, limit?}`（`limit` 默认 10）→ **这一台不做过滤**：调的是 `search(..., filter=null)`，写了 `filter` 也不生效 |

其它方法（包括 `DELETE /collections`）一律 405；`IllegalArgumentException` / `VectorException`
统一映射成 400，其它异常 500。

### B. Qdrant 形态 REST `QdrantRestServer`（starter 默认 6334，路径里带集合名）

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/collections` | 集合列表 |
| `PUT` | `/collections/{name}` | 建集合：`dimension`（**可缺省，缺省 128**）、`metric`、`index_type`、`index_params`；响应回读真实 `dimension` / `metric` / `index_type` |
| `GET` / `DELETE` | `/collections/{name}` | 取集合信息 / 删集合 |
| `PUT` | `/collections/{name}/points` | 批量 upsert（**只认 PUT**） |
| `GET` / `DELETE` | `/collections/{name}/points/{id}` | 取点 / 删点 |
| `POST` | `/collections/{name}/points/search` | `{vector, limit?, filter?, build_index?}` → `{result:[{id, score, payload?}], status, time_ms}`（`time_ms` 恒为 0，是占位） |
| `GET` | `/collections/{name}/points/count` | 点数 |

这一台**没有 `/health`**（会路由到 404），也**没有 scroll / delete-by-filter**。

`filter` 的 JSON 语法（实测 `parseFilter` / `parseCombinator` / `parseCondition`）：

- 组合子：`must` / `and`、`should` / `or`、`must_not`、`not`；同一对象里多个 key 隐式 AND
- 条件：裸值等价于等值（`{"lang":"zh"}`）；对象形态支持 `eq, ne, gt, gte, lt, lte, in, nin, exists, contains`
- 数值范围可写两项：`{"score":{"gte":0.2,"lte":0.6}}`；`{"exists":false}` 表示"该字段不存在"
- 解析不出来一律 400，不会把"没筛过"当成结果发出去

```bash
# B 台（6334）
curl -s -X PUT localhost:6334/collections/docs \
  -d '{"dimension":4,"metric":"L2","index_type":"HNSW","index_params":{"M":7}}'
curl -s -X PUT localhost:6334/collections/docs/points \
  -d '{"points":[{"id":"d1","vector":[0.1,0.2,0.3,0.4],"payload":{"lang":"zh"}}]}'
curl -s -X POST localhost:6334/collections/docs/points/search \
  -d '{"vector":[0.1,0.2,0.3,0.4],"limit":3,"filter":{"lang":"zh"}}'
```

---

## 💾 本地存储（z-vector-storage 实测件清单）

`PersistentVectorStore(dataDir)` 的默认档位是 `(checkpointInterval=1000, useStorageEngine=true, walMode=SYNC)`
—— **WAL 默认同步落盘**，`upsert()` 返回即持久（**RPO = 0**），
`useStorageEngine=false` 退回同步 `WalFile`（同样恒为同步）。`StorageEngine` 默认：Bloom 期望元素 `100_000` /
目标假阳率 `0.01`（MurmurHash3-x64-128 + Kirsch-Mitzenmacher 双哈希，无假阴性）、BufferPool `256` 页
（LRU，暴露 `hits / misses / evictions / hitRate`）；WAL 落盘由 `walMode` 决定：`sync` 走
`WalFile.append`（每条 1 次 write + 1 次 fsync），`async` 才起 `AsyncWalFile` Group Commit
（64 条 / 10ms 满足其一即整批一次 fsync，暴露 `totalAppended / totalFlushed / totalBatches / queueSize`）。
页大小 64KB，header 17B（magic `ZVP1`）+ 尾部 4B CRC32。**这些都在 Java 构造器上，不在 yml 里**：

```java
VectorStore store = new PersistentVectorStore("/data/zvector", 1000, true);            // 默认 = 同步 WAL
VectorStore async = new PersistentVectorStore("/data/zvector", 1000, true, WalMode.ASYNC); // 显式异步
StorageEngine engine = new StorageEngine("/data/zvector", wal, 100_000L, 0.01, 256);
```

### WAL 落盘模式与 RPO（默认同步，RPO = 0）

| 模式 | 写路径 | `upsert()` 返回时 | RPO（已 ack 却丢的条数） |
|---|---|---|---|
| `sync`（**默认**） | 每条记录 1 次 write + 1 次 fsync（`WalFile.append`） | **数据已在磁盘上** | **0 条** |
| `async` | 入队即返回，后台 flusher 按 64 条 / 10ms 整批 fsync（`AsyncWalFile`） | 数据可能还只在内存队列里 | 队列中尚未落盘的那一段 |

**数字**（来自崩溃恢复一致性套件 `./_doc/003_script/run_crash_consistency_suite.sh`，固定种子可复现；
`kill -9` 另一个 JVM 之后重启对账）：

| 模式 | `kill -9` 后已 ack 写入的丢失条数 | 口径 |
|---|---|---|
| `sync`（默认） | **0 / 200 条** | 套件 `--strict` 口径：已 ack 的写入一条不丢 |
| `async` | **1~4 / 126 条** | 自然窗口（fsync 正常速度） |
| `async` | **200 / 200 条** | 人为拖慢 fsync（模拟慢盘 / IO 抖动），全批丢光 |

**写吞吐的代价（before / after 对照）**（来自 `./_doc/003_script/run_wal_mode_benchmark.sh`；
1000 点 / dim 64 / 批 64，**单独跑这个脚本**、连跑 3 次取中位；本机 Mac OS X 26.6.2 + JDK 23.0.2）：

| 写法 | before = `async` | after = `sync`（默认） | 代价 |
|---|---|---|---|
| 单条 `upsert()` | 71,380 ops/s | 29,066 ops/s | 慢 **2.46×**（-59%） |
| 批量 `upsertBatch()`（64/批） | 156,269 ops/s | 32,171 ops/s | 慢 **4.86×**（-79%） |
| p50 延迟（单条 / 批量） | 0.010ms / 0.303ms | 0.028ms / 1.899ms | — |

⚠ **绝对值依赖环境，同批跑会漂**：上表是单独跑基准脚本的中位值；同一个基准混在全量测试里跑
（磁盘 / GC 状态不同）实测到 `async` 139,262 ops/s、`sync` 21,799 ops/s —— **方向一致、比值更大**。
结论只用相对关系，别拿绝对值当 SLA。
⚠ **这组数字强依赖 fsync 速度**：本机 fsync ≈ 30µs（APFS + NVMe），比 `WalFile` javadoc 里记的
「一次 fsync 8.3ms」的机器快两个数量级 ⇒ **慢盘上代价会远大于上面的倍数**（同步档会退化成 fsync 的速度）。

⚠ **`sync` 档下 `upsertBatch()` 没有走 group commit**：`WalFile.appendBatch`（一批一次 write + 一次 fsync）
只在 `async` 档的后台 flusher 里被调用（`AsyncWalFile.flushBatch`）；同步档的批量写仍是**逐条 fsync**
（`PersistentVectorStore.upsertBatch` 逐点调 `walAppend`）⇒ 批量吞吐比异步档低约 4.9×。
补这一段要给 `StorageEngine` 加 `appendWalBatch`（引擎的 WAL 提交路径）—— 未在本次修复，留档待立项。

**公式**：

- `sync`：`RPO = 0 条`（返回前已完成 fsync ⇒ 崩溃只会丢"还没返回给调用方"的写入）。
- `async`：`RPO ≤ min(队列容量 10000, 当前批 64 + 本次 fsync 期间到达的条数)` **条**；
  时间上界 `≤ 10ms（DEFAULT_MAX_LATENCY_MS）+ 一次 fsync 耗时`，
  **flusher 被阻塞时无上界**（那就是 200/200 全丢那一档）。
- `RPO` 与 `checkpointInterval` **无关**：checkpoint 只决定"崩溃后要重放多少条 WAL"，
  不决定"已 ack 的数据还在不在"。

**`checkpointInterval` 语义**（`zvector.checkpoint-interval`，默认 `1000`）：

- 每累计 N 条 WAL 记录触发一次 checkpoint：写快照（`snapshot.bin` / `psnap_*` / `hnsw_*.bin`）
  → 追加一条 CHECKPOINT 记录 → **截断 WAL**。
- 它决定两件事：**崩溃恢复要重放的条数**（≤ N 条）与**快照写放大**（每 N 条一次全量快照）。
- 调小 ⇒ 恢复重放更快、快照更频繁（写放大更大）；调大 ⇒ 快照更少、重放更多。**两者都不改变 RPO**。
- `useStorageEngine=false`（没有异步队列）时 WAL 恒为同步落盘：此时配 `wal.mode=async`
  属于配置矛盾，构造器直接抛 `IllegalArgumentException`（不静默降级）。

**怎么显式选 async（性能换 RPO）**：

| 入口 | 配置 |
|---|---|
| Spring Boot | `zvector.wal.mode: async`（yml）/ `zvector.wal.mode=async`（properties） |
| 裸 `main()` 进程 | 环境变量 `ZVECTOR_WAL_MODE=async` 或系统属性 `-Dzvector.wal.mode=async` |
| Java API | `new PersistentVectorStore(dir, 1000, true, WalMode.ASYNC)` |

选 `async` 会有**警告文案**（存储层 + 装配层各一条，名字与取值都点名）：

> WAL 异步模式已启用（walMode=ASYNC）：upsert() 返回时数据可能还只在内存队列里，
> 进程被 kill -9 / 机器断电会丢失已 ack 的写入（RPO ≠ 0）。
> 需要「ack 即持久」（RPO = 0）请用默认的 WalMode.SYNC。

写错的取值（`asnyc`）一律**抛异常让应用起不来**，不静默退回 `sync`。

```
<dataDir>/
├── wal.log                      # 当前 WAL 段：[magic4][op1][ts8][nameLen2][name][payloadLen4][payload][crc4]
├── wal_<n>.log                  # rotate 出来的历史段（默认段上限 16MB），重放按 n 升序
├── snapshot.bin                 # v1 全量快照：Jackson JSON 装进带长度的二信封 + CRC
├── psnap_meta.bin               # v2 增量 page manifest（PageSnapshot.META_FILE）
├── psnap_<cid>_<type>_<no>.bin  # v2 单 page 快照
├── hnsw_<collection>.bin        # HNSW 图，按集合
└── pages_<collectionId>.pgs     # 分页存储，每集合一个文件（± 一对 collectionId 不再共用同一份）
```

读路径优先 v2 `PageSnapshot`，缺失时回退 v1 `snapshot.bin` 并在下次 checkpoint 迁移；
mmap 冷读要显式 `PageStore.useMmap(true)`（默认关，写后自动失效旧视图）。
`ClusterManager`（`storage/distributed`）**不参与**以上任何一条：它只是内存 Map，没有网络。

**集合名没有任何字符校验，而它会直接参与磁盘路径拼接**——`hnsw_<collection>.bin` 是
`Paths.get(dataDir, "hnsw_" + name + ".bin")`，`deleteCollection` 还会对它无条件
`Files.deleteIfExists`。集合名从 `QdrantRestServer` 的 REST 端点与 `VectorServiceGrpc`
**客户端原样透传**进来，那台 REST server 没有鉴权。

照实说：**当前实测越不了界，但挡住的不是校验，是那个文件名前缀**。`hnsw_` 把第一段粘成了
`hnsw_..`——一个普通目录名而不是 `..`；OS 逐段解析路径必须先进入该目录才谈得上处理后面的
`..`，而全仓没有任何 API 能在 `dataDir` 下造出那个目录（`PageStore` 的文件名用的是
`collectionName.hashCode()` 这个 int，`WalFile` 只对 `dataDir` 本身建目录）。

`CollectionNamePathEscapeTest` 把这条边界钉成护栏：**把 `hnsw_` 前缀摘掉，受害文件真的会被删**
（变异实测 `expected: <true> but was: <false>`）。若将来有人"简化"这段拼接、
改成先 `normalize()` 再拼、或引入能按名字建目录的路径，那道测试就会红。
补一道显式的集合名白名单校验（禁 `/` 与 `..`）能把偶然安全变成真安全，属防御纵深，
但那会改变 API 行为（现在能用的某些名字会被拒），已报给用户，未擅自加。

---

## 🧪 测试

```bash
mvn clean test                       # 全量
mvn -o -fae clean test               # 离线 + 失败继续（_doc 里跨 JDK 对账用的就是这条）
mvn -pl z-vector-storage -am test    # 单模块（务必带 -am）
```

必须带 `clean`：陈旧的 surefire 报告会虚报测试类数（`_doc` 的登记原话）。用例不依赖任何外部服务，
读写都在临时目录里；Java 8 线还受 flatten 版本闸（P5）与 `jdk9-plus-release-gate` 保护。

静态清点（`rg -c "@Test"` + 文件枚举实测。**本次文档任务不跑 `mvn`，故只报件数、不声明通过数**；
历史上最近一次全量对账读数在 `_doc/007_backlog/feature001_hnsw_v2/TASK.md` §4）：

| 模块 | 测试类 | `@Test` 数 | 主要覆盖 |
|------|--------|-----------|----------|
| z-vector-api | 4 | 30 | Filter 表达式、枚举、POJO、Namespace |
| z-vector-core | 21 | 170 | 4 种距离、Flat/HNSW/IVF、HNSW 图形状与持久化、PayloadIndex 快路径、量化器、DiskAnnIndex、FTS、HybridSearch、KMeansAdapter |
| z-vector-storage | 26 | 185 | WAL（group commit / rotate / 崩溃恢复）、PageStore / BufferPool / Bloom、PageSnapshot / PointPageCodec、mmap 生命周期、PersistentVectorStore、StorageEngine、**崩溃恢复一致性套件（kill -9 三时点 × 三口径）**、**WAL 落盘模式契约（默认 sync / 显式 async / 拼错即失败）**、**WAL 模式写吞吐对照基准（before / after）** |
| z-vector-protocol | 1 | 6 | ProtocolSpec / OpenAPI 生成 |
| z-vector-grpc-server | 7 | 40 | `QdrantRestServerTest`、`QdrantRestFilterContractTest`、`QdrantRestIndexContractTest`、`QdrantRestServerPortLifecycleTest`、`OpenApiSpecRoutingTest` |
| z-vector-spring-boot-starter | 5 | 26 | 自动装配 + REST 生命周期 + **`zvector.wal.*` 兑现契约（默认值 / 拼错即失败 / 显式配了却兑现不了就拒绝启动）** |
| z-vector-server | 6 | 49 | 独立 server 索引契约 + POM 版本契约（P1/P2/P3/P5） |
| **合计** | **70** | **506** | |

> 件数一栏 2026-10-22 重新实测（`find <module>/src/test -name '*Test.java'` +
> `grep -rho '@Test' <module>/src/test`）。此前几行读数偏旧（grpc-server / starter / server 三行），
> 本次一并对齐；同日补 WAL 落盘模式契约两道尺后（storage 24→25 类 / 177→183 条，
> starter 4→5 类 / 20→26 条）再次实测；同日再补「混合写操作持久性探针 + WAL 模式写吞吐基准」
> （storage 25→26 类 / 183→185 条，合计 69→70 类 / 504→506 条）。
> **仍只报件数、不声明通过数** —— 通过数要看 CI 跑批的日志。

注意 `VectorServiceGrpc` **一条测试都没有**（旧 README 记的 `VectorServiceGrpcTest 7 条` 已不在树上），
这与"它零调用方"是同一件事的两面。

### 💥 崩溃恢复一致性验证（kill -9 / 断电模拟）

> 「持久化是真的」的唯一证明方式：**写入 N 条 → 从另一个 JVM 里 `kill -9` → 重启 → 三口径对账**。

```bash
./_doc/003_script/run_crash_consistency_suite.sh            # 三时点矩阵必须全绿
./_doc/003_script/run_crash_consistency_suite.sh --strict   # 连「已 ack 写入的持久性」探针也必须绿
mvn -pl z-vector-storage test -Dtest=CrashRecoveryConsistencyTest   # 直接跑套件
./_doc/003_script/run_wal_mode_benchmark.sh                  # WAL 模式写吞吐 before/after 对照
```

> 跑套件前确认环境里**没有** `ZVECTOR_WAL_MODE=async`（或 `-Dzvector.wal.mode=async`）：
> 那是进程级默认，子进程会跟着切到异步档，探针就会红（那是 `async` 的固有代价，不是缺陷）。

| 时点档 | 断点位置 | 期望 |
|---|---|---|
| `PRE_CHECKPOINT` | checkpoint **前**（全部数据只在 WAL 里） | 三口径全对，条数恰好 N |
| `POST_CHECKPOINT` | checkpoint **后**（快照已落盘、WAL 已截断，之后又有新写入） | 快照 + WAL 重放合起来恰好 N |
| `MID_WAL` | WAL **半途**（写入途中被 kill；另叠一档亲手把 `wal.log` 截断在记录中间的确定性半截尾） | 条数落在区间内，且恢复出来的必须是写入序列的**前缀** |

三口径（每档都自动断言，不靠人眼看日志）：

1. **collection 计数** —— `getPointCount` 与全量 `scroll` 去重后的条数必须相等（无幽灵点 / 重复点）；
2. **HNSW 图连通性** —— 不是"文件在不在"，而是**图还能不能查**：每个点用自己向量查自己必须命中自己，
   且从入口点 BFS 必须覆盖全图；
3. **payload 索引** —— 倒排必须**重建**（过滤要走倒排快路径，不是逐点兜底扫描），且过滤结果行数精确。

* **可重复执行**：数据集固定种子（`seed=20261022`，`dim=16`），断言基线与机器无关；每次跑用独立临时目录。
* **可挂 CI**：脚本退出码 0/1 可直接当门禁；产物 `z-vector-storage/target/crash-consistency/report.{json,md}`
  （JSON 给脚本读，Markdown 给人看）。
* **「已 ack 写入的持久性」探针**：第四档专门放大 fsync 窗口（子进程握住底层 WAL 的监视器不放），
  断言 `kill -9` 之后**已 ack 的写入一条不丢**。
  * **默认档（`wal.mode=sync`）现在是绿的**：默认改成同步落盘后，探针丢失 **0 / 200 条**，
    `--strict` 口径下通过（这正是"默认同步 WAL"这条改动要兑现的东西）。
  * **切到 `wal.mode=async` 会红**：异步落盘时已 ack 的写入在崩溃瞬间可能还在内存队列里
    （实测自然窗口丢 **1~4 / 126 条**；人为拖慢 fsync 时 **200 / 200 条**全丢）。
    这是 `async` 这个**显式性能选项**的固有代价，不是默认档的缺陷。
* **「已 ack 的混合写操作」探针**：第五档把 `createCollection` + `upsert` + `deletePoint` 全走一遍
  （**刻意不设持久化屏障** —— 屏障是普通调用方不会做的事，设了就等于把"ack 即持久"的契约偷换掉），
  断言崩溃后：集合还在、条数恰好是「写 N 条 - 删 M 条」、**删掉的点没有复活**、**该留着的点一条不少**。
  * 默认档实测：集合在 / 共 **150 条**（200 写 - 50 删）/ 删了却还在 **0** 条 / 该在却没了 **0** 条。

---

## 🐳 部署

```bash
# 唯一可复现的一条路：独立 server fat jar → 镜像（build context 就是 z-vector-server 目录）
mvn -pl z-vector-server -am clean package -DskipTests
docker build -f z-vector-server/Dockerfile -t z-vector:local z-vector-server
docker run -d -p 6333:6333 -e ZVECTOR_DATA_DIR=/data/zvector \
  -e ZVECTOR_DEFAULT_INDEX=HNSW -e ZVECTOR_INDEX_PARAMS='{"M":16,"efConstruction":200}' \
  z-vector:local
```

- [`z-vector-server/Dockerfile`](z-vector-server/Dockerfile)：`eclipse-temurin:8-jre`、非 root uid `10001`、
  `EXPOSE 6333`、`HEALTHCHECK` 打 `/health`、JVM 参数变量名是 **`JAVA_OPTS`**、jar 用 glob
  （`COPY target/z-vector-server-*.jar`）不写死版本号；数据目录 `/data/zvector`。
- [`Dockerfile`](Dockerfile)（仓根，多阶段）实测**打不出可运行镜像**，别照它部署：构建上下文只 COPY 了
  api / core / storage / protocol / grpc-server 五个模块（**没有 `z-vector-server`**），最后 `COPY` 的却是
  `z-vector-grpc-server` 的普通 jar —— 该模块没有任何打包插件、MANIFEST 里没有 `Main-Class`，
  `java -jar` 直接 "no main manifest attribute"；`EXPOSE 9090` 也没有任何进程监听。
  它头部注释里"parent 是 `com.zifang:z-opc:1.0.0-SNAPSHOT` 所以换台机器读不动 pom"那一条**已过期**
  （2026-09-29 parent 已迁到 `z-boot-parent:1.2.4`、现已到 `1.1.0`，repo1 实测 200），但上面两条依旧成立。
  这一份怎么修已记进 `_doc/007_backlog/feature001_hnsw_v2/TASK.md` 等拍板。
- 发布：[`_doc/003_script/deploy_maven_center.sh`](_doc/003_script/deploy_maven_center.sh)
  子命令 `publish` / `verify` / `gpg-init` / `readme` / `help`；凭证只从被 `.gitignore` 排除的 `.env`
  读取（键名 `CENTRAL_USERNAME`、`CENTRAL_TOKEN`、`CENTRAL_GPG_PASSPHRASE`，密钥环走
  `GNUPGHOME=./.gnupg`）。本 README 不含任何凭证值。
- 仓内**没有** `docker-compose.yml`、`deploy/`、`k8s/`、`Makefile`。旧 README 里的 compose 片段与
  StatefulSet YAML 是手抄示例（含 `ghcr.io/z-opc-foundation/z-vector:1.0.1` 镜像地址，本机无法证实其在位，
  且 1.0.1 早已落后两个发布件），已从本文件撤下；k8s 清单待 `_doc/002_deploy/` 有实物后再写。

---

## 📄 License

MIT —— 见根 [`LICENSE`](LICENSE)（`Copyright (c) 2026 z-opc-foundation`），根 POM `<licenses>` 同为 MIT License。

---

## 文档目录

本项目文档统一收口在 `_doc/` 下：

- [`_doc/001_arch/`](_doc/001_arch/) — 架构与规格：
  - [`00-overview.md`](_doc/001_arch/00-overview.md) — 项目总览：模块架构、核心功能、上下游、目录结构
  - [`08-design-manage-console-frontend.md`](_doc/001_arch/08-design-manage-console-frontend.md) — feature001 规格：后端 REST 统一 + `/_frontend/` 管理台（React 19 + Vite 6 + AntD 6）
- [`_doc/003_script/`](_doc/003_script/) — 运维脚本:
  - [`deploy_maven_center.sh`](_doc/003_script/deploy_maven_center.sh) — Maven Central 发布 / 校验 / GPG 初始化
  - [`verify_central.sh`](_doc/003_script/verify_central.sh) — 已发布构件的坐标与版本校验
- [`_doc/007_backlog/`](_doc/007_backlog/) — 待办与收口登记:
  - [`feature001_hnsw_v2/TASK.md`](_doc/007_backlog/feature001_hnsw_v2/TASK.md) — #20「版本号单源」收口登记 +
    四把尺（P1/P2/P3/P5）与 13 支变异电池读数 + 跨 JDK 对账。其 §5.4 记的「gRPC 广告与端口三个互相
    不打照面」「OpenApiSpec 生成了但没有端点服务它」两条，正是本 README「未接入」一节的上游依据。
    注意该目录虽名为 `feature001_hnsw_v2`，里面**不是** HNSW v2 的方案本体（文首已自注这次错位）

`deploy_maven_center.sh` 注释里指向的 `发布指引.md` 在本仓不存在（脚本自身是该目录里唯一的实体）。

_Maintained by the z-opc-foundation organization._

---

<!-- z-opc-foundation-sibling-registry:v1 -->
## 🔗 兄弟仓全景（z-opc-foundation 组织）

本仓隶属于 [z-opc-foundation](https://github.com/orgs/z-opc-foundation/repositories) 组织——一人公司基座的全部自研件（现存 61 仓，含私有仓；口径 2026-11-24 GitHub org API 实读）。

| 仓 | 一句话说明 |
|---|---|
| [z-agent](https://github.com/z-opc-foundation/z-agent) | 云端 Agent 平台的库与 starter——ReAct/Plan 实现 + 注册中心 + REST 网关 + 只读控制面 |
| [z-agent-kernel](https://github.com/z-opc-foundation/z-agent-kernel) | Agent 框架基座——消息/工具/记忆/provider/会话 SPI + 轻量默认实现（纯 POJO 无 Spring） |
| [z-asset](https://github.com/z-opc-foundation/z-asset) | 企业资产台账——实物设备全生命周期 + 域名/证书/许可到期提醒 |
| [z-audit](https://github.com/z-opc-foundation/z-audit) | 审计与合规留痕——事件模型 + 哈希链 + 事件出口 SPI + 留存期策略 |
| [z-biz](https://github.com/z-opc-foundation/z-biz) | 商务外壳域——客户/合同/票据/对账四聚合 |
| [z-biz-erp](https://github.com/z-opc-foundation/z-biz-erp) | 进销存 ERP——商品/客商/采购/销售/库存流水/应收应付核销（z-biz 业务线） |
| [z-biz-security](https://github.com/z-opc-foundation/z-biz-security) | 业务数据安全防护入口——场景化策略注册 + 安全线四段流水线对外编排 |
| [z-boot](https://github.com/z-opc-foundation/z-boot) | Spring Boot Starter 聚合仓 + 版本权威（火车制）——唯一消费入口 z-boot-parent |
| [z-bot](https://github.com/z-opc-foundation/z-bot) | 本地 agent 应用——终端 CLI + HTTP/SSE web 控制台 + 多通道 gateway（飞书/钉钉/webhook） |
| [z-cache](https://github.com/z-opc-foundation/z-cache) | RESP2 协议兼容的分布式内存缓存——Java 8 + Netty，Redis 客户端零修改直连 |
| [z-camuda](https://github.com/z-opc-foundation/z-camuda) | 【已退役·归档】基于 Camunda 的工作流封装层（原 z-wf 改名，与现行 z-wf 无共同历史） |
| [z-cognition](https://github.com/z-opc-foundation/z-cognition) | 业务认知域/认知舱——术语/概念/证据链 + memory/kb/ontology 渐进式模块 |
| [z-config](https://github.com/z-opc-foundation/z-config) | 分布式配置中心 + 服务注册中心（类 Nacos）——Netty 长连接推送 + React 控制台 |
| [z-ctc](https://github.com/z-opc-foundation/z-ctc) | 4A 中心——认证/授权/账户/审计，多租户 JWT + SSO 跨子域 |
| [z-dms](https://github.com/z-opc-foundation/z-dms) | 数据源管控底座——统一登记、统一建池、统一探活、变更统一推送 |
| [z-env](https://github.com/z-opc-foundation/z-env) | 本地中间件与基础设施环境——31 个 docker-compose 场景 + make 一键运维 |
| [z-etl](https://github.com/z-opc-foundation/z-etl) | ETL 引擎——DataX 式线性作业 + Flink 式 DAG 流管道（键控状态/水位/窗口） |
| [z-ext](https://github.com/z-opc-foundation/z-ext) | Java 扩展点框架——@ExtPoint/@ExtImpl 运行期选实现，五档侵入方式 |
| [z-frontend-common](https://github.com/z-opc-foundation/z-frontend-common) | 全仓共享前端基座——AppLayout/LoginPage 等前端规范的唯一承载 |
| [z-frontend-markdown](https://github.com/z-opc-foundation/z-frontend-markdown) | Markdown 通用共享组件包（npm）——MarkdownLiveEditor/NoteMarkdown/MermaidBlock 单一真源 |
| [z-frontend-render](https://github.com/z-opc-foundation/z-frontend-render) | 平台共享前端渲染引擎——后端 schema 是唯一事实源，报表与低代码共用 |
| [z-graph](https://github.com/z-opc-foundation/z-graph) | 独立图数据库——MVCC 版本链存储 + OpenCypher 子集 + Bolt 风格协议 + React 控制台 |
| [z-gw](https://github.com/z-opc-foundation/z-gw) | 自研高性能 API 网关——Netty 手写运行时，Route/Predicate/Filter 模型可嵌 Spring Boot |
| [z-indexer](https://github.com/z-opc-foundation/z-indexer) | 代码索引服务——仓库注册表 + 定时重建索引 + 符号/调用链查询 |
| [z-introduce](https://github.com/z-opc-foundation/z-introduce) | 组织仓库展示站——全仓实测元数据汇总成可搜索静态站点 |
| [z-lc](https://github.com/z-opc-foundation/z-lc) | 低代码平台——DB 驱动通用 CRUD 引擎 + 报表/模型设计框架 |
| [z-llm-agent](https://github.com/z-opc-foundation/z-llm-agent) | CLI Agent 服务化——把本机 CLI agent（CC/Hermes/Qoder 等）暴露成 OpenAI 兼容对话 API |
| [z-llm-gateway](https://github.com/z-opc-foundation/z-llm-gateway) | LLM 网关——OpenAI/Anthropic 双协议面 + 多 vendor 凭据池、限流与 token 记账 |
| [z-llm-msghub](https://github.com/z-opc-foundation/z-llm-msghub) | msgHub 中心消息路由——WebSocket 中心枢纽 + Agent 侧客户端 SDK |
| [z-mask](https://github.com/z-opc-foundation/z-mask) | 脱敏与遮蔽引擎——按敏感命中位置遮蔽，策略即数据 |
| [z-mcp](https://github.com/z-opc-foundation/z-mcp) | MCP 服务端 + 上游聚合网关——Java 8 手写协议层，Spring Boot 自动装配 |
| [z-meta](https://github.com/z-opc-foundation/z-meta) | 元数据管理平台——租户/应用/接口清单登记 + @BizModel 业务模型采集 |
| [z-mill](https://github.com/z-opc-foundation/z-mill) | 资料磨坊——全格式文档转 Markdown/JSON/纯文本/HTML（对标 MinerU） |
| [z-mist](https://github.com/z-opc-foundation/z-mist) | KMS 分布式密钥/秘密管理平台——集中加密存储 + Netty 协议 + starter 嵌入 |
| [z-mq](https://github.com/z-opc-foundation/z-mq) | 自研分布式消息队列——NameServer+Broker+Store，形状对标 RocketMQ、自有 Netty 协议 |
| [z-msg](https://github.com/z-opc-foundation/z-msg) | 多通道消息 + WebSocket 实时下发基础设施——短信/邮件/站内信/Webhook/IM 平台 |
| [z-observer](https://github.com/z-opc-foundation/z-observer) | 可观测性域——指标/日志/链路 + 告警引擎 |
| [z-opc](https://github.com/z-opc-foundation/z-opc) | 一人公司统一后端+统一前端——单 JVM all-in-one 承载协作/Agent/RPA/交易/资产等业务域与自研中间件 |
| [z-opc-foundation-lead](https://github.com/z-opc-foundation/z-opc-foundation-lead) | 组织治理大脑——事实表、强制规范、跨仓工具集、新机器恢复钥匙（本清单权威持有处） |
| [z-opcs](https://github.com/z-opc-foundation/z-opcs) | 任务撮合/服务市场——用户付费发布诉求，持有者接单完成，金额自动瓜分 |
| [z-ops](https://github.com/z-opc-foundation/z-ops) | 个人公司运维平台——部署单元/镜像构建/WebShell/K8s 巡检 + Ops Agent 执行器 |
| [z-oss](https://github.com/z-opc-foundation/z-oss) | 对象存储服务——自持 local 后端 + aliyun/s3 对接层，starter 注入 OssTemplate |
| [z-product](https://github.com/z-opc-foundation/z-product) | 产品设计信息汇总仓（开发域 PM 工件） |
| [z-qa](https://github.com/z-opc-foundation/z-qa) | 步骤式 API 链路测试平台——用例/计划/执行/报告/质量看板（对标 TestRail·MeterSphere） |
| [z-rpa](https://github.com/z-opc-foundation/z-rpa) | RPA 产品皮——应用市场/需求中心（执行归 z-script，触发归 z-schedule） |
| [z-rpc](https://github.com/z-opc-foundation/z-rpc) | Java 8 + Netty RPC 框架——微内核 + SPI 插件化，形状参照 Dubbo/gRPC |
| [z-schedule](https://github.com/z-opc-foundation/z-schedule) | 分布式任务调度中枢（对标 XXL-Job）——选主 + 时间环排期，starter 嵌入宿主 |
| [z-script](https://github.com/z-opc-foundation/z-script) | 多 DSL 脚本平台 + Mock 平台——沙箱执行/版本灰度/可注册 MCP Tool，starter 内嵌 |
| [z-security](https://github.com/z-opc-foundation/z-security) | 数据安全域编排层——场景→策略→识别→脱敏→水印→审计 四段流水线编排 |
| [z-sense](https://github.com/z-opc-foundation/z-sense) | 敏感识别与分类分级引擎——数据安全线底座（规则目录 + 可插拔识别器 + 分类分级） |
| [z-skill](https://github.com/z-opc-foundation/z-skill) | Skill 平台聚合器——归一化各家 Skill 形状，再按各家 API 形状发回 |
| [z-surl](https://github.com/z-opc-foundation/z-surl) | 短网址服务——短码生成/解析/命中统计，starter 一行集成 |
| [z-task](https://github.com/z-opc-foundation/z-task) | 任务协作平台——项目空间/看板/任务卡片（看板范式，对标 Trello·Teambition） |
| [z-team](https://github.com/z-opc-foundation/z-team) | 数字员工协同域——全栈独立仓，自 z-opc 毕业 |
| [z-trade](https://github.com/z-opc-foundation/z-trade) | 交易域独立仓——自 z-opc reactor 迁出 |
| [z-util](https://github.com/z-opc-foundation/z-util) | 底层 Java 工具库——89 个模块 POM，Java 8/17 口径，按需引用按需升级 |
| [z-vector](https://github.com/z-opc-foundation/z-vector) | 自研 Java 向量数据库——嵌入式+可独立部署，ANN+Payload 过滤，兼容 Qdrant REST **（本仓）** |
| [z-voice-demo](https://github.com/z-opc-foundation/z-voice-demo) | 语音链路 demo——流式识别→kernel LLM→流式 TTS→可打断 的契约验证实现 |
| [z-watermark](https://github.com/z-opc-foundation/z-watermark) | 水印引擎——显式文字/图片隐写/盲水印，三种技术成对「打+验」 |
| [z-webide](https://github.com/z-opc-foundation/z-webide) | 企业级 WebIDE 后端——Git 仓/共享目录秒变 code-server 实例，全生命周期管理 |
| [z-wf](https://github.com/z-opc-foundation/z-wf) | 自研流程引擎——不依赖 Camunda/Flowable，定义层与运行时全自研 |

**已退役仓**（迁至 [z-trash](https://github.com/z-trash) 组织，archived 只读）：

| 仓 | 说明 |
|---|---|
| [z-agent-proxy](https://github.com/z-trash/z-agent-proxy) | 三方 coding agent 适配代理（Aider/Cursor → kernel Agent SPI），2026-10-26 退役 |
| [z-kb](https://github.com/z-trash/z-kb) | 自研知识库系统（对标 LightRAG/RAGFlow），2026-11 并入 z-cognition 认知舱后退役 |
| [z-ontology](https://github.com/z-trash/z-ontology) | 本体层（TBox/ABox/RDFS 推理），并入 z-cognition 后退役 |

另有 [z-report](https://github.com/z-opc-foundation/z-report)（报表域，2026-10-11 撤销并入 z-lc（远端已删除））。

总清单权威持有处在 [z-opc-foundation-lead](https://github.com/z-opc-foundation/z-opc-foundation-lead) `008_组织规范/005_兄弟仓清单.md`。
