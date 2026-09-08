package com.zifang.z.vector.protocol;

/**
 * z-vector 标准协议规范 — Milvus 兼容协议 + Qdrant REST API.
 * <p>
 * 本模块定义 z-vector 对外协议层 — 对标业界两大向量数据库的协议:
 * <ul>
 *   <li><b>Milvus gRPC 协议</b> — 通过 Protobuf 定义（milvus.proto）</li>
 *   <li><b>Qdrant REST API</b> — 通过 JSON over HTTP（见 z-vector-grpc-server）</li>
 * </ul>
 *
 * <h2>Milvus 协议（参考）</h2>
 * <p>
 * Milvus 协议基于 gRPC + Protobuf，定义在官方仓库：
 * <a href="https://github.com/milvus-io/milvus-proto">milvus-proto</a>。
 * 核心 service:
 * <pre>
 * service MilvusService {
 *   rpc CreateCollection(CreateCollectionRequest) returns (StatusResponse);
 *   rpc DropCollection(DropCollectionRequest) returns (StatusResponse);
 *   rpc HasCollection(HasCollectionRequest) returns (BoolResponse);
 *   rpc DescribeCollection(DescribeCollectionRequest) returns (DescribeCollectionResponse);
 *   rpc Insert(InsertRequest) returns (MutationResult);
 *   rpc Search(SearchRequest) returns (SearchResults);
 *   rpc Query(QueryRequest) returns (QueryResults);
 *   rpc Delete(DeleteRequest) returns (MutationResult);
 *   ...
 * }
 * </pre>
 * <p>
 * z-vector 复用了这一接口语义（VectorStore 接口），未来可生成 Milvus 兼容的 gRPC stub。
 *
 * <h2>Qdrant REST 协议（已实现）</h2>
 * <p>
 * Qdrant REST 协议通过 {@code QdrantRestServer} 实现，端点:
 * <pre>
 * GET    /collections                   → 列出集合
 * PUT    /collections/{name}            → 创建集合
 * GET    /collections/{name}            → 获取集合信息
 * DELETE /collections/{name}            → 删除集合
 * PUT    /collections/{name}/points     → Upsert 向量
 * GET    /collections/{name}/points/{id}→ 获取向量
 * DELETE /collections/{name}/points/{id}→ 删除向量
 * POST   /collections/{name}/points/search → ANN 搜索
 * GET    /collections/{name}/points/count  → 向量数量
 * </pre>
 *
 * <h2>未来扩展</h2>
 * <ul>
 *   <li>添加 Milvus 完整 protobuf 定义（通过 maven protobuf-maven-plugin）</li>
 *   <li>生成 gRPC stub 并实现 MilvusService</li>
 *   <li>完整覆盖 Qdrant REST 全部端点（包括 collections/{name}/snapshots 等）</li>
 *   <li>添加 OpenAPI 3.0 规范（用于客户端 SDK 自动生成）</li>
 * </ul>
 */
public final class ProtocolSpec {

    private ProtocolSpec() {}

    /** z-vector 协议版本 */
    public static final String VERSION = "1.0.0";

    /** Milvus 协议版本（兼容） */
    public static final String MILVUS_VERSION = "2.4.x";

    /** Qdrant 协议版本（兼容） */
    public static final String QDRANT_VERSION = "1.7.x";
}