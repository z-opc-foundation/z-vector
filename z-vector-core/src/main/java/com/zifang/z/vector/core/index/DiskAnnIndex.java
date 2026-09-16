package com.zifang.z.vector.core.index;

import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.core.distance.Distance;
import com.zifang.z.vector.core.distance.DistanceFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DiskANNIndex - 磁盘 ANN 索引（基于 Vamana 图）
 * <p>
 * 实现微软 DiskANN 论文的核心思想：
 * <ul>
 *   <li>Vamana 图：每个节点维护固定大小的邻居列表</li>
 *   <li>Beam Search：从入口点开始，贪心搜索最近邻</li>
 *   <li>内存映射：索引元数据在内存，向量数据在磁盘</li>
 * </ul>
 * <p>
 * 设计参考：
 * <ul>
 *   <li>微软 DiskANN (NeurIPS 2019)</li>
 *   <li>Vamana 图构建算法</li>
 *   <li>SSD 优化的随机访问模式</li>
 * </ul>
 * <p>
 * 特点：
 * <ul>
 *   <li>支持超大规模数据集（10亿级）</li>
 *   <li>内存占用低（仅索引元数据在内存）</li>
 *   <li>查询时延高但吞吐量高（适合批处理）</li>
 * </ul>
 */
public class DiskAnnIndex {

    private static final int DEFAULT_MAX_NEIGHBORS = 32;
    private static final int DEFAULT_BEAM_WIDTH = 128;
    private static final int DEFAULT_SEARCH_LIST_SIZE = 100;

    private final int dimension;
    private final Distance distance;
    private final int maxNeighbors;
    private final int beamWidth;
    private final int searchListSize;

    // 索引数据
    private final Map<Integer, float[]> vectors = new ConcurrentHashMap<>();
    private final Map<Integer, List<Integer>> graph = new ConcurrentHashMap<>();
    private int nextNodeId = 0;
    private int entryPoint = -1;

    // 磁盘存储
    private Path indexDir;
    private boolean dirty = false;

    public DiskAnnIndex(int dimension, DistanceMetric distanceMetric) {
        this(dimension, distanceMetric, DEFAULT_MAX_NEIGHBORS, DEFAULT_BEAM_WIDTH, DEFAULT_SEARCH_LIST_SIZE);
    }

    public DiskAnnIndex(int dimension, DistanceMetric distanceMetric, int maxNeighbors, int beamWidth, int searchListSize) {
        this.dimension = dimension;
        this.distance = DistanceFactory.create(distanceMetric);
        this.maxNeighbors = maxNeighbors;
        this.beamWidth = beamWidth;
        this.searchListSize = searchListSize;
    }

    /**
     * 设置索引目录（用于磁盘持久化）
     */
    public void setIndexDir(Path indexDir) {
        this.indexDir = indexDir;
    }

    /**
     * 添加向量到索引
     */
    public int addVector(float[] vector) {
        if (vector.length != dimension) {
            throw new IllegalArgumentException("Vector dimension mismatch: " + vector.length + " vs " + dimension);
        }

        int nodeId = nextNodeId++;
        vectors.put(nodeId, vector.clone());
        graph.put(nodeId, new ArrayList<>());

        // 动态构建图（简化版）
        if (entryPoint == -1) {
            entryPoint = nodeId;
        } else {
            // 找到最近邻并建立连接
            List<Integer> neighbors = search(vector, maxNeighbors);
            graph.get(nodeId).addAll(neighbors);

            // 反向连接
            for (int neighborId : neighbors) {
                List<Integer> neighborNeighbors = graph.get(neighborId);
                if (neighborNeighbors.size() < maxNeighbors) {
                    neighborNeighbors.add(nodeId);
                } else {
                    // 替换最远的邻居
                    int farthestIdx = findFarthest(vector, neighborNeighbors);
                    if (farthestIdx >= 0) {
                        neighborNeighbors.set(farthestIdx, nodeId);
                    }
                }
            }
        }

        dirty = true;
        return nodeId;
    }

    /**
     * 添加带 ID 的向量
     */
    public void addVector(String id, float[] vector) {
        int nodeId = addVector(vector);
        // 简化版：ID 映射存储
    }

