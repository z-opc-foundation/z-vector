package com.zifang.z.vector.grpc;

import com.zifang.z.vector.api.*;
import com.zifang.z.vector.api.VectorStore;
import com.zifang.z.vector.core.InMemoryVectorStore;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VectorServiceGrpc - Milvus 兼容的向量服务（纯 Java 实现）
 * <p>
 * 实现 Milvus gRPC 接口的核心操作，使用自定义请求/响应类型，
 * 不依赖外部 gRPC/protobuf 依赖。可作为独立服务运行，
 * 也可嵌入到 HTTP/gRPC 服务器中使用。
 * <p>
 * 支持的操作：
 * <ul>
 *   <li>CreateCollection - 创建集合</li>
 *   <li>DropCollection - 删除集合</li>
 *   <li>Insert - 插入向量</li>
 *   <li>Search - 向量搜索</li>
 *   <li>GetCollectionInfo - 获取集合信息</li>
 * </ul>
 * <p>
 * 设计参考 Milvus 的 gRPC 服务定义，使用 Builder 模式的请求/响应对象，
 * 便于后续迁移到真实 protobuf 生成的类型。
 * <p>
 * protobuf 定义文件: (待生成)
 * <pre>
 * service VectorService {
 *   rpc CreateCollection(CreateCollectionRequest) returns (CreateCollectionResponse);
 *   rpc Insert(InsertRequest) returns (InsertResponse);
 *   rpc Search(SearchRequest) returns (SearchResponse);
 *   rpc DropCollection(DropCollectionRequest) returns (DropCollectionResponse);
 *   rpc GetCollectionInfo(GetCollectionInfoRequest) returns (GetCollectionInfoResponse);
 * }
 * </pre>
 */
public class VectorServiceGrpc {

    private final Map<String, VectorStore> stores = new ConcurrentHashMap<>();

    // ─── 请求/响应类型（Builder 模式，兼容 protobuf 风格） ─────────────

    /** 创建集合请求 */
    public static class CreateCollectionRequest {
        private final String collectionName;
        private final int dimension;
        private final String metric;

        private CreateCollectionRequest(Builder b) {
            this.collectionName = b.collectionName;
            this.dimension = b.dimension;
            this.metric = b.metric;
        }

        public String getCollectionName() { return collectionName; }
        public int getDimension() { return dimension; }
        public String getMetric() { return metric; }

        public static Builder newBuilder() { return new Builder(); }

        public static class Builder {
            private String collectionName;
            private int dimension;
            private String metric = "L2";

            public Builder setCollectionName(String v) { this.collectionName = v; return this; }
            public Builder setDimension(int v) { this.dimension = v; return this; }
            public Builder setMetric(String v) { this.metric = v; return this; }
            public CreateCollectionRequest build() { return new CreateCollectionRequest(this); }
        }
    }

    /** 创建集合响应 */
    public static class CreateCollectionResponse {
        private final boolean success;
        private final String message;

        private CreateCollectionResponse(Builder b) {
            this.success = b.success;
            this.message = b.message;
        }

        public boolean getSuccess() { return success; }
        public String getMessage() { return message; }

        public static Builder newBuilder() { return new Builder(); }

        public static class Builder {
            private boolean success;
            private String message = "";

            public Builder setSuccess(boolean v) { this.success = v; return this; }
            public Builder setMessage(String v) { this.message = v; return this; }
            public CreateCollectionResponse build() { return new CreateCollectionResponse(this); }
        }
    }

    /** 插入请求 */
    public static class InsertRequest {
        private final String collectionName;
        private final List<VectorData> vectors;

        private InsertRequest(Builder b) {
            this.collectionName = b.collectionName;
            this.vectors = Collections.unmodifiableList(b.vectors);
        }

        public String getCollectionName() { return collectionName; }
        public List<VectorData> getVectorsList() { return vectors; }
        public int getVectorsCount() { return vectors.size(); }

        public static Builder newBuilder() { return new Builder(); }

        public static class Builder {
            private String collectionName;
            private final List<VectorData> vectors = new ArrayList<>();

            public Builder setCollectionName(String v) { this.collectionName = v; return this; }
            public Builder addVectors(VectorData v) { this.vectors.add(v); return this; }
            public InsertRequest build() { return new InsertRequest(this); }
        }
    }

    /** 向量数据 */
    public static class VectorData {
        private final String id;
        private final float[] values;
        private final Map<String, String> metadata;

        public VectorData(String id, float[] values, Map<String, String> metadata) {
            this.id = id;
            this.values = values;
            this.metadata = metadata != null ? metadata : Collections.emptyMap();
        }

        public String getId() { return id; }
        public float[] getValues() { return values; }
        public int getValuesCount() { return values.length; }
        public float getValues(int i) { return values[i]; }
        public Map<String, String> getMetadataMap() { return metadata; }
    }

    /** 插入响应 */
    public static class InsertResponse {
        private final boolean success;
        private final int insertedCount;

        private InsertResponse(Builder b) {
            this.success = b.success;
            this.insertedCount = b.insertedCount;
        }

