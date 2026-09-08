package com.zifang.z.vector.storage.page;

import com.zifang.util.core.io.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * PageStore — 把 {@link Page} 持久化到磁盘文件。
 * <p>
 * 设计目标：
 * <ul>
 *   <li>每个 Page 固定大小（{@link Page#DEFAULT_PAGE_SIZE}，64KB），随机寻址简单；</li>
 *   <li>同一目录存多个 PageStore，每个 PageStore 对应一个集合（{@code pages_<collectionId>.pgs}）；</li>
 *   <li>提供 {@link #read(PageId)} / {@link #write(Page)} 两个核心操作；</li>
 *   <li>崩溃安全：写时随机寻址 + 强制 fsync，避免部分写入污染；CRC32 在 Page 内自带校验。</li>
 * </ul>
 * <h2>文件命名</h2>
 * {@code <dataDir>/pages_<collectionId>.pgs} — 每个集合一个文件，集合内页号 → 文件内偏移。
 * <p>
 * <h2>线程安全</h2>
 * 内部使用 {@link RandomAccessFile}，每次操作独立打开 + 关闭（避免多线程竞争），
 * 性能足够覆盖单集合场景；高频写入建议配合 BufferPool。
 */
public class PageStore {

    private static final Logger LOG = LoggerFactory.getLogger(PageStore.class);

    private final String dataDir;
    private final int collectionId;
    private final String collectionName;  // 仅用于日志
    private final int pageSize;
    private volatile boolean useMmap;     // 是否对 read 启用 mmap
    private volatile MmapPageReader mmapReader;  // 懒初始化

    /**
     * Free page bitmap（按 pageNo 排序）。
     * <p>
     * 设计参考 RocksDB / SQLite VACUUM：删除 page 时不立即截断文件，而是标记为 free，
     * 后续 {@link #write(Page)} 优先复用 free slot（避免文件无限增长）。
     * <p>
     * 线程安全：所有操作 {@code synchronized(this)}，因为 set + add + 写盘需要原子配合。
     */
    private final Set<Integer> freePages = new TreeSet<>();
    private final Object freeLock = new Object();

    public PageStore(String dataDir, String collectionName) {
        this.dataDir = dataDir;
        this.collectionName = collectionName;
        this.collectionId = collectionName.hashCode();
        this.pageSize = Page.DEFAULT_PAGE_SIZE;
    }

    /**
     * 直接传入 collectionId（用于已经持有 hash code 的场景，避免双重 hash）。
     */
    public PageStore(String dataDir, int collectionId, String collectionName) {
        this.dataDir = dataDir;
        this.collectionName = collectionName;
        this.collectionId = collectionId;
        this.pageSize = Page.DEFAULT_PAGE_SIZE;
    }

    /** 数据目录 */
    public String dataDir() { return dataDir; }
    public String collectionName() { return collectionName; }
    public int collectionId() { return collectionId; }
    public int pageSize() { return pageSize; }

    /**
     * 启用 mmap 读取（后续 read() 会走 mmap 路径，零系统调用）。
     * <p>
     * 适用场景：冷读多 / 单集合文件较大（&gt; 64MB）。对于写入密集场景不建议启用。
     */
    public PageStore useMmap(boolean enable) {
        this.useMmap = enable;
        if (!enable && mmapReader != null) {
            mmapReader.close();
            mmapReader = null;
        }
        return this;
    }

    public boolean isMmapEnabled() { return useMmap; }

    /** 当前 mmap 视图（调试用）。 */
    public MmapPageReader mmapReader() { return mmapReader; }

    /** 当前集合的 PageStore 文件路径 */
    public Path file() {
        return Paths.get(dataDir, "pages_" + Math.abs(collectionId) + ".pgs");
    }

    /**
     * 读取指定页 — 不存在则抛 IOException。
     * <p>
     * 启用 mmap 时走 {@link MmapPageReader}（零系统调用）；否则传统 RandomAccessFile 路径。
     */
    public Page read(PageId id) throws IOException {
        if (id.collectionId() != collectionId) {
            throw new IllegalArgumentException(
                    "PageStore collectionId mismatch: store=" + collectionId
                            + " page=" + id.collectionId());
        }
        Path path = file();
        if (!Files.exists(path)) {
            throw new IOException("PageStore file not found: " + path);
        }
        if (useMmap) {
            MmapPageReader reader = mmapReader;
            if (reader == null) {
                synchronized (this) {
                    if (mmapReader == null) {
                        mmapReader = new MmapPageReader(path, pageSize);
                    }
                    reader = mmapReader;
                }
            }
            return reader.read(id);
        }
        long offset = (long) id.pageNo() * pageSize;
        if (offset < 0) {
            throw new IOException("Invalid pageNo: " + id.pageNo());
        }

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            if (offset + pageSize > raf.length()) {
                throw new IOException("Page out of range: pageNo=" + id.pageNo()
                        + " (file size=" + raf.length() + ")");
            }
            raf.seek(offset);
            byte[] buf = new byte[pageSize];
            raf.readFully(buf);
            return Page.deserialize(buf, pageSize);
        }
    }

    /**
     * 写入（覆盖）指定页。
     * <p>
     * 采用 write-then-fsync 顺序保证崩溃一致性：写完后 {@code raf.getFD().sync()}
     * 确保 page 落盘。
     * <p>
     * 复用策略：如果 {@code pageNo} 已被标记为 free，则改写到最小 free slot（保pageNo 顺序），
     * 并返回；否则按原 pageNo 写入并扩展文件。
     *
     * @return 实际写入的 pageNo（如果改写到了 free slot，与输入可能不同）
     */
    public int write(Page page) throws IOException {
        if (page.id().collectionId() != collectionId) {
            throw new IllegalArgumentException(
                    "PageStore collectionId mismatch: store=" + collectionId
                            + " page=" + page.id().collectionId());
        }
        Path path = file();
        // 确保目录存在
        FileUtil.mkdirs(path.getParent().toString());

        // 复用 free slot：如果 pageNo 已被 free，改为写到更小的 free slot
        int targetPageNo = page.id().pageNo();
        PageId targetId = page.id();
        synchronized (freeLock) {
            Integer minFree = freePages.isEmpty() ? null : freePages.iterator().next();
            if (minFree != null && minFree < targetPageNo) {
                // 改写到 free slot：page id 改写，caller 通过返回值感知
                targetPageNo = minFree;
                freePages.remove(minFree);
                targetId = new PageId(page.id().collectionId(), page.id().type(), minFree);
            } else if (freePages.contains(targetPageNo)) {
                // 该 pageNo 已被 free，复用同一个 slot
                freePages.remove(targetPageNo);
            }
        }

        long offset = (long) targetPageNo * pageSize;
        PageId finalId = targetId;
        byte[] bytes = page.serializeWithId(finalId);
        if (bytes.length != pageSize) {
            throw new IOException("Serialized page size mismatch: " + bytes.length
                    + " vs " + pageSize);
        }
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            // 确保文件足够长
            long needed = offset + pageSize;
            if (raf.length() < needed) {
                raf.setLength(needed);
            }
            raf.seek(offset);
            raf.write(bytes);
            raf.getFD().sync();
        }
        // 写后让 mmap 视图失效（重新 mmap 后才会反映新内容）
        if (mmapReader != null) {
            mmapReader.invalidate();
        }
        return targetPageNo;
    }

    /**
     * 标记指定 page 为 free（逻辑删除）。
     * <p>
     * 对应的磁盘 slot 不会立即截断，但会被 {@link #write(Page)} 复用。
     * <p>
     * 反复 free + write 可以避免文件无限增长；最终调用 {@link #compact()} 可以
     * 把 file 末尾的空洞真正消除（参考 RocksDB level compaction / SQLite VACUUM）。
     */
    public void freePage(int pageNo) {
        synchronized (freeLock) {
            freePages.add(pageNo);
        }
    }

    /** 当前 free slot 数量。 */
    public int freePageCount() {
        synchronized (freeLock) {
            return freePages.size();
        }
    }

    /**
     * 把所有 live pages 重新写入新文件，剔除空洞（LSM-style compaction / SQLite VACUUM）。
     * <p>
     * 流程：
     * <ol>
     *   <li>列出所有 live pageNo；</li>
     *   <li>按 pageNo 顺序读到临时文件 {@code pages_<cid>.pgs.tmp}；</li>
     *   <li>fsync 后原子 rename 替换原文件。</li>
     * </ol>
     * <p>
     * 收益：删除大量 points 后文件可缩容；pageNo 重新紧凑（避免 pageNo 越来越大）。
     * <p>
     * 注意：调用方负责把 pageNo 映射关系持久化（如更新 PageSnapshot manifest），
     * 否则压缩后旧 pageNo 会指向错误内容。
     *
     * @return pageNo 映射：oldPageNo → newPageNo
     */
    public java.util.Map<Integer, Integer> compact() throws IOException {
        Path src = file();
        if (!Files.exists(src)) return new java.util.HashMap<>();

        // 收集 live pages（按 pageNo 排序，保证压缩后顺序一致）
        // 注意：跳过已被标记为 free 的 page（freePages）
        List<Integer> livePageNos = new ArrayList<>(listPageNos());
        livePageNos.sort(Integer::compareTo);
        synchronized (freeLock) {
            livePageNos.removeAll(freePages);
        }

        // 写临时文件
        Path tmp = Paths.get(dataDir, "pages_" + Math.abs(collectionId) + ".pgs.tmp");
        java.util.Map<Integer, Integer> mapping = new java.util.HashMap<>();
        try (RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rw")) {
            raf.setLength(0);
            int newNo = 0;
            for (int oldNo : livePageNos) {
                Page p = read(PageId.of(collectionName, PageType.DATA, oldNo));
                // 写时统一改为 DATA 类型 + 新 pageNo（因为 caller 可能用了 INDEX/BLOOM 等）
                PageId newId = new PageId(collectionId, p.id().type(), newNo);
                Page rewritten = new Page(newId, p.payload());
                byte[] bytes = rewritten.serialize();
                raf.write(bytes);
                mapping.put(oldNo, newNo);
                newNo++;
            }
            raf.getFD().sync();
        }

        // 原子替换
        Files.move(tmp, src, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        // 清空 free bitmap（压缩后没有空洞）
        synchronized (freeLock) {
            freePages.clear();
        }
        // mmap 视图失效
        if (mmapReader != null) {
            mmapReader.invalidate();
        }
        LOG.info("PageStore compacted: collection={}, live={}, oldSize={}B, newSize={}B",
                collectionName, livePageNos.size(),
                src.toFile().length() - (long) livePageNos.size() * pageSize, src.toFile().length());
        return mapping;
    }

    /**
     * 列出当前 PageStore 中所有已分配的页号。
     * <p>
     * 通过扫描文件长度 / pageSize 计算；对每个 slot 检查 magic 是否匹配，
     * 不匹配视为空洞（未写入），跳过。
     */
    public Set<Integer> listPageNos() throws IOException {
        Path path = file();
        if (!Files.exists(path)) return new HashSet<>();
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long len = raf.length();
            if (len == 0 || len % pageSize != 0) return new HashSet<>();
            int count = (int) (len / pageSize);
            Set<Integer> set = new HashSet<>(count);
            byte[] headerBuf = new byte[Page.MAGIC.length];
            for (int i = 0; i < count; i++) {
                raf.seek((long) i * pageSize);
                int read = raf.read(headerBuf);
                if (read != Page.MAGIC.length) continue;
                boolean magicMatch = true;
                for (int j = 0; j < Page.MAGIC.length; j++) {
                    if (headerBuf[j] != Page.MAGIC[j]) {
                        magicMatch = false;
                        break;
                    }
                }
                if (magicMatch) set.add(i);
            }
            return set;
        }
    }

    /** 删除 PageStore 文件（慎用）。 */
    public void delete() throws IOException {
        if (mmapReader != null) {
            mmapReader.close();
            mmapReader = null;
        }
        Files.deleteIfExists(file());
        LOG.info("PageStore deleted: {}", file());
    }

    /** 当前 PageStore 的磁盘占用（字节）。 */
    public long diskSize() throws IOException {
        Path path = file();
        if (!Files.exists(path)) return 0;
        return Files.size(path);
    }
}
