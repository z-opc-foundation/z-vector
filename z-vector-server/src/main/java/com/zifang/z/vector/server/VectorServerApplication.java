package com.zifang.z.vector.server;

import com.zifang.z.vector.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * z-vector 独立服务器
 * 提供 REST API 用于向量数据库操作
 */
public class VectorServerApplication {

    private static VectorStore vectorStore;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("ZVECTOR_PORT", "6333"));
        String dataDir = System.getenv().getOrDefault("ZVECTOR_DATA_DIR", "/data/zvector");

        System.out.println("Starting z-vector server...");
        System.out.println("Port: " + port);
        System.out.println("Data directory: " + dataDir);

        // 初始化向量存储
        vectorStore = VectorStoreFactory.persistent(dataDir);

        // 创建 HTTP 服务器
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // 注册路由
        server.createContext("/health", new HealthHandler());
        server.createContext("/collections", new CollectionsHandler());
        server.createContext("/points", new PointsHandler());
        server.createContext("/search", new SearchHandler());

        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();

        System.out.println("z-vector server started on port " + port);
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, Object> health = new HashMap<>();
            health.put("status", "ok");
            health.put("version", "1.0.1");
            health.put("collections", vectorStore.listCollections().size());

            String response = objectMapper.writeValueAsString(health);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }

    static class CollectionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("GET".equals(method) && "/collections".equals(path)) {
                // 列出所有集合
                List<String> collections = vectorStore.listCollections();
                String response = objectMapper.writeValueAsString(collections);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.getBytes().length);
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            } else if ("PUT".equals(method)) {
                // 创建集合
                InputStream is = exchange.getRequestBody();
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> request = objectMapper.readValue(body, Map.class);

                String name = (String) request.get("name");
                int dimensions = (int) request.get("dimensions");

                vectorStore.createCollection(name, dimensions, DistanceMetric.COSINE);

                Map<String, Object> result = new HashMap<>();
                result.put("status", "ok");
                result.put("collection", name);
                result.put("dimensions", dimensions);

                String response = objectMapper.writeValueAsString(result);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.getBytes().length);
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            } else {
                exchange.sendResponseHeaders(405, 0);
                exchange.getResponseBody().close();
            }
        }
    }

    static class PointsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();

            if ("POST".equals(method)) {
                InputStream is = exchange.getRequestBody();
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> request = objectMapper.readValue(body, Map.class);

                String collectionName = (String) request.get("collection");
                List<Map<String, Object>> pointsData = (List<Map<String, Object>>) request.get("points");

                if (!vectorStore.hasCollection(collectionName)) {
                    String response = "{\"error\": \"Collection not found\"}";
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(404, response.getBytes().length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(response.getBytes());
                    os.close();
                    return;
                }

                List<VectorPoint> points = new ArrayList<>();
                for (Map<String, Object> pointData : pointsData) {
                    String id = (String) pointData.get("id");
                    List<Number> vector = (List<Number>) pointData.get("vector");
                    Map<String, Object> payload = (Map<String, Object>) pointData.getOrDefault("payload", new HashMap<>());

                    float[] vectorArray = new float[vector.size()];
                    for (int i = 0; i < vector.size(); i++) {
                        vectorArray[i] = vector.get(i).floatValue();
                    }
                    VectorPoint point = new VectorPoint(id, vectorArray, payload);
                    points.add(point);
                }

                vectorStore.upsertBatch(collectionName, points);

                Map<String, Object> result = new HashMap<>();
                result.put("status", "ok");
                result.put("upserted", points.size());

                String response = objectMapper.writeValueAsString(result);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.getBytes().length);
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            } else {
                exchange.sendResponseHeaders(405, 0);
                exchange.getResponseBody().close();
            }
        }
    }

    static class SearchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();

            if ("POST".equals(method)) {
                InputStream is = exchange.getRequestBody();
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> request = objectMapper.readValue(body, Map.class);

                String collectionName = (String) request.get("collection");
                List<Number> queryVector = (List<Number>) request.get("vector");
                int limit = (int) request.getOrDefault("limit", 10);

                if (!vectorStore.hasCollection(collectionName)) {
                    String response = "{\"error\": \"Collection not found\"}";
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(404, response.getBytes().length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(response.getBytes());
                    os.close();
                    return;
                }

                float[] queryArray = new float[queryVector.size()];
                for (int i = 0; i < queryVector.size(); i++) {
                    queryArray[i] = queryVector.get(i).floatValue();
                }

                List<SearchResult> results = vectorStore.search(collectionName, queryArray, limit, null);

                Map<String, Object> result = new HashMap<>();
                result.put("status", "ok");
                result.put("results", results);

                String response = objectMapper.writeValueAsString(result);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.getBytes().length);
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            } else {
                exchange.sendResponseHeaders(405, 0);
                exchange.getResponseBody().close();
            }
        }
    }
}
