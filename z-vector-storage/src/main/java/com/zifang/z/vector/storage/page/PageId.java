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

    /**
     * 集合名 → collectionId（取 {@code hashCode()}，4B）。
     * <p>
     * 两个不同名字撞成同一个 int 是可能的（概率约 1/2^32 一对），这一层没法区分——页头里存的
     * 也就是这个 int。能保证的是<b>不再雪上加霜</b>：文件名此前取绝对值，于是
     * {@code h} 与 {@code -h} 这一对名字必然共用一个文件，而 {@code PageStore.read(PageId)}
     * 从不核对页头里的 id，读回来的是别人的页却一句不报（现由 {@code requireSamePage} 拦住，
     * 文件名也改成带符号单射）。
     */
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
