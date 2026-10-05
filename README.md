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
| **父项目** | `io.github.yuku123:z-boot-parent:1.0.21`（`<relativePath/>` 留空，parent 在 repo1 不在磁盘；2026-09-29 之前是 `com.zifang:z-opc:1.0.0-SNAPSHOT`，那个 pom 只在作者本机 `~/.m2` 里） |
| **Maven Central** | 已发布（repo1 逐坐标 ranged GET 实测 200）：`z-vector` / `-api` / `-core` / `-storage` / `-protocol` / `-grpc-server` / `-spring-boot-starter` / `-server` 八个坐标的 `1.0.4` 与 `1.0.5` 全部 200；`z-vector-api` 的 maven-metadata 显示 `latest = release = 1.0.5`，历史 1.0.1–1.0.5 |
| **默认端口** | **只有 HTTP，没有 gRPC**：独立 server `6333`（`ZVECTOR_PORT`）、starter 嵌入式 REST `6334`（`zvector.server.port`，`0` = 不起） |
| **运行口径** | Java 8（`maven.compiler.source/target=1.8`；JDK 9+ 上 `jdk9-plus-release-gate` profile 追加 `release=8` 真闸）；Spring Boot 只出现在 starter 侧 |
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
| 持久化 | `storage/PersistentVectorStore` + `storage/engine/StorageEngine` | WAL + Snapshot + 分页存储 + BufferPool + Bloom，见「本地存储」一节 |
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
| **可视化控制台 / 前端镜像** | 仓内没有 `_frontend/`、`console/` 或任何 JS 资产；`z-vector-console` 镜像无从证实 |
| **示例里的 `Map.of` / `List.of` / `.toList()`** | 本仓 target 是 Java 8，这些 Java 9+ API 在 `-release 8` 闸下当场编译失败（commit `03ea768` 就是把它变成真闸）；旧 README 的示例因此**跑不通**，本文件示例已改成 Java 8 写法 |

---

## 🏗️ 项目结构

