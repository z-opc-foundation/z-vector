package com.zifang.z.vector.api;

/**
 * VectorStore 工厂 — 简化常用 store 实例的创建。
 * <p>
 * 各实现位于不同的模块:
 * <ul>
 *   <li>{@code InMemoryVectorStore} — 纯内存版，见 {@code z-vector-core}</li>
 *   <li>{@code PersistentVectorStore} — 持久化版，见 {@code z-vector-core + z-vector-storage}</li>
 * </ul>
 *
 * <h2>典型用法</h2>
 * <pre>{@code
 * // 纯内存
 * try (VectorStore store = VectorStoreFactory.inMemory()) {
 *     store.createCollection("docs", 768, DistanceMetric.COSINE);
 *     store.upsert("docs", new VectorPoint("d1", new float[768]));
 *     store.search("docs", query, 10, null);
 * }
 *
 * // 持久化（带 WAL + snapshot）
 * try (VectorStore store = VectorStoreFactory.persistent("/data/zvec")) {
 *     ...
 * }
 * }</pre>
 */
public final class VectorStoreFactory {

    private VectorStoreFactory() {}

    /**
     * 创建纯内存版 VectorStore（默认实现）
     */
    public static VectorStore inMemory() {
        try {
            Class<?> clazz = Class.forName("com.zifang.z.vector.core.InMemoryVectorStore");
            return (VectorStore) clazz.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new VectorException(
                    "z-vector-core 模块未引入，请添加依赖: com.zifang:z-vector-core", e);
        } catch (Exception e) {
            throw new VectorException("创建 InMemoryVectorStore 失败", e);
        }
    }

    /**
     * 创建持久化版 VectorStore（基于磁盘 + WAL）
     *
     * @param dataDir 数据目录（不存在会自动创建）
     */
    public static VectorStore persistent(String dataDir) {
        try {
            Class<?> clazz = Class.forName("com.zifang.z.vector.storage.PersistentVectorStore");
            return (VectorStore) clazz.getDeclaredConstructor(String.class).newInstance(dataDir);
        } catch (ClassNotFoundException e) {
            throw new VectorException(
                    "z-vector-storage 模块未引入，请添加依赖: com.zifang:z-vector-storage", e);
        } catch (Exception e) {
            throw new VectorException("创建 PersistentVectorStore 失败: " + dataDir, e);
        }
    }
}