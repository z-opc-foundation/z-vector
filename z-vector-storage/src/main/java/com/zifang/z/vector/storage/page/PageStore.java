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
 * {@code collectionId} 原样带符号写进文件名：它是集合名的 {@code hashCode()}，正负都会出现，
 * 取绝对值会让 ± 一对名字共用同一个文件（详见 {@link #file()}）。负 hash 集合在 1.0.3 之前
 * 落的是绝对值名，那批旧目录由 {@link #resolveFile()} 按页头归属继续延用，不改名、不复制。
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

    /**
     * 当前集合的 PageStore 文件路径 —— <b>这里不能再取绝对值</b>。
     * <p>
     * {@code collectionId} 是集合名的 {@code hashCode()}，正负都会出现；旧写法
     * {@code pages_<|collectionId|>.pgs} 把互为相反数的一对折成同一个文件，两个互不相干的
     * 集合于是写进同一份页存储。实测（{@code PageStoreAbsCollisionProbe}，词典里这种名字成对
     * 存在）："Gretel"=+2141074721 写 3 页，"nudeness"=-2141074721 再写 3 页 ⇒ 前者读回来拿到
     * 的是<b>后者的数据</b>，CRC 合法、页号对得上，一个异常都不抛。
     * <p>
     * 带符号打印是单射的；正数集合的名字与旧格式逐字相同，所以只有"负 hash 集合"换名，
     * 那批旧目录由 {@link #resolveFile()} 按页头归属继续延用。
     */
    public Path file() {
        return Paths.get(dataDir, "pages_" + collectionId + ".pgs");
    }

    /** 旧格式（{@code Math.abs}）给负 hash 集合用的文件名；正数集合新旧同名，无需兼容。 */
    private Path legacyFile() {
        int abs = Math.abs(collectionId);
        if (collectionId >= 0 || abs == collectionId) return null;
        return Paths.get(dataDir, "pages_" + abs + ".pgs");
    }

    /** 页头里 collectionId 的偏移（magic 4B + pageType 1B）。 */
    private static final int COLLECTION_ID_OFFSET = 5;

    /**
     * 实际要读写的那个文件。
     * <p>
     * 优先用 {@link #file()}；只有正名还不存在、而旧名文件存在<b>且页头确认属于本集合</b>时，
     * 才继续用旧文件——不改名也不复制，避免"升级即丢数据"。归属判定只看第一张有效页头里的
     * 4 字节 collectionId（不做 CRC：越是崩溃过的文件越不该因为 CRC 拒绝认领）；读不出任何
     * 有效页、或首页属于别人，就一律不碰旧文件，从正名重新开始。
     */
    private Path resolveFile() {
        Path fresh = file();
        if (collectionId < 0) {
            Path legacy = legacyFile();
            if (legacy != null && !Files.exists(fresh) && legacyOwnedByUs(legacy)) {
                return legacy;
            }
        }
        return fresh;
    }

    private boolean legacyOwnedByUs(Path legacy) {
        try (RandomAccessFile raf = new RandomAccessFile(legacy.toFile(), "r")) {
            int count = (int) (raf.length() / pageSize);
            byte[] header = new byte[COLLECTION_ID_OFFSET + 4];
            for (int i = 0; i < count; i++) {
                raf.seek((long) i * pageSize);
                if (!readHeader(raf, header)) continue;
                return readIntAt(header, COLLECTION_ID_OFFSET) == collectionId;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /** 读一页的头部前 9 字节；短读要重试补齐，"读不满"不能当成"这页不存在"。 */
    private static boolean readHeader(RandomAccessFile raf, byte[] header) throws IOException {
        int got = 0;
        while (got < header.length) {
            int n = raf.read(header, got, header.length - got);
            if (n < 0) return false;
            got += n;
        }
        for (int j = 0; j < Page.MAGIC.length; j++) {
            if (header[j] != Page.MAGIC[j]) return false;
        }
        return true;
    }

    private static int readIntAt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /**
     * 读回来的必须就是请求的那一页。页头自带 collectionId/pageNo，但 {@link Page#deserialize}
     * 只按字节重建、从不核对请求 —— {@code PageStoreTest.readAndWritePage} 里那句
     * {@code assertEquals(id, p.id())} 一直只是"文件名刚好没撞上"的侥幸。
     * <p>
     * 类型故意不在核对范围内：{@link #compact()} 拿 {@code PageType.DATA} 当"任意类型"的占位
     * 去逐页读，实际页可能是 INDEX/BLOOM。
     */
    private static void requireSamePage(Page got, PageId requested) throws IOException {
        PageId actual = got.id();
        if (actual.collectionId() != requested.collectionId() || actual.pageNo() != requested.pageNo()) {
            throw new IOException("Page identity mismatch: requested " + requested
                    + " but the slot holds " + actual + " — 这个文件被另一个集合写过");
        }
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
        Path path = resolveFile();
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
            Page viaMmap = reader.read(id);
            requireSamePage(viaMmap, id);
            return viaMmap;
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
            Page p = Page.deserialize(buf, pageSize);
            requireSamePage(p, id);
            return p;
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
        Path path = resolveFile();
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
        Path src = resolveFile();
        if (!Files.exists(src)) return new java.util.HashMap<>();

        // 收集 live pages（按 pageNo 排序，保证压缩后顺序一致）
        // 注意：跳过已被标记为 free 的 page（freePages）
        List<Integer> livePageNos = new ArrayList<>(listPageNos());
        livePageNos.sort(Integer::compareTo);
        synchronized (freeLock) {
            livePageNos.removeAll(freePages);
        }

        // 一页都没读出来、文件却不是空的 ⇒ 这是"读不出来"，不是"真的没有"。
        // 下面无条件 ATOMIC_MOVE 会把整个集合清空，所以这条闸是承重的：旧写法在
        // len % pageSize != 0 时让 listPageNos 直接返回空集，"末尾多出几个字节"这件小事
        // 就等于全库蒸发（而且 compact 的返回值是空 map，caller 看不出差别）。
        if (livePageNos.isEmpty() && Files.size(src) > 0) {
            LOG.warn("PageStore compact skipped: collection={}, file={}B 但读不出任何有效页 —— 保留原文件",
                    collectionName, Files.size(src));
            return new java.util.HashMap<>();
        }

        // 写临时文件（名字跟着 src 走，± 两个集合不再有同一个 tmp）
        Path tmp = Paths.get(dataDir, src.getFileName().toString() + ".tmp");
        java.util.Map<Integer, Integer> mapping = new java.util.HashMap<>();
        try (RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rw")) {
            raf.setLength(0);
            int newNo = 0;
            for (int oldNo : livePageNos) {
                // 这里的 id 必须用本 store 的 collectionId，不能拿 collectionName 再 hash 一次：
                // 3 参构造器（StorageEngine / HybridSnapshot 走的就是那条）里 name 只是日志标签，
                // "cid=5".hashCode() != 5，旧写法在第一页就抛 collectionId mismatch。
                Page p = read(new PageId(collectionId, PageType.DATA, oldNo));
                // 页类型保持原样（INDEX/BLOOM 不会被改成 DATA），只有 pageNo 紧凑了
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
     * <p>
     * 末尾凑不满一页的残页<b>只作废它自己</b>：旧写法是 {@code len % pageSize != 0} 就整个
     * 返回空集 —— 一次没写完的追加会把成百上千页好数据判成"不存在"，再被 {@link #compact()}
     * 拿空文件替换掉。
     */
    public Set<Integer> listPageNos() throws IOException {
        Path path = resolveFile();
        if (!Files.exists(path)) return new HashSet<>();
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long len = raf.length();
            if (len == 0) return new HashSet<>();
            int count = (int) (len / pageSize);
            if (len % pageSize != 0) {
                LOG.warn("PageStore 尾部非整页: collection={}, size={}B, pageSize={}B —— 尾部 {}B 按残页忽略",
                        collectionName, len, pageSize, len % pageSize);
            }
            Set<Integer> set = new HashSet<>(count);
            byte[] headerBuf = new byte[Page.MAGIC.length];
            for (int i = 0; i < count; i++) {
                raf.seek((long) i * pageSize);
                // 短读要补齐再判：一次 read 少几个字节不等于"这页没写过"
                if (!readHeader(raf, headerBuf)) continue;
                set.add(i);
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
        Path path = resolveFile();
        Files.deleteIfExists(path);
        LOG.info("PageStore deleted: {}", path);
    }

    /** 当前 PageStore 的磁盘占用（字节）。 */
    public long diskSize() throws IOException {
        Path path = resolveFile();
        if (!Files.exists(path)) return 0;
        return Files.size(path);
    }
}
