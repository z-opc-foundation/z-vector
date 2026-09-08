package com.zifang.z.vector.storage.page;

import java.util.Objects;

/**
 * 页面标识 — 全局唯一标识一个 page。
 * <p>
 * 由三段组成：
 * <ul>
 *   <li>{@code collectionId}：所属集合（hash of collection name，4B）；</li>
 *   <li>{@code type}：页面类型；</li>
 *   <li>{@code pageNo}：集合内页号（4B）。</li>
 * </ul>
 */
public final class PageId {

    /** 集合名 → collectionId（用 hashCode，碰撞概率极低，4B 足够）。 */
    private final int collectionId;
    private final PageType type;
    private final int pageNo;

    public PageId(int collectionId, PageType type, int pageNo) {
        if (type == null) throw new IllegalArgumentException("type is null");
        this.collectionId = collectionId;
        this.type = type;
        this.pageNo = pageNo;
    }

    /** 字符串集合名 → PageId 的便捷构造（内部取 hashCode 作为 collectionId） */
    public static PageId of(String collection, PageType type, int pageNo) {
        return new PageId(Objects.requireNonNull(collection, "collection").hashCode(),
                type, pageNo);
    }

    public int collectionId() { return collectionId; }
    public PageType type() { return type; }
    public int pageNo() { return pageNo; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PageId)) return false;
        PageId other = (PageId) o;
        return collectionId == other.collectionId && pageNo == other.pageNo && type == other.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(collectionId, type, pageNo);
    }

    @Override
    public String toString() {
        return "PageId{" + collectionId + "/" + type + "#" + pageNo + "}";
    }
}
