package com.zifang.z.vector.storage.page;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PageStore 的"集合之间不许互相踩"契约。
 *
 * <p>改动前的三条事实（都能从旧字节码里逐字读出来，也都有探针复算过）：
 * <ol>
 *   <li>文件名是 {@code pages_<|collectionId|>.pgs}。{@code collectionId} 是集合名的
 *       {@code hashCode()}，取绝对值把 {@code h} 与 {@code -h} 折成同一个文件；词典里这种
 *       名字成对存在（本测试钉住 "Gretel"/"nudeness" 这一对）。</li>
 *   <li>{@code read(PageId)} 从不核对页头里的 collectionId/pageNo，而 {@code Page.deserialize}
 *       只按字节重建 —— 撞车的两个集合互相对方的数据"读成功"，CRC 合法、一个异常都不抛。</li>
 *   <li>{@code listPageNos()} 在 {@code len % pageSize != 0} 时返回<b>空集</b>，紧接着
 *       {@code compact()} 无条件拿临时文件 {@code ATOMIC_MOVE} 盖掉原文件 —— 文件末尾多出几个
 *       字节就等于全库蒸发。</li>
 * </ol>
 *
 * <p>每支测试都带<b>阳性对照</b>：判"读不出来/不属于我"的那种断言，必须先证明同一批字节
 * 在正确的所有者手里是能被正常读出来的，否则它可能只是在读一个空目录。
 */
class PageStoreCollectionIsolationTest {

    @TempDir
    Path tmpDir;

    private static final int PAGE = Page.DEFAULT_PAGE_SIZE;

