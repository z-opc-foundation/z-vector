package com.zifang.z.vector.storage.wal;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.VectorPoint;
import com.zifang.z.vector.storage.PersistentVectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WAL 落盘模式的尺 —— 钉住「默认同步（ack 即持久，RPO = 0）」与「async 是显式选项」这两件事。
 *
 * <p>为什么不只看文档：这一族缺陷的原型是「属性有值、日志有值、行为没有值」。
 * 默认档位从异步翻成同步是一次<b>持久性语义</b>的变更，尺必须从外部事实判
 * （模式值 + 重启后能不能读回来），不看 javadoc。
 */
class WalModeTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultStoreIsSync() {
        try (PersistentVectorStore store = new PersistentVectorStore(tempDir.toString())) {
            assertEquals(WalMode.SYNC, store.walMode(),
                    "默认档位必须是 SYNC（ack 即持久、RPO=0）");
        }
    }

    /** async 必须能显式选出来 —— 这是「性能换 RPO」的那条路，不能被删掉。 */
    @Test
    void asyncModeIsSelectable() {
        try (PersistentVectorStore store =
                     new PersistentVectorStore(tempDir.toString(), 1000, true, WalMode.ASYNC)) {
            assertEquals(WalMode.ASYNC, store.walMode());
        }
    }

    /**
     * 同步档的实打实后果：{@code upsert()} 返回后<b>不 close</b>，另一个 store 直接重开同一个目录，
     * 数据必须已经读得到（这正是「ack 即持久」在同机上的可观测形式）。
     */
    @Test
    void syncModeIsVisibleToAReopenedStore() {
        Path dir = tempDir;
        PersistentVectorStore writer = new PersistentVectorStore(dir.toString(), 1000, true);
        try {
            writer.createCollection("docs", 4, DistanceMetric.COSINE);
            for (int i = 0; i < 10; i++) {
                writer.upsert("docs", new VectorPoint("d" + i, new float[]{0.1f, 0.2f, 0.3f, 0.4f}));
            }
            // 不 close（不写快照、不 flush）：读得到才说明那 10 条真的进了 WAL 且已 fsync。
            try (PersistentVectorStore reader = new PersistentVectorStore(dir.toString(), 1000, true)) {
                assertEquals(10L, reader.getPointCount("docs"),
                        "同步档下 upsert 返回的 10 条在重开后读不到 ⇒ 返回即持久这条契约没兑现");
            }
        } finally {
            writer.close();
        }
    }

    /** 配置矛盾必须早失败：没有引擎就没有异步队列可入。 */
    @Test
    void asyncWithoutEngineFailsFast() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new PersistentVectorStore(tempDir.toString(), 1000, false, WalMode.ASYNC));
        assertTrue(String.valueOf(e.getMessage()).contains("ASYNC"),
                "报错没点名是哪个模式： " + e.getMessage());
    }

    /** 拼错的取值必须抛异常，不静默回落成默认。 */
    @Test
    void bogusModeIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WalMode.from("asnyc"));
        String msg = String.valueOf(e.getMessage());
        assertTrue(msg.contains("sync") && msg.contains("async"),
                "报错没列出可选取值： " + msg);
        assertEquals(WalMode.SYNC, WalMode.from("SYNC"));
        assertEquals(WalMode.ASYNC, WalMode.from(" async "));
    }

    /**
     * 进程级默认的优先级：系统属性 &gt; 环境变量 &gt; SYNC。
     * <p>环境变量在测试进程里改不动（不能 {@code setenv}），所以这一条只钉系统属性这条腿；
     * 环境变量那条腿由 {@link WalMode#resolveDefault()} 的实现与 README 的旋钮表兜着。
     */
    @Test
    void resolveDefaultPrefersSystemProperty() {
        String key = WalMode.MODE_PROPERTY;
        String old = System.getProperty(key);
        try {
            System.setProperty(key, "async");
            assertEquals(WalMode.ASYNC, WalMode.resolveDefault());
            System.setProperty(key, "sync");
            assertEquals(WalMode.SYNC, WalMode.resolveDefault());
            System.clearProperty(key);
            assertNotNull(WalMode.resolveDefault(), "没有配置时也必须有确定的默认（SYNC）");
            assertEquals(WalMode.SYNC, WalMode.resolveDefault());
        } finally {
            if (old == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, old);
            }
        }
    }
}
