package com.zifang.z.vector.storage.crash;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * WAL 字节格式的<b>测试侧</b>解析器 —— 只为「造出确定性的半截记录」和「数清盘上有几条记录」。
 *
 * <p>为什么要自己解析一遍，而不是直接调 {@code WalFile.readAll()}：
 * {@code readAll()} 会把尾部半截记录<b>丢掉</b>，读回来的是「好的那些」——
 * 而本套件要做的恰恰是<b>亲手</b>把文件截断成半截，好让恢复流程去面对它。
 * 要截断在记录中间，就得先知道记录边界在哪 ⇒ 需要一个能报出「每条记录占了哪几个字节」的解析器。
 *
 * <p>格式（与 {@code WalFile#serialize} 逐字对齐）：
 * <pre>
 *   [magic 4B = "WAL1"][op 1B][timestamp 8B][nameLen 2B][name][payloadLen 4B][payload][crc32 4B]
 * </pre>
 *
 * <p><b>容错语义必须与引擎一致</b>：遇到半截记录就停（不抛异常），
 * 否则测试侧算出来的「有效记录数」和引擎恢复出来的条数会对不上，
 * 断言就会指向一个不存在的问题。
 */
final class WalByteFormat {

    /** WAL magic（与 {@code WalRecord.MAGIC} 一致）。 */
    static final String MAGIC = "WAL1";

    /** op = UPSERT_POINT（与 {@code WalOpType} 一致）。 */
    static final byte OP_UPSERT_POINT = 3;

    /** op = CREATE_COLLECTION。 */
    static final byte OP_CREATE_COLLECTION = 1;

    /** op = CHECKPOINT。 */
    static final byte OP_CHECKPOINT = 7;

    private WalByteFormat() {
    }

    /** 一条记录在文件里的字节区间。 */
    static final class Rec {
        final int start;
        final int end;
        final byte op;

        Rec(int start, int end, byte op) {
            this.start = start;
            this.end = end;
            this.op = op;
        }

        int length() {
            return end - start;
        }
    }

    /**
     * 逐条扫描，返回「引擎能认出来的」记录区间。
     *
     * @return 记录列表；遇到半截/损坏记录即停止（与 {@code WalFile#parseRecords} 同语义）
     */
    static List<Rec> scan(byte[] bytes) {
        List<Rec> out = new ArrayList<Rec>();
        int pos = 0;
        while (pos < bytes.length) {
            int end = recordEnd(bytes, pos);
            if (end < 0) {
                break;   // 半截 / 损坏 ⇒ 停在这里，与引擎一致
            }
            out.add(new Rec(pos, end, bytes[pos + 4]));
            pos = end;
        }
        return out;
    }

    /**
     * 从 {@code offset} 起解析一条记录，返回它的结束偏移；解析不出来返回 -1。
     */
    private static int recordEnd(byte[] b, int offset) {
        if (offset + 15 > b.length) {
            return -1;
        }
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream(64);
            DataOutputStream ro = new DataOutputStream(raw);
            DataInputStream in = new DataInputStream(
                    new java.io.ByteArrayInputStream(b, offset, b.length - offset));
            byte[] magic = new byte[4];
            in.readFully(magic);
            ro.write(magic);
            if (!MAGIC.equals(new String(magic, StandardCharsets.US_ASCII))) {
                return -1;
            }
            byte op = in.readByte();
            ro.writeByte(op);
            long ts = in.readLong();
            ro.writeLong(ts);
            int nameLen = in.readShort();
            ro.writeShort(nameLen);
            if (nameLen < 0) {
                return -1;
            }
            byte[] name = new byte[nameLen];
            in.readFully(name);
            ro.write(name);
            int payloadLen = in.readInt();
            ro.writeInt(payloadLen);
            if (payloadLen < 0 || payloadLen > 64 * 1024 * 1024) {
                return -1;
            }
            byte[] payload = new byte[payloadLen];
            in.readFully(payload);
            raw.write(payload);
            int crcRead = in.readInt();
            CRC32 crc = new CRC32();
            crc.update(raw.toByteArray());
            if ((int) crc.getValue() != crcRead) {
                return -1;   // CRC 不过 ⇒ 引擎也会在这里停
            }
            return offset + raw.size() + 4;
        } catch (EOFException e) {
            return -1;   // 半截记录
        } catch (IOException e) {
            return -1;
        }
    }
}
