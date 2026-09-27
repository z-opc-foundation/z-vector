package com.zifang.z.vector.protocol;

import java.util.*;

/**
 * OpenApiSpec - OpenAPI 3.0 规范生成器
 * <p>
 * 自动生成 z-vector REST API 的 OpenAPI 3.0 规范文档。
 * 支持 Swagger UI 和 OpenAPI Generator 生成客户端 SDK。
 * <p>
 * 设计参考 Qdrant 的 OpenAPI 规范。
 */
public class OpenApiSpec {

    /**
     * 生成 OpenAPI 3.0 规范文档
     * <p>
     * 版本取 {@link ProtocolSpec#VERSION}（协议版本），不在这里再抄一份字面量 ——
     * 这一族"两处各自写死同一个值，改一处就漂"的缺陷在 /health 上已经修过一次。
     */
    public static String generateSpec() {
        return generateSpec("z-vector", ProtocolSpec.VERSION);
    }

    /**
     * 生成 OpenAPI 3.0 规范文档
     */
    public static String generateSpec(String title, String version) {
        StringBuilder sb = new StringBuilder();

        // OpenAPI 头部
        sb.append("openapi: 3.0.3\n");
        sb.append("info:\n");
        sb.append("  title: ").append(title).append(" API\n");
        sb.append("  version: ").append(version).append("\n");
        sb.append("  description: |\n");
        sb.append("    z-vector 向量数据库 REST API\n");
        sb.append("    兼容 Qdrant 1.7+ 协议\n");
        // 本文件描述的是 embedded（QdrantRestServer，默认 6334）这一面。仓里还有第二台独立
        // server（z-vector-server，Docker 默认 6333），它的 URL 形状与入参名都不同：
        // 集合名走 body 的 collection、创建入参是必填的 dimensions（不是 dimension）、
        // 健康检查是 /health（不是 /healthz）。两台共用一份文档会生成跑不通的客户端，
        // 所以这里只描述一台，另一台的差异写在 README 的"方式二"。

        // 服务器
        sb.append("servers:\n");
        sb.append("  - url: http://localhost:6334\n");
        sb.append("    description: Local development server\n");

        // 路径
        sb.append("paths:\n");
        // 这里原先有 /healthz —— 两台 server 都没有这个端点（embedded 那台路由到 404，
        // 独立那台是 /health）。生成的客户端会带一个永远拿 404 的方法，所以按"广告了就得有"
        // 撤掉，而不是留一条注释说"待实现"。

        // 集合操作
        sb.append("  /collections/{collection_name}:\n");
        sb.append("    put:\n");
        sb.append("      summary: Create collection\n");
        sb.append("      operationId: createCollection\n");
        sb.append("      parameters:\n");
        sb.append("        - name: collection_name\n");
        sb.append("          in: path\n");
        sb.append("          required: true\n");
        sb.append("          schema:\n");
        sb.append("            type: string\n");
        sb.append("      requestBody:\n");
        sb.append("        required: true\n");
        sb.append("        content:\n");
        sb.append("          application/json:\n");
        sb.append("            schema:\n");
        sb.append("              $ref: '#/components/schemas/CreateCollectionRequest'\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: Collection created\n");
        sb.append("        '400':\n");
        sb.append("          description: Bad request\n");

        sb.append("    get:\n");
        sb.append("      summary: Get collection info\n");
        sb.append("      operationId: getCollection\n");
        sb.append("      parameters:\n");
        sb.append("        - name: collection_name\n");
        sb.append("          in: path\n");
        sb.append("          required: true\n");
        sb.append("          schema:\n");
        sb.append("            type: string\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: Collection info\n");
        sb.append("          content:\n");
        sb.append("            application/json:\n");
        sb.append("              schema:\n");
        sb.append("                $ref: '#/components/schemas/CollectionInfo'\n");

        sb.append("    delete:\n");
        sb.append("      summary: Delete collection\n");
        sb.append("      operationId: deleteCollection\n");
        sb.append("      parameters:\n");
        sb.append("        - name: collection_name\n");
        sb.append("          in: path\n");
        sb.append("          required: true\n");
        sb.append("          schema:\n");
        sb.append("            type: string\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: Collection deleted\n");

        // 向量操作
        sb.append("  /collections/{collection_name}/points:\n");
        sb.append("    put:\n");
        sb.append("      summary: Upsert points\n");
        sb.append("      operationId: upsertPoints\n");
        sb.append("      parameters:\n");
        sb.append("        - name: collection_name\n");
        sb.append("          in: path\n");
        sb.append("          required: true\n");
        sb.append("          schema:\n");
        sb.append("            type: string\n");
        sb.append("      requestBody:\n");
        sb.append("        required: true\n");
        sb.append("        content:\n");
        sb.append("          application/json:\n");
        sb.append("            schema:\n");
        sb.append("              $ref: '#/components/schemas/UpsertRequest'\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: Points upserted\n");

        // 搜索在 /points/search 上，不在 /points 上：这一台对 /collections/{name}/points 只认
        // PUT，POST 直接 405（QdrantRestServer 的 P_POINTS_BATCH 分支）。此前这条挂在
        // /points 的 post 下面，照文档生成的客户端一个搜索请求都发不出去，而文档里的
        // operationId 名叫 searchPoints —— 看起来完全像是已经支持了。
        sb.append("  /collections/{collection_name}/points/search:\n");
        sb.append("    post:\n");
        sb.append("      summary: Search points\n");
        sb.append("      operationId: searchPoints\n");
        sb.append("      parameters:\n");
        sb.append("        - name: collection_name\n");
        sb.append("          in: path\n");
        sb.append("          required: true\n");
        sb.append("          schema:\n");
        sb.append("            type: string\n");
        sb.append("      requestBody:\n");
        sb.append("        required: true\n");
        sb.append("        content:\n");
        sb.append("          application/json:\n");
        sb.append("            schema:\n");
        sb.append("              $ref: '#/components/schemas/SearchRequest'\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: Search results\n");
        sb.append("          content:\n");
        sb.append("            application/json:\n");
        sb.append("              schema:\n");
        sb.append("                $ref: '#/components/schemas/SearchResponse'\n");

        // 下面四条是这台 server 一直在路由、文档此前一个字没提的端点。反向那把尺
        // （OpenApiSpecRoutingTest）会把这种"实现宽、文档窄"的缺口也判红 —— 生成的客户端
        // 够不到的能力等于没有，而 README 对外说的是"兼容 Qdrant 核心端点"。
        sb.append("  /collections:\n");
        sb.append("    get:\n");
        sb.append("      summary: List collections\n");
        sb.append("      operationId: listCollections\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: Collection names\n");
        sb.append("          content:\n");
        sb.append("            application/json:\n");
        sb.append("              schema:\n");
        sb.append("                $ref: '#/components/schemas/CollectionList'\n");

        sb.append("  /collections/{collection_name}/points/{id}:\n");
        sb.append("    get:\n");
        sb.append("      summary: Get point by id\n");
        sb.append("      operationId: getPoint\n");
        sb.append("      parameters:\n");
        sb.append("        - name: collection_name\n");
        sb.append("          in: path\n");
        sb.append("          required: true\n");
        sb.append("          schema:\n");
        sb.append("            type: string\n");
        sb.append("        - name: id\n");
        sb.append("          in: path\n");
        sb.append("          required: true\n");
        sb.append("          schema:\n");
        sb.append("            type: string\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: Point\n");
        sb.append("          content:\n");
        sb.append("            application/json:\n");
        sb.append("              schema:\n");
        sb.append("                $ref: '#/components/schemas/VectorPoint'\n");
        sb.append("        '404':\n");
        sb.append("          description: Point not found\n");
        sb.append("    delete:\n");
        sb.append("      summary: Delete point by id\n");
        sb.append("      operationId: deletePoint\n");
        sb.append("      parameters:\n");
        sb.append("        - name: collection_name\n");
        sb.append("          in: path\n");
        sb.append("          required: true\n");
        sb.append("          schema:\n");
        sb.append("            type: string\n");
        sb.append("        - name: id\n");
        sb.append("          in: path\n");
        sb.append("          required: true\n");
        sb.append("          schema:\n");
        sb.append("            type: string\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: Point deleted\n");

        sb.append("  /collections/{collection_name}/points/count:\n");
        sb.append("    get:\n");
        sb.append("      summary: Count points\n");
        sb.append("      operationId: countPoints\n");
        sb.append("      parameters:\n");
        sb.append("        - name: collection_name\n");
        sb.append("          in: path\n");
        sb.append("          required: true\n");
        sb.append("          schema:\n");
        sb.append("            type: string\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: Point count\n");
        sb.append("          content:\n");
        sb.append("            application/json:\n");
        sb.append("              schema:\n");
        sb.append("                $ref: '#/components/schemas/PointCount'\n");

        // 组件
        sb.append("components:\n");
        sb.append("  schemas:\n");

        sb.append("    CreateCollectionRequest:\n");
        sb.append("      type: object\n");
        sb.append("      properties:\n");
        sb.append("        dimension:\n");
        sb.append("          type: integer\n");
        sb.append("        metric:\n");
        sb.append("          type: string\n");
        // IP 而不是 INNER_PRODUCT：枚举里压根没有后者（DistanceMetric = L2/IP/COSINE/HAMMING），
        // 照这份 spec 生成的客户端提交 INNER_PRODUCT 会拿到 400 "No enum constant"。
        // 谁跟代码对不上由 ProtocolSpecTest 的词汇表尺当场判红。
        sb.append("          enum: [L2, IP, COSINE, HAMMING]\n");
        sb.append("        index_type:\n");
        sb.append("          type: string\n");
        sb.append("          enum: [FLAT, HNSW, IVF]\n");
        // index_params 此前只有代码收得下、文档没写：两台 server 都读它（HNSW 认 M /
        // efConstruction / efSearch，IVF 认 nlist / nprobe / maxIter），照文档写客户端的人
        // 永远调不到已经支持的索引调参面。
        sb.append("        index_params:\n");
        sb.append("          type: object\n");

        sb.append("    UpsertRequest:\n");
        sb.append("      type: object\n");
        sb.append("      properties:\n");
        sb.append("        points:\n");
        sb.append("          type: array\n");
        sb.append("          items:\n");
        sb.append("            $ref: '#/components/schemas/VectorPoint'\n");

        sb.append("    VectorPoint:\n");
        sb.append("      type: object\n");
        sb.append("      properties:\n");
        sb.append("        id:\n");
        sb.append("          type: string\n");
        sb.append("        vector:\n");
        sb.append("          type: array\n");
        sb.append("          items:\n");
        sb.append("            type: number\n");
        sb.append("        payload:\n");
        sb.append("          type: object\n");

        sb.append("    SearchRequest:\n");
        sb.append("      type: object\n");
        sb.append("      properties:\n");
        sb.append("        vector:\n");
        sb.append("          type: array\n");
        sb.append("          items:\n");
        sb.append("            type: number\n");
        sb.append("        limit:\n");
        sb.append("          type: integer\n");
        sb.append("          default: 10\n");
        sb.append("        filter:\n");
        sb.append("          $ref: '#/components/schemas/Filter'\n");

        sb.append("    SearchResponse:\n");
        sb.append("      type: object\n");
        sb.append("      properties:\n");
        sb.append("        result:\n");
        sb.append("          type: array\n");
        sb.append("          items:\n");
        sb.append("            $ref: '#/components/schemas/SearchResult'\n");

        sb.append("    SearchResult:\n");
        sb.append("      type: object\n");
        sb.append("      properties:\n");
        sb.append("        id:\n");
        sb.append("          type: string\n");
        sb.append("        score:\n");
        sb.append("          type: number\n");
        sb.append("        payload:\n");
        sb.append("          type: object\n");

        // Filter 这段先前抄的是 Qdrant 的 must/should/must_not + {key, match} 形状，而这台 server
        // 一个都不认；上一轮改成"照实描述"（单字段等值 + {字段:{op:值}} + and/or），可 and/or 在实现里
        // 其实也是死的 —— size()==1 那一支先把它当成一个叫 and 的字段吃掉，恒零命中。
        // 现在文档与实现对齐，而那两行 x-implemented-* 是给 QdrantRestFilterContractTest 当锚点用的：
        // 广告了却没实现、实现了却没广告，都要在那把尺上红一条，不靠人记得同步这段文字。
        sb.append("    Filter:\n");
        sb.append("      type: object\n");
        sb.append("      description: |\n");
        sb.append("        载荷字段过滤。条件对象里每个键要么是组合词，要么是 payload 字段名，多个键按 AND 合并；\n");
        sb.append("        认不出的组合词、操作符或类型一律 400 —— 不会把\"筛了但没生效\"的未过滤结果当答案发出去。\n");
        sb.append("        1) 单字段等值: {\"lang\": \"zh\"}\n");
        sb.append("        2) 带操作符: {\"score\": {\"gte\": 0.2, \"lte\": 0.6}}（同字段多个操作符按 AND）\n");
        sb.append("        3) 组合: {\"and\": [ … ]}、{\"or\": [ … ]}，以及 Qdrant 拼写 {\"must\": [ … ]}、\n");
        sb.append("           {\"should\": [ … ]}、{\"must_not\": [ … ]}、{\"not\": { … }}\n");
        sb.append("        4) {} 与空数组是不施加限制，不是恒假\n");
        sb.append("      x-implemented-operators: [eq, ne, gt, gte, lt, lte, in, nin, exists, contains]\n");
        sb.append("      x-implemented-combinators: [and, or, must, should, must_not, not]\n");
        sb.append("      additionalProperties: true\n");

        // CollectionInfo 此前写的是 {status, vectors_count, config}，而这台真正回的是
        // {name, dimension, metric, index_type, index_params, points_count, indexed} ——
        // 生成的客户端拿到响应后一个字段都取不到，而三个名字里有两个（status / config）
        // 在响应里根本不存在。这一族取值同样归 ProtocolSpecTest 的词汇表尺管。
        sb.append("    CollectionInfo:\n");
        sb.append("      type: object\n");
        sb.append("      properties:\n");
        sb.append("        name:\n");
        sb.append("          type: string\n");
        sb.append("        dimension:\n");
        sb.append("          type: integer\n");
        sb.append("        metric:\n");
        sb.append("          type: string\n");
        sb.append("          enum: [L2, IP, COSINE, HAMMING]\n");
        sb.append("        index_type:\n");
        sb.append("          type: string\n");
        sb.append("          enum: [FLAT, HNSW, IVF]\n");
        sb.append("        index_params:\n");
        sb.append("          type: object\n");
        sb.append("        points_count:\n");
        sb.append("          type: integer\n");
        sb.append("        indexed:\n");
        sb.append("          type: boolean\n");

        sb.append("    CollectionList:\n");
        sb.append("      type: object\n");
        sb.append("      properties:\n");
        sb.append("        status:\n");
        sb.append("          type: string\n");
        sb.append("        collections:\n");
        sb.append("          type: array\n");
        sb.append("          items:\n");
        sb.append("            type: string\n");

        sb.append("    PointCount:\n");
        sb.append("      type: object\n");
        sb.append("      properties:\n");
        sb.append("        count:\n");
        sb.append("          type: integer\n");

        return sb.toString();
    }

    /**
     * 获取 API 文档 JSON 格式
     */
    public static Map<String, Object> getApiDocs() {
        Map<String, Object> docs = new HashMap<>();
        docs.put("openapi", "3.0.3");
        docs.put("title", "z-vector API");
        docs.put("version", "1.0.0");
        docs.put("description", "z-vector 向量数据库 REST API");
        docs.put("version", ProtocolSpec.VERSION);
        return docs;
    }
}