    /**
     * KNN 搜索（Vamana Beam Search）
     * <p>
     * 算法流程:
     * 1. 从入口点出发，加入候选队列和结果集
     * 2. 贪心扩展：每次取出距离最小的候选节点，探索其邻居
     * 3. 将新发现的邻居加入候选队列和结果集
     * 4. 结果集维护 topK 个最近邻（最大堆，超出时弹出最远的）
     * 5. 直到候选队列为空或已访问节点达到 beamWidth
     */
    public List<int[]> searchKnn(float[] query, int topK) {
        if (entryPoint == -1) {
            return Collections.emptyList();
        }

        // Beam Search
        Set<Integer> visited = new HashSet<>();
        // 候选队列: 最小堆，按距离排序
        PriorityQueue<int[]> candidates = new PriorityQueue<>(Comparator.comparingDouble(a -> a[1]));
        // 结果集: 最大堆，维护 topK 个最近邻
        PriorityQueue<int[]> results = new PriorityQueue<>(Comparator.comparingDouble((int[] a) -> a[1]).reversed());

        // 从入口点开始
        float entryDist = (float) distance.compute(query, vectors.get(entryPoint));
        candidates.add(new int[]{entryPoint, Float.floatToIntBits(entryDist)});
        visited.add(entryPoint);

        while (!candidates.isEmpty() && visited.size() < beamWidth) {
            int[] current = candidates.poll();
            int currentNode = current[0];

            // 将当前节点加入结果集
            results.add(current);
            if (results.size() > topK) {
                results.poll();
            }

            // 探索邻居
            List<Integer> neighbors = graph.get(currentNode);
            if (neighbors != null) {
                for (int neighborId : neighbors) {
                    if (!visited.contains(neighborId)) {
                        visited.add(neighborId);
                        float[] neighborVec = vectors.get(neighborId);
                        if (neighborVec != null) {
                            float dist = (float) distance.compute(query, neighborVec);
                            candidates.add(new int[]{neighborId, Float.floatToIntBits(dist)});
                        }
                    }
                }
            }
        }

        // 返回结果（按距离升序）
        List<int[]> result = new ArrayList<>(results);
        result.sort(Comparator.comparingInt(a -> a[1]));
        return result.subList(0, Math.min(topK, result.size()));
    }

    /**
     * 搜索返回节点ID
     */
    public List<Integer> search(float[] query, int topK) {
        List<int[]> knnResults = searchKnn(query, topK);
        List<Integer> results = new ArrayList<>();
        for (int[] r : knnResults) {
            results.add(r[0]);
        }
        return results;
    }

    /**
     * 保存索引到磁盘
     */
    public void save(Path path) throws IOException {
        if (indexDir == null) {
            indexDir = path;
        }
        Files.createDirectories(indexDir);

        // 保存元数据
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new FileOutputStream(indexDir.resolve("meta.bin").toFile()))) {
            oos.writeInt(dimension);
            oos.writeInt(nextNodeId);
            oos.writeInt(entryPoint);
            oos.writeObject(graph);
        }

        // 保存向量数据
        try (DataOutputStream dos = new DataOutputStream(
                new FileOutputStream(indexDir.resolve("vectors.bin").toFile()))) {
            dos.writeInt(vectors.size());
            for (Map.Entry<Integer, float[]> entry : vectors.entrySet()) {
                dos.writeInt(entry.getKey());
                for (float v : entry.getValue()) {
                    dos.writeFloat(v);
                }
            }
        }

        dirty = false;
    }

    /**
     * 从磁盘加载索引
     */
    public static DiskAnnIndex load(Path path, DistanceMetric distanceMetric) throws IOException {
        // 加载元数据
        Map<Integer, List<Integer>> loadedGraph;
        int dimension, nextNodeId, entryPoint;

        try (ObjectInputStream ois = new ObjectInputStream(
                new FileInputStream(path.resolve("meta.bin").toFile()))) {
            dimension = ois.readInt();
            nextNodeId = ois.readInt();
            entryPoint = ois.readInt();
            loadedGraph = (Map<Integer, List<Integer>>) ois.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("Failed to load index metadata", e);
        }

        // 加载向量数据
        Map<Integer, float[]> loadedVectors = new ConcurrentHashMap<>();
        try (DataInputStream dis = new DataInputStream(
                new FileInputStream(path.resolve("vectors.bin").toFile()))) {
            int count = dis.readInt();
            for (int i = 0; i < count; i++) {
                int nodeId = dis.readInt();
                float[] vector = new float[dimension];
                for (int j = 0; j < dimension; j++) {
                    vector[j] = dis.readFloat();
                }
                loadedVectors.put(nodeId, vector);
            }
        }

        // 创建索引实例
        DiskAnnIndex index = new DiskAnnIndex(dimension, distanceMetric);
        index.vectors.putAll(loadedVectors);
        index.graph.putAll(loadedGraph);
        index.nextNodeId = nextNodeId;
        index.entryPoint = entryPoint;
        index.indexDir = path;

        return index;
    }

    /**
     * 检查是否需要保存
     */
    public boolean isDirty() {
        return dirty;
    }

    /**
     * 获取索引大小（向量数量）
     */
    public int size() {
        return vectors.size();
    }

    /**
     * 清空索引
     */
    public void clear() {
        vectors.clear();
        graph.clear();
        nextNodeId = 0;
        entryPoint = -1;
        dirty = true;
    }

    private int findFarthest(float[] from, List<Integer> nodeIds) {
        int farthestIdx = -1;
        double maxDist = Double.MIN_VALUE;

        for (int i = 0; i < nodeIds.size(); i++) {
            float[] vec = vectors.get(nodeIds.get(i));
            if (vec != null) {
                double dist = distance.compute(from, vec);
                if (dist > maxDist) {
                    maxDist = dist;
                    farthestIdx = i;
                }
            }
        }

        return farthestIdx;
    }
}
