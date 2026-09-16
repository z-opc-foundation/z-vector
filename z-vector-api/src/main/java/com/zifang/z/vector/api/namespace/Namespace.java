package com.zifang.z.vector.api.namespace;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Namespace - 多租户隔离的核心单元
 * <p>
 * 每个 Namespace 拥有独立的集合空间、权限配置和配额限制。
 * 设计参考 Milvus 的多租户方案：基于 Collection 级别隔离，支持 namespace + collection 两级寻址。
 * <p>
 * 权限模型：
 * <ul>
 *   <li>READ: 可搜索/查询</li>
 *   <li>WRITE: 可 upsert/delete</li>
 *   <li>ADMIN: 可 create/drop namespace</li>
 * </ul>
 */
public class Namespace {

    public enum Permission {
        READ(1),
        WRITE(2),
        ADMIN(4);

        private final int bit;

        Permission(int bit) {
            this.bit = bit;
        }

        public int getBit() {
            return bit;
        }

        public boolean includes(Permission other) {
            return (this.bit & other.bit) == other.bit;
        }
    }

    private final String name;
    private final String description;
    private final int maxCollections;
    private final int maxPoints;
    private final long maxStorageBytes;
    private final Map<String, Integer> userPermissions; // userId -> permission bitmask
    private final Set<String> collections; // 所属集合

    private Namespace(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.maxCollections = builder.maxCollections;
        this.maxPoints = builder.maxPoints;
        this.maxStorageBytes = builder.maxStorageBytes;
        this.userPermissions = new HashMap<>(builder.userPermissions);
        this.collections = new java.util.HashSet<>(builder.collections);
    }

    // Getters
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getMaxCollections() { return maxCollections; }
    public int getMaxPoints() { return maxPoints; }
    public long getMaxStorageBytes() { return maxStorageBytes; }
    public Set<String> getCollections() { return Collections.unmodifiableSet(collections); }

    /**
     * 检查用户是否有指定权限
     */
    public boolean hasPermission(String userId, Permission permission) {
        if (userId == null) return false;
        Integer perm = userPermissions.get(userId);
        if (perm == null) return false;
        return (perm & permission.getBit()) != 0;
    }

    /**
     * 授予用户权限
     */
    public void grantPermission(String userId, Permission permission) {
        userPermissions.merge(userId, permission.getBit(), (a, b) -> a | b);
    }

    /**
     * 撤销用户权限
     */
    public void revokePermission(String userId, Permission permission) {
        userPermissions.computeIfPresent(userId, (k, v) -> v & ~permission.getBit());
    }

    /**
     * 添加集合到 namespace
     */
    public void addCollection(String collectionName) {
        collections.add(collectionName);
    }

    /**
     * 从 namespace 移除集合
     */
    public void removeCollection(String collectionName) {
        collections.remove(collectionName);
    }

    /**
     * 检查是否可以创建新集合
     */
    public boolean canCreateCollection() {
        return collections.size() < maxCollections;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final String name;
        private String description = "";
        private int maxCollections = 100;
        private int maxPoints = 1_000_000;
        private long maxStorageBytes = 10L * 1024 * 1024 * 1024; // 10GB
        private final Map<String, Integer> userPermissions = new HashMap<>();
        private final Set<String> collections = new java.util.HashSet<>();

        public Builder(String name) {
            this.name = name;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder maxCollections(int maxCollections) {
            this.maxCollections = maxCollections;
            return this;
        }

        public Builder maxPoints(int maxPoints) {
            this.maxPoints = maxPoints;
            return this;
        }

        public Builder maxStorageBytes(long maxStorageBytes) {
            this.maxStorageBytes = maxStorageBytes;
            return this;
        }

        public Builder grant(String userId, Permission... permissions) {
            int perm = 0;
            for (Permission p : permissions) {
                perm |= p.getBit();
            }
            userPermissions.put(userId, perm);
            return this;
        }

        public Builder withCollection(String collectionName) {
            collections.add(collectionName);
            return this;
        }

        public Namespace build() {
            return new Namespace(this);
        }
    }
}