```
z-vector/
├── pom.xml                          # 聚合 POM：parent=z-boot-parent:1.0.21，<revision> 单源，flatten 常开
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
`server → core + storage`，`protocol` **谁也不引**（只在 `grpc-server` 的测试里以 `test` scope 出现）。
一个容易踩的点：starter **不依赖 `z-vector-storage`** —— `zvector.storage-type: persistent` 是靠
`Class.forName("com.zifang.z.vector.storage.PersistentVectorStore")` 反射装配的，缺件时只 `log.warn`
并退回 `InMemoryVectorStore` ⇒ 要持久化必须自己额外引 `z-vector-storage`。

---

## 🔧 技术栈

| 层级 | 技术（全部取自 POM 实测） |
|------|---------------------------|
| 语言 / 运行时 | Java 8（`maven.compiler.source/target=1.8`；JDK 9+ 另有 `maven.compiler.release=8` 闸） |
| 构建 | Maven 多模块；`flatten-maven-plugin` **钉在 1.5.0**（1.6.0 起要求 Maven 3.6.3，而门禁机是 3.6.0，flatten 已常开 ⇒ 抬号会打死全仓构建，PomVersionContractTest 的 P5 尺盯着这条）；`maven-shade-plugin:3.5.1` 打 `z-vector-server` 的 fat jar |
| 版本供给 | `z-boot-parent:1.0.21` → 第三方地板 `z-boot-dependencies` + 兄弟仓权威表 `z-boot-fleet`；netty 4.1.138.Final、jackson 2.18.6 已改由父链下发，本仓删掉了自钉的 `<netty.version>` / `<jackson.version>` |
| 本仓仍自钉的第三方 | `slf4j 2.0.13`、`junit-jupiter 5.10.2`（父链面值更低）、`snakeyaml 2.0`（直接条目，防被父链降到 1.30）、`grpc 1.65.1` + `protobuf 3.25.5`（**仅 dependencyManagement，无模块引用**） |
| 工具库复用 | `io.github.yuku123:z-util-{core,math,ml}`：版本 1.0.13 由 fleet 的 `${z-util.version}` 下发（本仓已删自有的 `zutil.version` 键），并显式排除 `log4j-slf4j2-impl` 以免与 Spring Boot 的 `log4j-to-slf4j` 抢日志后端 |
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

拼错不降级：`ZVECTOR_DEFAULT_INDEX=SPARSE` 或 `ZVECTOR_INDEX_PARAMS='M=7'` 会让进程启动即失败，
日志里点名是哪一个变量。`/health` 的 `version` 来自构建期资源过滤写进 `build-info.properties` 的
`${project.version}`，不是手抄字面量。SIGTERM 走 shutdown hook：`server.stop` + 关线程池 + `store.close()`。

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

`PersistentVectorStore(dataDir)` 的默认档位是 `(checkpointInterval=1000, useStorageEngine=true)`，
`useStorageEngine=false` 退回同步 `WalFile`。`StorageEngine` 默认：Bloom 期望元素 `100_000` /
目标假阳率 `0.01`（MurmurHash3-x64-128 + Kirsch-Mitzenmacher 双哈希，无假阴性）、BufferPool `256` 页
（LRU，暴露 `hits / misses / evictions / hitRate`）、`AsyncWalFile` Group Commit（64 条 / 10ms 满足其一
即整批一次 fsync，暴露 `totalAppended / totalFlushed / totalBatches / queueSize`）。页大小 64KB，
header 17B（magic `ZVP1`）+ 尾部 4B CRC32。**这些都在 Java 构造器上，不在 yml 里**：

```java
VectorStore store = new PersistentVectorStore("/data/zvector", 1000, true);
StorageEngine engine = new StorageEngine("/data/zvector", wal, 100_000L, 0.01, 256);
```

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
| z-vector-storage | 22 | 171 | WAL（group commit / rotate / 崩溃恢复）、PageStore / BufferPool / Bloom、PageSnapshot / PointPageCodec、mmap 生命周期、PersistentVectorStore、StorageEngine |
| z-vector-protocol | 1 | 6 | ProtocolSpec / OpenAPI 生成 |
| z-vector-grpc-server | 5 | 33 | `QdrantRestServerTest`、`QdrantRestFilterContractTest`、`QdrantRestIndexContractTest`、`QdrantRestServerPortLifecycleTest`、`OpenApiSpecRoutingTest` |
| z-vector-spring-boot-starter | 3 | 18 | 自动装配 + REST 生命周期 |
| z-vector-server | 3 | 30 | 独立 server 索引契约 + POM 版本契约（P1/P2/P3/P5） |
| **合计** | **59** | **458** | |

注意 `VectorServiceGrpc` **一条测试都没有**（旧 README 记的 `VectorServiceGrpcTest 7 条` 已不在树上），
这与"它零调用方"是同一件事的两面。

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
  （2026-09-29 parent 已迁到 `z-boot-parent:1.0.21`，repo1 实测 200），但上面两条依旧成立。
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

- [`_doc/003_script/`](_doc/003_script/) — 运维脚本（本仓唯一有实文件的分类目录）:
  - [`deploy_maven_center.sh`](_doc/003_script/deploy_maven_center.sh) — Maven Central 发布 / 校验 / GPG 初始化
- [`_doc/007_backlog/`](_doc/007_backlog/) — 待办与收口登记:
  - [`feature001_hnsw_v2/TASK.md`](_doc/007_backlog/feature001_hnsw_v2/TASK.md) — #20「版本号单源」收口登记 +
    四把尺（P1/P2/P3/P5）与 13 支变异电池读数 + 跨 JDK 对账。其 §5.4 记的「gRPC 广告与端口三个互相
    不打照面」「OpenApiSpec 生成了但没有端点服务它」两条，正是本 README「未接入」一节的上游依据。
    注意该目录虽名为 `feature001_hnsw_v2`，里面**不是** HNSW v2 的方案本体（文首已自注这次错位）
- `_doc/001_arch/` — 目前为空目录，暂无架构文档
- `_doc/002_deploy/` — 目前为空目录，暂无部署文档
- `_doc/004_skill/` — 目前为空目录，暂无 skill 定义

`deploy_maven_center.sh` 注释里指向的 `发布指引.md` 在本仓不存在（脚本自身是该目录里唯一的实体）。

_Maintained by the z-opc-foundation organization._