        public boolean getSuccess() { return success; }
        public int getInsertedCount() { return insertedCount; }

        public static Builder newBuilder() { return new Builder(); }

        public static class Builder {
            private boolean success;
            private int insertedCount;

            public Builder setSuccess(boolean v) { this.success = v; return this; }
            public Builder setInsertedCount(int v) { this.insertedCount = v; return this; }
            public InsertResponse build() { return new InsertResponse(this); }
        }
    }

    /** 搜索请求 */
    public static class SearchRequest {
        private final String collectionName;
        private final float[] query;
        private final int topK;
        private final Map<String, Object> filterConditions;

        private SearchRequest(Builder b) {
            this.collectionName = b.collectionName;
            this.query = b.query;
            this.topK = b.topK;
            this.filterConditions = b.filterConditions;
        }

        public String getCollectionName() { return collectionName; }
        public float[] getQuery() { return query; }
        public int getQueryCount() { return query.length; }
        public float getQuery(int i) { return query[i]; }
        public int getTopK() { return topK; }
        public boolean hasFilter() { return filterConditions != null && !filterConditions.isEmpty(); }
        public Map<String, Object> getFilter() { return filterConditions; }

        public static Builder newBuilder() { return new Builder(); }

        public static class Builder {
            private String collectionName;
            private float[] query;
            private int topK = 10;
            private Map<String, Object> filterConditions;

            public Builder setCollectionName(String v) { this.collectionName = v; return this; }
            public Builder setQuery(float[] v) { this.query = v; return this; }
            public Builder setTopK(int v) { this.topK = v; return this; }
            public Builder setFilter(Map<String, Object> v) { this.filterConditions = v; return this; }
            public SearchRequest build() { return new SearchRequest(this); }
        }
    }

    /** 搜索响应 */
    public static class SearchResponse {
        private final List<SearchHit> hits;

        private SearchResponse(Builder b) {
            this.hits = Collections.unmodifiableList(b.hits);
        }

        public List<SearchHit> getHitsList() { return hits; }
        public int getHitsCount() { return hits.size(); }

        public static Builder newBuilder() { return new Builder(); }

        public static class Builder {
            private final List<SearchHit> hits = new ArrayList<>();

            public Builder addHits(SearchHit v) { this.hits.add(v); return this; }
            public SearchResponse build() { return new SearchResponse(this); }
        }
    }

    /** 搜索命中 */
    public static class SearchHit {
        private final String id;
        private final float score;

        public SearchHit(String id, float score) {
            this.id = id;
            this.score = score;
        }

        public String getId() { return id; }
        public float getScore() { return score; }

        public static Builder newBuilder() { return new Builder(); }

        public static class Builder {
            private String id;
            private float score;

            public Builder setId(String v) { this.id = v; return this; }
            public Builder setScore(float v) { this.score = v; return this; }
            public SearchHit build() { return new SearchHit(id, score); }
        }
    }

    /** 删除集合请求 */
    public static class DropCollectionRequest {
        private final String collectionName;

        private DropCollectionRequest(Builder b) { this.collectionName = b.collectionName; }
        public String getCollectionName() { return collectionName; }

        public static Builder newBuilder() { return new Builder(); }

        public static class Builder {
            private String collectionName;
            public Builder setCollectionName(String v) { this.collectionName = v; return this; }
            public DropCollectionRequest build() { return new DropCollectionRequest(this); }
        }
    }

    /** 删除集合响应 */
    public static class DropCollectionResponse {
        private final boolean success;
        private final String message;

        private DropCollectionResponse(Builder b) { this.success = b.success; this.message = b.message; }
        public boolean getSuccess() { return success; }
        public String getMessage() { return message; }

        public static Builder newBuilder() { return new Builder(); }

        public static class Builder {
            private boolean success;
            private String message = "";
            public Builder setSuccess(boolean v) { this.success = v; return this; }
            public Builder setMessage(String v) { this.message = v; return this; }
            public DropCollectionResponse build() { return new DropCollectionResponse(this); }
        }
    }

    /** 获取集合信息请求 */
    public static class GetCollectionInfoRequest {
        private final String collectionName;

        private GetCollectionInfoRequest(Builder b) { this.collectionName = b.collectionName; }
        public String getCollectionName() { return collectionName; }

        public static Builder newBuilder() { return new Builder(); }

        public static class Builder {
            private String collectionName;
            public Builder setCollectionName(String v) { this.collectionName = v; return this; }
            public GetCollectionInfoRequest build() { return new GetCollectionInfoRequest(this); }
        }
    }

    /** 获取集合信息响应 */
    public static class GetCollectionInfoResponse {
        private final String collectionName;
        private final boolean exists;
        private final int vectorCount;
        private final int dimension;

        private GetCollectionInfoResponse(Builder b) {
            this.collectionName = b.collectionName;
            this.exists = b.exists;
            this.vectorCount = b.vectorCount;
            this.dimension = b.dimension;
        }

        public String getCollectionName() { return collectionName; }
        public boolean getExists() { return exists; }
        public int getVectorCount() { return vectorCount; }
        public int getDimension() { return dimension; }

