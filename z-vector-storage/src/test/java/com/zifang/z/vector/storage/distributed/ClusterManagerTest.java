package com.zifang.z.vector.storage.distributed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClusterManager 分布式测试
 */
class ClusterManagerTest {

    private ClusterManager cluster;

    @BeforeEach
    void setUp() {
        cluster = ClusterManager.create();
    }

    @Test
    void addNode() {
        cluster.addNode("node-1", "192.168.1.100:50051");

        assertEquals(1, cluster.getNodeCount());
        assertEquals("192.168.1.100:50051", cluster.getNodeAddress("node-1"));
    }

    @Test
    void addMultipleNodes() {
        cluster.addNode("node-1", "192.168.1.100:50051");
        cluster.addNode("node-2", "192.168.1.101:50051");
        cluster.addNode("node-3", "192.168.1.102:50051");

        assertEquals(3, cluster.getNodeCount());
    }

    @Test
    void removeNode() {
        cluster.addNode("node-1", "192.168.1.100:50051");
        cluster.addNode("node-2", "192.168.1.101:50051");

        cluster.removeNode("node-1");

        assertEquals(1, cluster.getNodeCount());
        assertNull(cluster.getNodeAddress("node-1"));
    }

    @Test
    void getNodeRouting() {
        cluster.addNode("node-1", "192.168.1.100:50051");
        cluster.addNode("node-2", "192.168.1.101:50051");

        String nodeId = cluster.getNode("collection-1", "doc-123");
        assertNotNull(nodeId);
        assertTrue(cluster.getNodes().contains(nodeId));
    }

    @Test
    void nodeHealth() {
        cluster.addNode("node-1", "192.168.1.100:50051");

        // 刚添加的节点应该健康
        assertTrue(cluster.isNodeHealthy("node-1"));
    }

    @Test
    void getHealthyNodes() {
        cluster.addNode("node-1", "192.168.1.100:50051");
        cluster.addNode("node-2", "192.168.1.101:50051");

        List<String> healthy = cluster.getHealthyNodes();
        assertEquals(2, healthy.size());
    }

    @Test
    void heartbeat() {
        cluster.addNode("node-1", "192.168.1.100:50051");

        cluster.heartbeat("node-1");
        assertTrue(cluster.isNodeHealthy("node-1"));
    }

    @Test
    void getClusterStatus() {
        cluster.addNode("node-1", "192.168.1.100:50051");
        cluster.addNode("node-2", "192.168.1.101:50051");

        Map<String, Object> status = cluster.getClusterStatus();
        assertEquals(2, status.get("totalNodes"));
        assertEquals(2, status.get("healthyNodes"));
    }

    @Test
    void noNodesThrows() {
        assertThrows(IllegalStateException.class, () -> {
            cluster.getNode("collection-1", "doc-123");
        });
    }

    @Test
    void getNodeAddressNonExistent() {
        assertNull(cluster.getNodeAddress("non-existent"));
    }
}
