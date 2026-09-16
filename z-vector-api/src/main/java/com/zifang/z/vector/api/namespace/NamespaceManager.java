package com.zifang.z.vector.api.namespace;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NamespaceManager - 多租户管理器
 * <p>
 * 负责 namespace 的创建、删除、权限检查和配额管理。
 * 设计参考 Milvus 的多租户方案：每个请求携带 namespace + userId，
 * NamespaceManager 负责验证权限并路由到正确的 VectorStore 实例。
 * <p>
 * 使用示例：
 * <pre>{@code
 * NamespaceManager mgr = NamespaceManager.createDefault();
 *
 * // 创建 namespace
 * Namespace ns = Namespace.builder("tenant-a")
 *     .description("租户 A 的向量数据")
 *     .maxCollections(50)
 *     .maxPoints(100_000)
 *     .grant("user-1", Namespace.Permission.READ, Namespace.Permission.WRITE)
 *     .grant("admin-1", Namespace.Permission.ADMIN)
 *     .build();
 * mgr.createNamespace(ns);
 *
 * // 验证权限
 * if (mgr.checkPermission("tenant-a", "user-1", Namespace.Permission.READ)) {
 *     // 允许读操作
 * }
 * }</pre>
 */
public class NamespaceManager {

    private final Map<String, Namespace> namespaces = new ConcurrentHashMap<>();
    private final Namespace defaultNamespace;

    public NamespaceManager() {
        this.defaultNamespace = Namespace.builder("default")
            .description("Default namespace")
            .maxCollections(Integer.MAX_VALUE)
            .maxPoints(Integer.MAX_VALUE)
            .maxStorageBytes(Long.MAX_VALUE)
            .build();
        namespaces.put("default", defaultNamespace);
    }

    /**
     * 创建默认 NamespaceManager
     */
    public static NamespaceManager createDefault() {
        return new NamespaceManager();
    }

    /**
     * 创建 NamespaceManager（带预配置的 namespace）
     */
    public static NamespaceManager createWithNamespaces(Namespace... namespaces) {
        NamespaceManager mgr = new NamespaceManager();
        for (Namespace ns : namespaces) {
            mgr.createNamespace(ns);
        }
        return mgr;
    }

    /**
     * 创建 namespace
     */
    public void createNamespace(Namespace namespace) {
        if (namespaces.containsKey(namespace.getName())) {
            throw new IllegalArgumentException("Namespace already exists: " + namespace.getName());
        }
        namespaces.put(namespace.getName(), namespace);
    }

    /**
     * 删除 namespace
     */
    public boolean deleteNamespace(String name) {
        if ("default".equals(name)) {
            throw new IllegalStateException("Cannot delete default namespace");
        }
        return namespaces.remove(name) != null;
    }

    /**
     * 获取 namespace
     */
    public Namespace getNamespace(String name) {
        return namespaces.getOrDefault(name, defaultNamespace);
    }

    /**
     * 列出所有 namespace
     */
    public List<String> listNamespaces() {
        return new ArrayList<>(namespaces.keySet());
    }

    /**
     * 检查用户权限
     */
    public boolean checkPermission(String namespaceName, String userId, Namespace.Permission permission) {
        Namespace ns = getNamespace(namespaceName);
        return ns.hasPermission(userId, permission);
    }

    /**
     * 验证操作权限（无权限时抛异常）
     */
    public void requirePermission(String namespaceName, String userId, Namespace.Permission permission) {
        if (!checkPermission(namespaceName, userId, permission)) {
            throw new SecurityException(String.format(
                "Permission denied: user=%s, namespace=%s, required=%s",
                userId, namespaceName, permission));
        }
    }

    /**
     * 添加集合到 namespace
     */
    public void addCollectionToNamespace(String namespaceName, String collectionName) {
        Namespace ns = getNamespace(namespaceName);
        if (!ns.canCreateCollection()) {
            throw new IllegalStateException("Namespace quota exceeded: " + namespaceName);
        }
        ns.addCollection(collectionName);
    }

    /**
     * 从 namespace 移除集合
     */
    public void removeCollectionFromNamespace(String namespaceName, String collectionName) {
        Namespace ns = getNamespace(namespaceName);
        ns.removeCollection(collectionName);
    }

    /**
     * 获取 namespace 的集合列表
     */
    public Set<String> getNamespaceCollections(String namespaceName) {
        return getNamespace(namespaceName).getCollections();
    }

    /**
     * 检查 namespace 是否存在
     */
    public boolean namespaceExists(String name) {
        return namespaces.containsKey(name);
    }

    /**
     * 获取 namespace 总数
     */
    public int getNamespaceCount() {
        return namespaces.size();
    }
}
