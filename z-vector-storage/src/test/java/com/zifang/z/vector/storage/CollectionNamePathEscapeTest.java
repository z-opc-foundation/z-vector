package com.zifang.z.vector.storage;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.VectorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集合名直接参与磁盘路径拼接这件事的<b>边界钉</b>。
 *
 * <p>{@code PersistentVectorStore.hnswFile} 是 {@code Paths.get(dataDir, "hnsw_" + name + ".bin")}，
 * 而 {@code createCollection} 只查重名、{@code deleteCollection} 无条件执行
 * {@code Files.deleteIfExists(hnswFile(name))}——链路上没有任何字符校验，
 * 集合名又是从 {@code QdrantRestServer}（无鉴权）与 {@code VectorServiceGrpc} 客户端原样透传进来的。</p>
 *
 * <p><b>实测结论：当前删不到 dataDir 之外，但挡住它的是偶然。</b>
 * 那个 {@code hnsw_} 前缀把第一段粘成了 {@code hnsw_..}——一个<b>普通目录名</b>而不是 {@code ..}；
 * 而 OS 逐段解析路径时必须先进入该目录才谈得上处理后面的 {@code ..}，目录不存在 ⇒ ENOENT ⇒
 * {@code deleteIfExists} 安静地什么都没做。全仓也没有任何 API 能在 dataDir 下造出那个目录
 * （{@code PageStore} 的文件名用的是 {@code collectionName.hashCode()} 这个 int，
 * {@code WalFile} 只对 dataDir 本身建目录）。</p>
 *
 * <p>所以这两道是<b>护栏</b>，不是"已修好的证明"。一旦有人把前缀去掉、改成先
 * {@code normalize()} 再拼、或引入能按名字建目录的路径，它们就会红。</p>
 */
class CollectionNamePathEscapeTest {

    /** 对照组：正常名字删集合，目标在 dataDir 之内。 */
    @Test
    @DisplayName("对照组：正常集合名只影响 dataDir 内的文件")
    void normalNameTouchesOnlyDataDir(@TempDir Path tmp) throws IOException {
        Path dataDir = Files.createDirectories(tmp.resolve("data"));
        VectorStore store = new PersistentVectorStore(dataDir.toString());
        try {
            store.createCollection("docs", 4, DistanceMetric.COSINE);
            assertTrue(store.deleteCollection("docs"), "前提：集合应当删得掉");
        } finally {
            store.close();
        }
        assertTrue(Files.isDirectory(dataDir), "前提：dataDir 本身不该被删掉");
    }

    /**
     * 集合名里的 {@code ..} 不会把删除操作带出 dataDir——靠的是 {@code hnsw_} 前缀
     * 粘住了第一段，而不是任何显式校验。
     */
    @Test
    @DisplayName("护栏：集合名含 .. 时不得删到 dataDir 之外")
    void dotDotInCollectionNameMustNotEscapeDataDir(@TempDir Path tmp) throws IOException {
        Path dataDir = Files.createDirectories(tmp.resolve("data"));
        Path victim = tmp.resolve("victim.bin");
        Files.write(victim, "important data".getBytes(StandardCharsets.UTF_8));
        assertTrue(Files.exists(victim), "前提：受害文件应当先存在");

        // 拼出来是 <dataDir>/hnsw_../victim.bin，段依次是
        //   hnsw_..  ← 普通目录名，不是 ..，它不存在 ⇒ 整条路径 ENOENT
        //   ..       ← 走不到
        //   victim.bin
        //
        // 这里必须是**恰好一跳**：<dataDir>/../victim.bin 正好落在 dataDir 的父目录，
        // 也就是受害文件所在处。层数给多了的话，摘掉前缀的变异体删的是别处，
        // 这道测试就会假绿（第一版写成 ../../../victim 就是这么假绿的）。
        String evil = "../victim";
        VectorStore store = new PersistentVectorStore(dataDir.toString());
        try {
            store.createCollection(evil, 4, DistanceMetric.COSINE);
            assertTrue(store.deleteCollection(evil), "前提：集合应当删得掉");
        } finally {
            store.close();
        }

        assertTrue(Files.exists(victim),
                "dataDir 之外的 victim.bin 被删掉了——集合名里的 .. 参与了路径拼接且越了界");
    }
}
