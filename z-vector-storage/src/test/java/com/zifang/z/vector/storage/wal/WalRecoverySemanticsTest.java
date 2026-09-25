package com.zifang.z.vector.storage.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WAL 恢复语义测试 —— 钉住两件事：
 * <ol>
 *   <li><b>重放顺序</b>：跨 ≥3 个段时必须严格"先写先重放"。
 *       {@code rotate()} 生成的 wal_1 是<b>最旧</b>段，倒着遍历会让旧 upsert 覆盖新 upsert。</li>
 *   <li><b>CRC 真的生效</b>：单字节损坏必须被拒绝，而不是被当成合法记录恢复进内存。</li>
 * </ol>
 * <p>
 * 这些用例配的是 {@link WalFileRotationTest#replayAcrossSegmentsPreservesOrder} 的盲区：
 * 那个用例只比 {@code System.currentTimeMillis()}，同毫秒写入让它对任意顺序都成立，
 * 因此代码写反时它照样绿。这里改用记录自带的 seq 做全序断言。
 */
class WalRecoverySemanticsTest {

    @TempDir
    Path tmpDir;

    /** 从 payload `{"seq":N}` 里取 N。 */
    private static long seqOf(WalRecord r) {
        String p = r.getPayload();
        int i = p.indexOf("\"seq\":");
        if (i < 0) return Long.MIN_VALUE;
        String rest = p.substring(i + 6);
        int end = 0;
        while (end < rest.length() && (Character.isDigit(rest.charAt(end)) || rest.charAt(end) == '-')) end++;
        return Long.parseLong(rest.substring(0, end));
    }

    private static List<Long> seqs(List<WalRecord> records) {
        List<Long> out = new ArrayList<>(records.size());
        for (WalRecord r : records) out.add(seqOf(r));
        return out;
    }

    // ==================== 1. 重放顺序 ====================

    @Test
    void replaysOldestFirstAcrossAtLeastThreeSegments() throws IOException {
        // 段阈值压到很小，制造 >=3 段；seq 全局递增，任何乱序都能被下面的一次比较抓到
        WalFile wal = new WalFile(tmpDir.toString(), 300);
        int total = 0;
        try {
            for (int seq = 0; seq < 120; seq++) {
                wal.append(WalRecord.checkpoint(seq));
                total++;
            }
            assertTrue(wal.segmentCount() >= 3,
                    "本用例的前提是 >=3 段（2 段时倒序与正序结果相同，抓不到 bug），实际 "
                            + wal.segmentCount());
        } finally {
            wal.close();
        }

        try (WalFile reopened = new WalFile(tmpDir.toString(), 300)) {
            List<WalRecord> all = reopened.readAll();
            assertEquals(total, all.size(), "所有段的记录都要被重放");
            List<Long> seqs = seqs(all);
            for (int i = 0; i < seqs.size(); i++) {
                assertEquals(i, seqs.get(i).intValue(),
                        "重放顺序必须等于写入顺序；位置 " + i + " 实际 seq=" + seqs.get(i)
                                + "，整串=" + seqs);
            }
        }
    }

    @Test
    void laterUpsertWinsAfterRecovery() throws IOException {
        // 端到端版：同一个 id 先写 v=1 再写 v=2，跨段重启后必须是 2。
        // 这正是"旧覆盖新"在生产里造成的实际后果。
        try (WalFile wal = new WalFile(tmpDir.toString(), 1024 * 1024)) {
            wal.append(new WalRecord(WalOpType.UPSERT_POINT, "c", "{\"id\":\"k\",\"v\":1}"));
            wal.rotate();
            wal.rotate();
            wal.append(new WalRecord(WalOpType.UPSERT_POINT, "c", "{\"id\":\"k\",\"v\":2}"));
        }
        try (WalFile reopened = new WalFile(tmpDir.toString(), 1024 * 1024)) {
            List<WalRecord> all = reopened.readAll();
            assertEquals(2, all.size());
            assertEquals("{\"id\":\"k\",\"v\":2}", all.get(all.size() - 1).getPayload(),
                    "最后一条重放的应是最后一次写入");
        }
    }

    // ==================== 2. CRC 校验 ====================

    @Test
    void intactWalRoundTripsAfterCrcEnforcement() throws IOException {
        // 反向对照：加了 CRC 校验之后，完好文件必须一条都不能少。
        // 少了就是我自己把量具弄坏了，而不是数据的问题。
        try (WalFile wal = new WalFile(tmpDir.toString(), 1024 * 1024)) {
            for (int i = 0; i < 25; i++) wal.append(WalRecord.checkpoint(i));
        }
        try (WalFile reopened = new WalFile(tmpDir.toString(), 1024 * 1024)) {
            assertEquals(25, reopened.readAll().size());
        }
    }

    @Test
    void singleBitFlipInsidePayloadIsRejectedNotSilentlyAccepted() throws IOException {
        Path walPath = tmpDir.resolve("wal.log");
        try (WalFile wal = new WalFile(tmpDir.toString(), 1024 * 1024)) {
            wal.append(WalRecord.checkpoint(0));
            wal.append(WalRecord.checkpoint(1));   // 破坏这条
            wal.append(WalRecord.checkpoint(2));
        }

        byte[] bytes = Files.readAllBytes(walPath);
        String marker = "\"seq\":1}";
        int at = indexOf(bytes, marker.getBytes(StandardCharsets.US_ASCII));
        assertTrue(at > 0, "marker 必须存在才能构造损坏");
        bytes[at + 1] ^= 0x01;                      // 改 's' 一个 bit：magic/长度字段都没动，只有 CRC 能抓到
        Files.write(walPath, bytes);

        try (WalFile reopened = new WalFile(tmpDir.toString(), 1024 * 1024)) {
            List<WalRecord> all = reopened.readAll();
            List<Long> seqs = seqs(all);
            assertFalse(seqs.contains(1L),
                    "被改写的那条必须被 CRC 拒绝，实际重放出 " + seqs);
            assertEquals(1, all.size(),
                    "CRC 之后的段无法对齐重同步，读取应在损坏处停止；实际 " + seqs);
            assertEquals(0L, seqs.get(0).longValue(), "损坏之前的记录必须完好重放");
        }
    }

    @Test
    void corruptedLengthFieldFailsRecoveryAsIoExceptionNotRuntime() throws IOException {
        // 旧实现读到 payloadLen<0 会 new byte[负数] => NegativeArraySizeException，
        // 它是 RuntimeException，parseRecords 的 catch(IOException) 接不住，
        // 整个 recover() 直接崩 —— 一个坏字节从"丢尾部"升级成"启不来"。
        Path walPath = tmpDir.resolve("wal.log");
        try (WalFile wal = new WalFile(tmpDir.toString(), 1024 * 1024)) {
            wal.append(WalRecord.checkpoint(0));
        }
        byte[] bytes = Files.readAllBytes(walPath);
        // 布局: magic[0..3] op[4] ts[5..12] nameLen[13..14] name[15..] payloadLen[...]
        int nameLen = ((bytes[13] & 0xFF) << 8) | (bytes[14] & 0xFF);
        int payloadLenOff = 15 + nameLen;
        assertTrue(payloadLenOff + 4 <= bytes.length,
                "offset 算错了：nameLen=" + nameLen + " len=" + bytes.length);
        bytes[payloadLenOff] = (byte) 0xFF;         // payloadLen -> 负数
        bytes[payloadLenOff + 1] = (byte) 0xFF;
        Files.write(walPath, bytes);

        try (WalFile reopened = new WalFile(tmpDir.toString(), 1024 * 1024)) {
            List<WalRecord> all = reopened.readAll();   // 不得抛异常
            assertTrue(all.isEmpty(), "非法长度记录不得被接受: " + seqs(all));
        }
    }

    @Test
    void truncatedTailIsTolerated() throws IOException {
        // 掉电最常见的形态：最后一条只写了一半，文件被截断。
        Path walPath = tmpDir.resolve("wal.log");
        try (WalFile wal = new WalFile(tmpDir.toString(), 1024 * 1024)) {
            wal.append(WalRecord.checkpoint(0));
            wal.append(WalRecord.checkpoint(1));
        }
        byte[] bytes = Files.readAllBytes(walPath);
        byte[] cut = new byte[bytes.length - 5];
        System.arraycopy(bytes, 0, cut, 0, cut.length);
        Files.write(walPath, cut);

        try (WalFile reopened = new WalFile(tmpDir.toString(), 1024 * 1024)) {
            List<WalRecord> all = reopened.readAll();
            assertEquals(1, all.size(), "截断尾巴应被丢弃，前一条完整记录必须保住: " + seqs(all));
            assertEquals(0L, seqOf(all.get(0)));
        }
    }

    // ==================== 工具 ====================

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
