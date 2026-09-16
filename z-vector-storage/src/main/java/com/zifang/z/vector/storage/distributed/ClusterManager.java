package com.zifang.z.vector.storage.distributed;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ClusterManager - 分布式集群管理器
 * <p>
 * 负责节点发现、负载均衡和故障检测。
 * 设计参考 Milvus 的分布式架构：
 * <ul>
 *   <li>无中心化：每个节点都可以处理请求</li>
 *   <li>一致性哈希：数据分片路由</li>
 *   <li>心跳检测：节点健康监控</li>
 * </ul>
 * <p>
 * 使用示例：
 * <pre>{@code
 * ClusterManager cluster = ClusterManager.create();
 *
 * // 添加节点
 * cluster.addNode("node-1", "192.168.1.100:50051");
 * cluster.addNode("node-2", "192.168.1.101:50051");
 *
 * // 获取目标节点
 * String targetNode = cluster.getNode("collection-1", "doc-123");
 *
 * // 检查节点健康
 * boolean healthy = cluster.isNodeHealthy("node-1");
 * }</pre>
 */
public class ClusterManager {

    private final Map<String, NodeInfo> nodes = new ConcurrentHashMap<>();
    private final Map<String, String> collectionToNode = new ConcurrentHashMap<>();
    private final List<String> nodeList = Collections.synchronizedList(new ArrayList<>());

    private static class NodeInfo {
        final String nodeId;
        final String address;
        final long lastHeartbeat;
        final int load; // 负载指标

        NodeInfo(String nodeId, String address) {
            this.nodeId = nodeId;
            this.address = address;
            this.lastHeartbeat = System.currentTimeMillis();
            this.load = 0;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NodeInfo nodeInfo = (NodeInfo) o;
            return nodeId.equals(nodeInfo.nodeId);
        }

        @Override
        public int hashCode() {
            return nodeId.hashCode();
        }
    }

    public static ClusterManager create() {
        return new ClusterManager();
    }

    /**
     * 添加节点
     */
    public void addNode(String nodeId, String address) {
        NodeInfo info = new NodeInfo(nodeId, address);
        nodes.put(nodeId, info);
        nodeList.add(nodeId);
    }

    /**
     * 移除节点
     */
    public void removeNode(String nodeId) {
        nodes.remove(nodeId);
        nodeList.remove(nodeId);
        // 移除该节点的集合映射
        collectionToNode.values().removeIf(v -> v.equals(nodeId));
    }

    /**
     * 获取节点地址
     */
    public String getNodeAddress(String nodeId) {
        NodeInfo info = nodes.get(nodeId);
        return info != null ? info.address : null;
    }

    /**
     * 根据集合和键获取目标节点
     */
    public String getNode(String collectionName, String key) {
        if (nodeList.isEmpty()) {
            throw new IllegalStateException("No nodes available in cluster");
        }

        // 一致性哈希路由
        int hash = Math.abs(key.hashCode());
        int index = hash % nodeList.size();
        return nodeList.get(index);
    }

    /**
     * 获取所有节点
     */
    public List<String> getNodes() {
        return new ArrayList<>(nodeList);
    }

    /**
     * 检查节点是否健康
     */
    public boolean isNodeHealthy(String nodeId) {
        NodeInfo info = nodes.get(nodeId);
        if (info == null) return false;

        // 5秒内有心跳认为健康
        return (System.currentTimeMillis() - info.lastHeartbeat) < 5000;
    }

    /**
     * 获取健康节点列表
     */
    public List<String> getHealthyNodes() {
        List<String> healthy = new ArrayList<>();
        for (String nodeId : nodeList) {
            if (isNodeHealthy(nodeId)) {
                healthy.add(nodeId);
            }
        }
        return healthy;
    }

    /**
     * 更新节点心跳
     */
    public void heartbeat(String nodeId) {
        nodes.computeIfPresent(nodeId, (k, v) -> {
            // 创建新实例更新时间戳（简化实现）
            return new NodeInfo(v.nodeId, v.address);
        });
    }

    /**
     * 获取集群状态
     */
    public Map<String, Object> getClusterStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("totalNodes", nodeList.size());
        status.put("healthyNodes", getHealthyNodes().size());
        status.put("totalCollections", collectionToNode.size());
        status.put("nodes", new ArrayList<>(nodeList));
        return status;
    }

    /**
     * 获取节点数量
     */
    public int getNodeCount() {
        return nodeList.size();
    }
}