        public static Builder newBuilder() { return new Builder(); }

        public static class Builder {
            private String collectionName;
            private boolean exists;
            private int vectorCount;
            private int dimension;

            public Builder setCollectionName(String v) { this.collectionName = v; return this; }
            public Builder setExists(boolean v) { this.exists = v; return this; }
            public Builder setVectorCount(int v) { this.vectorCount = v; return this; }
            public Builder setDimension(int v) { this.dimension = v; return this; }
            public GetCollectionInfoResponse build() { return new GetCollectionInfoResponse(this); }
        }
    }

    // ─── 服务操作 ─────────────────────────────────────────────

    /**
     * 获取或创建 VectorStore
     */
    private VectorStore getOrCreateStore(String collectionName) {
        return stores.computeIfAbsent(collectionName, k -> new InMemoryVectorStore());
    }

    /**
     * 创建集合
     */
    public CreateCollectionResponse createCollection(CreateCollectionRequest request) {
        try {
            VectorStore store = getOrCreateStore(request.getCollectionName());
            store.createCollection(
                request.getCollectionName(),
                request.getDimension(),
                DistanceMetric.valueOf(request.getMetric())
            );

            return CreateCollectionResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Collection created: " + request.getCollectionName())
                .build();
        } catch (Exception e) {
            return CreateCollectionResponse.newBuilder()
                .setSuccess(false)
                .setMessage("Error: " + e.getMessage())
                .build();
        }
    }

    /**
     * 插入向量
     */
    public InsertResponse insert(InsertRequest request) {
        try {
            VectorStore store = getOrCreateStore(request.getCollectionName());

            for (VectorData data : request.getVectorsList()) {
                // VectorPoint 要求 Map<String, Object>，VectorData.getMetadataMap() 返回 Map<String, String>
                Map<String, Object> metadata = new LinkedHashMap<>(data.getMetadataMap());
                VectorPoint point = new VectorPoint(
                    data.getId(),
                    data.getValues(),
                    metadata
                );
                store.upsert(request.getCollectionName(), point);
            }

            return InsertResponse.newBuilder()
                .setSuccess(true)
                .setInsertedCount(request.getVectorsCount())
                .build();
        } catch (Exception e) {
            return InsertResponse.newBuilder()
                .setSuccess(false)
                .setInsertedCount(0)
                .build();
        }
    }

    /**
     * 向量搜索
     */
    public SearchResponse search(SearchRequest request) {
        try {
            VectorStore store = getOrCreateStore(request.getCollectionName());

            Filter filter = null;
            if (request.hasFilter()) {
                Map<String, Object> conditions = request.getFilter();
                for (Map.Entry<String, Object> entry : conditions.entrySet()) {
                    filter = Filter.eq(entry.getKey(), entry.getValue());
                }
            }

            List<SearchResult> results = store.search(
                request.getCollectionName(),
                request.getQuery(),
                request.getTopK(),
                filter
            );

            SearchResponse.Builder responseBuilder = SearchResponse.newBuilder();
            for (SearchResult result : results) {
                responseBuilder.addHits(new SearchHit(
                    result.getVectorId(),
                    (float) result.getScore()
                ));
            }

            return responseBuilder.build();
        } catch (Exception e) {
            return SearchResponse.newBuilder().build();
        }
    }

    /**
     * 删除集合
     */
    public DropCollectionResponse dropCollection(DropCollectionRequest request) {
        try {
            stores.remove(request.getCollectionName());

            return DropCollectionResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Collection dropped: " + request.getCollectionName())
                .build();
        } catch (Exception e) {
            return DropCollectionResponse.newBuilder()
                .setSuccess(false)
                .setMessage("Error: " + e.getMessage())
                .build();
        }
    }

    /**
     * 获取集合信息
     */
    public GetCollectionInfoResponse getCollectionInfo(GetCollectionInfoRequest request) {
        VectorStore store = stores.get(request.getCollectionName());
        if (store == null) {
            return GetCollectionInfoResponse.newBuilder()
                .setCollectionName(request.getCollectionName())
                .setExists(false)
                .build();
        }

        try {
            VectorCollection collection = store.getCollection(request.getCollectionName());
            if (collection == null) {
                return GetCollectionInfoResponse.newBuilder()
                    .setCollectionName(request.getCollectionName())
                    .setExists(false)
                    .build();
            }

            long pointCount = store.getPointCount(request.getCollectionName());
            return GetCollectionInfoResponse.newBuilder()
                .setCollectionName(request.getCollectionName())
                .setExists(true)
                .setVectorCount((int) pointCount)
                .setDimension(collection.getDimension())
                .build();
        } catch (Exception e) {
            return GetCollectionInfoResponse.newBuilder()
                .setCollectionName(request.getCollectionName())
                .setExists(true)
                .build();
        }
    }

    /**
     * 获取所有集合名
     */
    public Set<String> listCollections() {
        return Collections.unmodifiableSet(stores.keySet());
    }

    /**
     * 关闭所有集合
     */
    public void close() {
        stores.clear();
    }
}
