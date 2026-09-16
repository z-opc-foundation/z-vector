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
     */
    public static String generateSpec() {
        return generateSpec("z-vector", "1.0.0");
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

        // 服务器
        sb.append("servers:\n");
        sb.append("  - url: http://localhost:6334\n");
        sb.append("    description: Local development server\n");

        // 路径
        sb.append("paths:\n");

        // 健康检查
        sb.append("  /healthz:\n");
        sb.append("    get:\n");
        sb.append("      summary: Health check\n");
        sb.append("      operationId: healthz\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: OK\n");

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
        sb.append("          enum: [L2, COSINE, INNER_PRODUCT, HAMMING]\n");
        sb.append("        index_type:\n");
        sb.append("          type: string\n");
        sb.append("          enum: [FLAT, HNSW, IVF]\n");

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

        sb.append("    Filter:\n");
        sb.append("      type: object\n");
        sb.append("      properties:\n");
        sb.append("        must:\n");
        sb.append("          type: array\n");
        sb.append("          items:\n");
        sb.append("            $ref: '#/components/schemas/Condition'\n");
        sb.append("        should:\n");
        sb.append("          type: array\n");
        sb.append("          items:\n");
        sb.append("            $ref: '#/components/schemas/Condition'\n");
        sb.append("        must_not:\n");
        sb.append("          type: array\n");
        sb.append("          items:\n");
        sb.append("            $ref: '#/components/schemas/Condition'\n");

        sb.append("    Condition:\n");
        sb.append("      type: object\n");
        sb.append("      properties:\n");
        sb.append("        key:\n");
        sb.append("          type: string\n");
        sb.append("        match:\n");
        sb.append("          $ref: '#/components/schemas/Match'\n");

        sb.append("    Match:\n");
        sb.append("      type: object\n");
        sb.append("      properties:\n");
        sb.append("        value:\n");
        sb.append("          oneOf:\n");
        sb.append("            - type: string\n");
        sb.append("            - type: integer\n");
        sb.append("            - type: boolean\n");

        sb.append("    CollectionInfo:\n");
        sb.append("      type: object\n");
        sb.append("      properties:\n");
        sb.append("        status:\n");
        sb.append("          type: string\n");
        sb.append("        vectors_count:\n");
        sb.append("          type: integer\n");
        sb.append("        config:\n");
        sb.append("          type: object\n");

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
        return docs;
    }
}
