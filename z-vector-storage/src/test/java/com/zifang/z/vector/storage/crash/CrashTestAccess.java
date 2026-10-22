package com.zifang.z.vector.storage.crash;

import com.zifang.z.vector.core.collection.Collection;
import com.zifang.z.vector.core.index.Index;
import com.zifang.z.vector.storage.PersistentVectorStore;
import com.zifang.z.vector.storage.engine.StorageEngine;
import com.zifang.z.vector.storage.wal.WalFile;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * 测试侧对 {@link PersistentVectorStore} 私有状态的一层薄反射壳。
 *
 * <p><b>为什么不改生产代码加 getter</b>：本卡的纪律是「只建验证、不修引擎」。
 * 为了让验证能看见「WAL 到底落盘了没有」「HNSW 图长什么样」「payload 倒排有没有重建」，
 * 用反射读几个私有字段，比给引擎加一堆只为测试存在的 public API 更克制 ——
 * 引擎的行为边界一点没动，验证能力却够了。
 *
 * <p><b>反射一旦失配，套件必须立刻炸</b>：这里不做「拿不到就返回 null」的静默降级 ——
 * 那样量具会悄悄坏掉（读不到字段 ⇒ 断言通过），正是本仓 §3「量具坏了会伪装成产品坏了」的反面。
 */
final class CrashTestAccess {

    private CrashTestAccess() {
    }

    /** 取私有字段 {@code PersistentVectorStore.engine}（可能为 null：同步 WAL 模式）。 */
    static StorageEngine engine(PersistentVectorStore store) {
        return (StorageEngine) field(store, PersistentVectorStore.class, "engine");
    }

    /** 取私有字段 {@code PersistentVectorStore.wal}。 */
    static WalFile wal(PersistentVectorStore store) {
        return (WalFile) field(store, PersistentVectorStore.class, "wal");
    }

    /**
     * <b>持久化屏障</b>：阻塞直到此前提交的所有 WAL 记录都已 fsync 落盘。
     *
     * <p>这是本套件区分「已持久化」与「只是已 ack」的支点：
     * {@code upsert()} 在启用 v2 引擎（默认）时只是把记录<b>入队</b>就返回，
     * 真正落盘由后台 flusher 完成 ⇒ 「kill -9 之后数据还在吗」这个问题，
     * 只有在屏障之后问才有确定答案。
     *
     * @throws IllegalStateException 引擎未启用（同步 WAL 模式本身即持久，无需屏障）
     */
    static void durabilityBarrier(PersistentVectorStore store) {
        StorageEngine engine = engine(store);
        if (engine == null) {
            throw new IllegalStateException("StorageEngine 未启用：同步 WAL 模式本身就是持久的，"
                    + "不应要求屏障（若走到这里说明套件配置与断言前提脱节了）");
        }
        try {
            engine.flushWal();
        } catch (Exception e) {
            throw new IllegalStateException("持久化屏障失败: " + e.getMessage(), e);
        }
    }

    /** 取存活内存态里的 {@link Collection}（恢复后的真实索引持有者）。 */
    static Collection liveCollection(PersistentVectorStore store, String name) {
        Map<String, Collection> map = (Map<String, Collection>) field(store, PersistentVectorStore.class, "collections");
        return map == null ? null : map.get(name);
    }

    /** 取存活内存态里的索引实现（HNSW 图就在它身上）。 */
    static Index liveIndex(PersistentVectorStore store, String name) {
        Collection coll = liveCollection(store, name);
        return coll == null ? null : coll.getIndex();
    }

    /** payload 倒排里已建索引的字段数（0 ⇒ 倒排没重建，过滤只能靠全量扫描兜底）。 */
    static int payloadIndexedFields(PersistentVectorStore store, String name) {
        Collection coll = liveCollection(store, name);
        return coll == null ? -1 : coll.payloadIndexedFields();
    }

    /** payload 倒排快路径命中次数（&gt;0 ⇒ 过滤确实走了倒排，而不是逐点求值）。 */
    static long payloadFastPathHits(PersistentVectorStore store, String name) {
        Collection coll = liveCollection(store, name);
        return coll == null ? -1L : coll.payloadFastPathHits();
    }

    private static Object field(Object target, Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("反射失配：找不到字段 " + owner.getSimpleName() + "." + name
                    + "（引擎改了私有布局？套件必须同步更新，不能静默跳过）", e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("反射失配：读不到字段 " + owner.getSimpleName() + "." + name, e);
        }
    }
}