    private static byte[] payload(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private long filesInDir() throws IOException {
        try (Stream<Path> s = Files.list(tmpDir)) {
            return s.count();
        }
    }

    private static void writePage(PageStore store, int collectionId, PageType type,
                                  int pageNo, String content) throws IOException {
        store.write(new Page(new PageId(collectionId, type, pageNo), payload(content)));
    }

    // ==================== G1：± 一对名字不能再共用一个文件 ====================

    @Test
    void plusMinusHashNamesGetTheirOwnFiles() throws IOException {
        String nameA = "Gretel";
        String nameB = "nudeness";
        // 阳性对照：这一对名字必须真的是"互为相反数"，否则本测试就只是在测两个普通集合
        assertEquals(-nameA.hashCode(), nameB.hashCode(),
                "名字对不再满足 h == -h' —— 换一对，别把这条尺改成空跑");
        assertTrue(nameA.hashCode() > 0);
        assertEquals("pages_" + Math.abs(nameA.hashCode()) + ".pgs",
                "pages_" + Math.abs(nameB.hashCode()) + ".pgs",
                "旧的绝对值命名法确实会让这两个集合同名 —— 这一条是探针读出来的，不是猜的");

        PageStore a = new PageStore(tmpDir.toString(), nameA);
        PageStore b = new PageStore(tmpDir.toString(), nameB);
        assertNotEquals(a.file(), b.file(), "两个集合被分配到了同一个文件");

        for (int i = 0; i < 3; i++) {
            writePage(a, nameA.hashCode(), PageType.DATA, i, "A-page-" + i);
        }
        // B 还没写之前，它不该看见 A 的页
        assertEquals(0, b.listPageNos().size(), "B 的 listPageNos 读到了 A 的页 ⇒ 还在共用文件");

        for (int i = 0; i < 3; i++) {
            writePage(b, nameB.hashCode(), PageType.DATA, i, "B-page-" + i);
        }

        for (int i = 0; i < 3; i++) {
            Page pa = a.read(new PageId(nameA.hashCode(), PageType.DATA, i));
            Page pb = b.read(new PageId(nameB.hashCode(), PageType.DATA, i));
            assertEquals("A-page-" + i, text(pa.payload()), "A 的第 " + i + " 页被覆盖了");
            assertEquals("B-page-" + i, text(pb.payload()), "B 的第 " + i + " 页不对");
            assertEquals(nameA.hashCode(), pa.id().collectionId());
            assertEquals(nameB.hashCode(), pb.id().collectionId());
        }
        assertEquals(2, filesInDir(), "磁盘上应该正好两个 .pgs 文件");
    }

    // ==================== G2：读回来的必须是请求的那一页（传统读路径） ====================

    @Test
    void readRefusesAPageFromAnotherCollection() throws IOException {
        final int victim = 77;
        final int intruder = 78;
        PageStore victimStore = new PageStore(tmpDir.toString(), victim, "cid=" + victim);
        PageStore intruderStore = new PageStore(tmpDir.toString(), intruder, "cid=" + intruder);
        assertNull(victimStore.mmapReader(), "前置：这一支必须走 RandomAccessFile 分支");

        // victim 先有一页自己的数据（否则 read 会先以"文件不存在"失败，那就白测了）
        writePage(victimStore, victim, PageType.DATA, 1, "victim-page-1");
        assertEquals("victim-page-1",
                text(victimStore.read(new PageId(victim, PageType.DATA, 1)).payload()));

        // 把 intruder 的第 0 页原封不动搬进 victim 的文件第 0 页
        writePage(intruderStore, intruder, PageType.DATA, 0, "intruder-page-0");
        byte[] foreignPage = Files.readAllBytes(intruderStore.file());
        assertEquals(PAGE, foreignPage.length);
        try (RandomAccessFile raf = new RandomAccessFile(victimStore.file().toFile(), "rw")) {
            raf.seek(0);
            raf.write(foreignPage);
        }

        IOException thrown = assertThrows(IOException.class,
                () -> victimStore.read(new PageId(victim, PageType.DATA, 0)),
                "页头写着别人的 collectionId，read 却成功返回了");
        assertTrue(thrown.getMessage().contains("identity mismatch"),
                "抛的是别的原因，不是身份核对：" + thrown.getMessage());

        // 阳性对照：同一批字节在 intruder 那里是合法页 —— victim 失败只能是因为"不是它的"
        Page stillValidForOwner = intruderStore.read(new PageId(intruder, PageType.DATA, 0));
        assertEquals("intruder-page-0", text(stillValidForOwner.payload()));
        // victim 自己的第 1 页不受影响
        assertEquals("victim-page-1",
                text(victimStore.read(new PageId(victim, PageType.DATA, 1)).payload()));
    }

    /** mmap 路径也必须核对身份：两条读路径共用一个守卫，摘掉守卫两条都要红。 */
    @Test
    void mmapReadAlsoRefusesForeignPage() throws IOException {
        final int victim = 91;
        final int intruder = 92;
        PageStore victimStore = new PageStore(tmpDir.toString(), victim, "cid=" + victim)
                .useMmap(true);
        PageStore intruderStore = new PageStore(tmpDir.toString(), intruder, "cid=" + intruder);

        writePage(victimStore, victim, PageType.DATA, 1, "victim-page-1");
        writePage(intruderStore, intruder, PageType.DATA, 0, "intruder-page-0");
        try (RandomAccessFile raf = new RandomAccessFile(victimStore.file().toFile(), "rw")) {
            raf.seek(0);
            raf.write(Files.readAllBytes(intruderStore.file()));
        }

        IOException thrown = assertThrows(IOException.class,
                () -> victimStore.read(new PageId(victim, PageType.DATA, 0)));
        assertTrue(thrown.getMessage().contains("identity mismatch"),
                "抛的是别的原因，不是身份核对：" + thrown.getMessage());
        // 阳性对照：这次 read 确实走了 mmap 分支，而不是被哪个前置条件绕回了传统路径
        assertNotNull(victimStore.mmapReader(), "read 没有建立 mmap 视图 ⇒ 这条尺测的是另一条分支");

        // 同一批字节在 intruder 手里读得出来；victim 自己的第 1 页也不受影响
        assertEquals("intruder-page-0",
                text(intruderStore.read(new PageId(intruder, PageType.DATA, 0)).payload()));
        assertEquals("victim-page-1",
                text(victimStore.read(new PageId(victim, PageType.DATA, 1)).payload()));
    }

    // ==================== G3：1.0.3 的旧目录（绝对值名）不能升级即丢 ====================

    @Test
    void legacyAbsNamedDirectoryIsStillReadable() throws IOException {
        final int cid = -12345;
        PageStore first = new PageStore(tmpDir.toString(), cid, "legacy-owner");
        writePage(first, cid, PageType.DATA, 0, "legacy-page-0");
        Path fresh = first.file();
        assertEquals("pages_" + cid + ".pgs", fresh.getFileName().toString(),
                "前置：新命名法应当原样带符号");

        // 模拟 1.0.3 的落盘形状：负 hash 集合的文件名是绝对值版
        Path legacy = tmpDir.resolve("pages_" + Math.abs(cid) + ".pgs");
        assertFalse(Files.exists(legacy), "前置：新名与旧名撞在了一起");
        Files.move(fresh, legacy);
        assertFalse(Files.exists(fresh));

        PageStore reopened = new PageStore(tmpDir.toString(), cid, "legacy-owner");
        // file() 是纯命名函数（正名）；"延用旧文件"要这样判：旧文件还在、正名没被凭空造出来
        assertTrue(Files.exists(legacy), "前置：旧文件不在了");
        Set<Integer> listed = reopened.listPageNos();
        assertEquals(1, listed.size(), "旧目录里的页没被认出来（延用失败 ⇒ 升级即丢数据）");
        assertTrue(listed.contains(0));
        assertEquals("legacy-page-0", text(reopened.read(new PageId(cid, PageType.DATA, 0)).payload()));
        assertTrue(reopened.diskSize() > 0, "diskSize 也没跟着延用旧文件");

        // 再写一页，必须落在同一个旧文件里（不改名、不复制）
        writePage(reopened, cid, PageType.DATA, 1, "legacy-page-1");
        assertEquals(1, filesInDir(), "延用旧文件时不该再多长出一个新文件");
        assertFalse(Files.exists(fresh), "延用旧文件时不该另建正名文件");
        assertEquals(PAGE * 2L, Files.size(legacy));
        assertEquals("legacy-page-1", text(reopened.read(new PageId(cid, PageType.DATA, 1)).payload()));
    }

    /**
     * 旧名字的文件<b>未必</b>属于负 hash 集合：它可能正是正数兄弟自己的数据。
     * 这时负 hash 集合必须另起正名文件，而不是把兄弟的目录认领过来。
     */
    @Test
    void doesNotAdoptALegacyFileOwnedByThePositiveSibling() throws IOException {
        final int positive = 12345;
        PageStore sibling = new PageStore(tmpDir.toString(), positive, "sibling");
        writePage(sibling, positive, PageType.DATA, 0, "sibling-page-0");
        Path sharedName = sibling.file();
        assertEquals("pages_" + positive + ".pgs", sharedName.getFileName().toString());

        PageStore adopted = new PageStore(tmpDir.toString(), -positive, "would-be-adopter");
        assertEquals(sharedName, tmpDir.resolve("pages_" + Math.abs(-positive) + ".pgs"),
                "前置：旧名正是兄弟的文件，否则这条尺没有猎物");
        IOException thrown = assertThrows(IOException.class,
                () -> adopted.read(new PageId(-positive, PageType.DATA, 0)),
                "把兄弟的旧文件认领成了自己的，于是读回了别人的页");
        assertTrue(thrown.getMessage().contains("not found"),
                "抛的不是“文件还没建”，而是别的原因：" + thrown.getMessage());
        assertFalse(Files.exists(sharedName.resolveSibling("pages_" + (-positive) + ".pgs")));

        // 阳性对照：兄弟的数据完好，且它自己读这一页一直是合法的
        assertEquals("sibling-page-0",
                text(sibling.read(new PageId(positive, PageType.DATA, 0)).payload()));

        // 认领失败之后写自己的页 ⇒ 落在正名文件里，兄弟的文件一字节不动
        byte[] siblingBefore = Files.readAllBytes(sharedName);
        writePage(adopted, -positive, PageType.DATA, 0, "adopter-page-0");
        assertTrue(Arrays.equals(siblingBefore, Files.readAllBytes(sharedName)),
                "负 hash 集合写自己的页，却动了兄弟的文件");
        assertEquals("adopter-page-0",
                text(adopted.read(new PageId(-positive, PageType.DATA, 0)).payload()));
        assertEquals(2, filesInDir());
    }

    /**
     * 反方向：正 hash 集合的 {@code file()} 恰好是旧格式给负 hash 集合用的名字。
     * 旧目录里的页头写着别人 ⇒ 必须<b>响亮地</b>拒绝，而不是像改动前那样把邻居的数据当自己的读回来。
     */
    @Test
    void positiveSiblingOfALegacyFileIsRefusedByTheIdentityCheck() throws IOException {
        final int negative = -12345;
        PageStore owner = new PageStore(tmpDir.toString(), negative, "legacy-owner");
        writePage(owner, negative, PageType.DATA, 0, "owner-page-0");
        Path legacy = tmpDir.resolve("pages_" + Math.abs(negative) + ".pgs");
        Files.move(owner.file(), legacy);

        PageStore sibling = new PageStore(tmpDir.toString(), -negative, "sibling");
        assertEquals(legacy, sibling.file(), "前置：兄弟集合的正名就是那个旧文件");
        IOException thrown = assertThrows(IOException.class,
                () -> sibling.read(new PageId(-negative, PageType.DATA, 0)),
                "兄弟集合把别人的页当自己的读回来了");
        assertTrue(thrown.getMessage().contains("identity mismatch"), thrown.getMessage());

        // 阳性对照：同一批字节在 owner 手里是合法页 ⇒ 拒绝只能是因为"不是它的"
        PageStore reopenedOwner = new PageStore(tmpDir.toString(), negative, "legacy-owner");
        assertEquals("owner-page-0",
                text(reopenedOwner.read(new PageId(negative, PageType.DATA, 0)).payload()));
    }

    // ==================== G4：残页只作废自己 ====================

    @Test
    void tornTailOnlyForfeitsItself() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), 500, "torn");
        for (int i = 0; i < 3; i++) {
            writePage(store, 500, PageType.DATA, i, "page-" + i);
        }
        long aligned = store.diskSize();
        assertEquals(3 * PAGE, aligned, "前置：应该正好三页");

        try (RandomAccessFile raf = new RandomAccessFile(store.file().toFile(), "rw")) {
            raf.setLength(aligned + 7);  // 崩溃时没写完的尾巴
        }

        Set<Integer> listed = store.listPageNos();
        assertEquals(3, listed.size(), "尾部多了 7 个字节，整库就被判成空的了：" + listed);
        for (int i = 0; i < 3; i++) {
            assertEquals("page-" + i, text(store.read(new PageId(500, PageType.DATA, i)).payload()));
        }

        Map<Integer, Integer> mapping = store.compact();
        assertEquals(3, mapping.size(), "compact 之后数据应该还在：" + mapping);
        assertEquals(3 * PAGE, store.diskSize(), "compact 没把残页截掉");
        for (int i = 0; i < 3; i++) {
            assertEquals("page-" + i, text(store.read(new PageId(500, PageType.DATA, i)).payload()));
        }
    }

    // ==================== G5：读不出页时，compact 无权替换文件 ====================

    @Test
    void compactRefusesToWipeAFileItCannotRead() throws IOException {
        PageStore store = new PageStore(tmpDir.toString(), 600, "junk");
        byte[] junk = new byte[2 * PAGE];
        Arrays.fill(junk, (byte) 0x5A);
        Files.write(store.file(), junk);   // 长度对齐、但没有一页 magic

        assertTrue(store.listPageNos().isEmpty(), "前置：这批字节本就读不出任何页");
        assertEquals(0, store.compact().size());

        assertTrue(Files.exists(store.file()), "compact 把读不出内容的文件删掉了");
        assertEquals(2 * PAGE, Files.size(store.file()));
        assertTrue(Arrays.equals(junk, Files.readAllBytes(store.file())),
                "compact 改写了它没读到的字节");

        // 阳性对照：真的空目录（没有文件）compact 不报错、也不凭空造文件
        PageStore untouched = new PageStore(tmpDir.toString(), 601, "empty-dir");
        assertEquals(0, untouched.compact().size());
        assertFalse(Files.exists(untouched.file()));
    }

    // ==================== G6：外部指定 collectionId 的集合也得能压缩 ====================

    @Test
    void compactWorksWithExternallyAssignedCollectionId() throws IOException {
        final int cid = 4242;
        // StorageEngine 的形状：name 只是日志标签，id 由调用方给
        assertNotEquals(cid, ("cid=" + cid).hashCode(),
                "前置：名字 hash 与指定 id 必须不同，否则这条尺测不到那个 bug");
        PageStore store = new PageStore(tmpDir.toString(), cid, "cid=" + cid);
        writePage(store, cid, PageType.DATA, 0, "keep-0");
        writePage(store, cid, PageType.DATA, 1, "dropped-1");
        writePage(store, cid, PageType.DATA, 2, "keep-2");
        store.freePage(1);

        Map<Integer, Integer> mapping;
        try {
            mapping = store.compact();
        } catch (IllegalArgumentException e) {
            fail("compact 用 collectionName 重新 hash 了 id（旧写法）：" + e.getMessage());
            return;
        }
        assertEquals(2, mapping.size(), String.valueOf(mapping));
        assertEquals(Integer.valueOf(0), mapping.get(0));
        assertEquals(Integer.valueOf(1), mapping.get(2));
        assertEquals("keep-0", text(store.read(new PageId(cid, PageType.DATA, 0)).payload()));
        assertEquals("keep-2", text(store.read(new PageId(cid, PageType.DATA, 1)).payload()));
    }

    // ==================== G7：压缩只重编号，不改页类型 ====================

    @Test
    void compactKeepsPageTypeAndOnlyRenumbers() throws IOException {
        final int cid = 700;
        PageStore store = new PageStore(tmpDir.toString(), cid, "cid=" + cid);
        writePage(store, cid, PageType.META, 0, "meta-page");
        writePage(store, cid, PageType.INDEX, 1, "index-page");
        writePage(store, cid, PageType.BLOOM, 2, "bloom-page");
        store.freePage(1);

        Map<Integer, Integer> mapping = store.compact();
        assertEquals(2, mapping.size(), String.valueOf(mapping));

        // 故意拿 DATA 去请求：read 核对的是"谁的哪一页"，不是"什么类型"，所以能读到，
        // 而读回来的类型必须还是原来的（compact 不许把 META/BLOOM 抹平成 DATA）
        Page first = store.read(new PageId(cid, PageType.DATA, 0));
        assertEquals(PageType.META, first.id().type());
        assertEquals("meta-page", text(first.payload()));
        Page second = store.read(new PageId(cid, PageType.DATA, 1));
        assertEquals(PageType.BLOOM, second.id().type());
        assertEquals("bloom-page", text(second.payload()));
    }
}
