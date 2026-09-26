package com.zifang.z.vector.storage.wal;

import com.zifang.z.vector.api.VectorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Write-Ahead Log (WAL) — 预写日志。
 * <p>
 * 设计参考 zvec 的 LocalWalFile + SQLite 的 WAL + PostgreSQL 的 WAL.
 *
 * <h2>核心特性</h2>
 * <ul>
 *   <li><b>顺序追加</b>：每条记录追加到文件尾部，写入 O(1)</li>
 *   <li><b>CRC32 校验</b>：每条记录末尾有 CRC32，损坏可检测</li>
 *   <li><b>崩溃恢复</b>：启动时重放所有记录，重建内存状态</li>
 *   <li><b>Checkpoint</b>：周期性创建 snapshot，截断旧 WAL</li>
 * </ul>
 *
 * <h2>文件格式</h2>
 * <pre>
 * Record 1 | Record 2 | ... | Record N
 *  每个 Record:
 *  [magic 4B][op 1B][timestamp 8B][nameLen 2B][name 变长][payloadLen 4B][payload 变长][crc32 4B]
 * </pre>
 *
 * <h2>线程安全</h2>
 * 内部使用 synchronized 保护写入；读取在启动时单线程执行。
 */
public class WalFile implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WalFile.class);

    /** 默认 WAL 段大小上限（超过则 rotate）。16MB 对标 RocksDB 默认值。 */
    public static final long DEFAULT_MAX_SEGMENT_SIZE = 16 * 1024 * 1024;

    /**
     * 单条记录 payload 上限。读到超长/负数长度说明流已错位，必须作为 IOException 报错
     * （{@code parseRecords} 会停在这里），而不是让 {@code new byte[n]} 抛
     * NegativeArraySizeException 冲出整个恢复流程。
     */
    static final int MAX_PAYLOAD_SIZE = 64 * 1024 * 1024;

    private final Path walPath;
    private final Path dir;
    private final long maxSegmentSize;
    private RandomAccessFile raf;
    private long sequenceNumber;
    private int segmentIndex = 0;  // 当前段的序号

    public WalFile(String dataDir) throws IOException {
        this(dataDir, DEFAULT_MAX_SEGMENT_SIZE);
    }

    public WalFile(String dataDir, long maxSegmentSize) throws IOException {
        this.dir = Paths.get(dataDir);
        this.walPath = dir.resolve("wal.log");
        this.maxSegmentSize = maxSegmentSize;
        Files.createDirectories(this.dir);
        open();
    }

    private void open() throws IOException {
        raf = new RandomAccessFile(walPath.toFile(), "rw");
        // 扫描目录中已存在的 rotated segments（重启时恢复 segmentIndex）
        segmentIndex = discoverRotatedSegments();
        sequenceNumber = countRecords();
        LOG.info("WAL opened: {}, segments={}, records replayed: {}",
                walPath, segmentIndex + 1, sequenceNumber);
    }

    /**
     * 扫描目录，发现已存在的 rotated segments 并返回最大 segment 序号。
     */
    private int discoverRotatedSegments() {
        int maxIdx = 0;
        try {
            try (java.util.stream.Stream<Path> stream = java.nio.file.Files.list(dir)) {
                java.util.regex.Pattern p = java.util.regex.Pattern.compile("wal_(\\d+)\\.log");
                for (Path p2 : (Iterable<Path>) stream::iterator) {
                    java.util.regex.Matcher m = p.matcher(p2.getFileName().toString());
                    if (m.matches()) {
                        int idx = Integer.parseInt(m.group(1));
                        if (idx > maxIdx) maxIdx = idx;
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to scan rotated segments: {}", e.getMessage());
        }
        return maxIdx;
    }

    /** 追加一条记录 */
    public synchronized void append(WalRecord record) throws IOException {
        // 检查段大小，超过则 rotate
        if (raf != null && raf.length() >= maxSegmentSize) {
            rotate();
        }
        byte[] bytes = serialize(record);
        raf.seek(raf.length());
        raf.write(bytes);
        raf.getFD().sync(); // 强制刷盘
        sequenceNumber++;
    }

    /**
     * 一批记录一次 write + 一次 fsync（group commit 真正落地的地方）。
     * <p>
     * 为什么要它：{@link #append} 每条记录都 {@code getFD().sync()}，所以"攒 64 条再刷"的
     * {@link AsyncWalFile} 实际上还是在每条一次 fsync —— 批量只省了锁，没省 IO。250 上实测
     * 一次 fsync 8.3ms、一次 2000 条的批量落盘 8ms（比值 2086 倍，同机对照），
     * {@code MemoryStabilityTest} 的 30s flush 超时就是这么来的（本机 NVMe 只要 67us，看不见）。
     * <p>
     * 分段判据与 {@link #append} 逐字相同（"写之前看当前段够不够"，所以越界那条仍留在老段，
     * 下一条才 rotate），整批写出的字节和逐条 append 完全一致 —— 这一点由
     * {@code WalGroupCommitTest} 逐字节对拍钉住，包括批内跨段的情况。
     */
    public synchronized void appendBatch(List<WalRecord> records) throws IOException {
        if (records == null || records.isEmpty()) return;
        ByteArrayOutputStream buf = new ByteArrayOutputStream(1024);
        for (WalRecord record : records) {
            // buf 里的字节还没进文件，所以"当前长度"要算上它们；为空时这条判据就退化成一进
            // 循环时的 raf.length() 检查，与 append() 完全一致（含 rotate 自己会跳过空文件）。
            if (raf != null && raf.length() + buf.size() >= maxSegmentSize) {
                if (buf.size() > 0) drain(buf);
                rotate();
            }
            buf.write(serialize(record));
            sequenceNumber++;
        }
        if (buf.size() > 0) drain(buf);
    }

    /** 把攒下的一次写下去、一次 sync —— 一个段一次 fsync。 */
    private void drain(ByteArrayOutputStream buf) throws IOException {
        byte[] bytes = buf.toByteArray();
        raf.seek(raf.length());
        raf.write(bytes);
        raf.getFD().sync();
        buf.reset();
    }

    /**
     * 把当前 wal.log rotate 为 wal_N.log（编号 N+1），新 wal.log 接管后续写入。
     * <p>
     * 设计参考 RocksDB log file numbering（wal.log → wal.1.log → ...），
     * 避免单个 wal 文件无限增长。
     */
    public synchronized void rotate() throws IOException {
        if (raf == null || raf.length() == 0) return;  // 空文件不需要 rotate
        if (raf != null) {
            raf.close();
            raf = null;
        }
        // 当前 wal.log → wal_<segmentIndex+1>.log
        Path rotated = dir.resolve("wal_" + (++segmentIndex) + ".log");
        Files.move(walPath, rotated);
        LOG.info("WAL rotated: {} → {}", walPath, rotated);
        // 新建 wal.log
        raf = new RandomAccessFile(walPath.toFile(), "rw");
    }

    /**
     * 读取所有段的记录（启动恢复用，按写入先后顺序重放）。
     * <p>
     * {@code rotate()} 每次把 wal.log 改名成 wal_&lt;递增序号&gt;.log，因此 wal_1 最旧、
     * wal_N 最新、wal.log 次新。重放必须从 1 递增，否则后写入的 upsert 会被先写入的覆盖。
     */
    public List<WalRecord> readAll() throws IOException {
        List<WalRecord> records = new ArrayList<>();
        // 1. 先重放历史段（wal_1.log 最旧 → wal_N.log 最新）
        for (int i = 1; i <= segmentIndex; i++) {
            Path seg = dir.resolve("wal_" + i + ".log");
            if (Files.exists(seg)) {
                records.addAll(readSegment(seg));
            }
        }
        // 2. 最后重放当前 wal.log
        if (raf != null && raf.length() > 0) {
            records.addAll(readFromCurrentRaf());
        }
        return records;
    }

    private List<WalRecord> readFromCurrentRaf() throws IOException {
        List<WalRecord> records = new ArrayList<>();
        if (raf.length() == 0) return records;
        long pos = raf.getFilePointer();
        try {
            byte[] allBytes = new byte[(int) raf.length()];
            raf.seek(0);
            raf.readFully(allBytes);
            return parseRecords(allBytes);
        } finally {
            raf.seek(pos);
        }
    }

    private List<WalRecord> readSegment(Path segPath) throws IOException {
        byte[] allBytes = Files.readAllBytes(segPath);
        return parseRecords(allBytes);
    }

    private List<WalRecord> parseRecords(byte[] allBytes) {
        List<WalRecord> records = new ArrayList<>();
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(allBytes))) {
            while (dis.available() > 0) {
                try {
                    WalRecord rec = deserialize(dis);
                    records.add(rec);
                } catch (EOFException e) {
                    break;
                } catch (IOException e) {
                    LOG.warn("Failed to parse WAL record (corrupted?): {}", e.getMessage());
                    break;
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to read WAL segment: {}", e.getMessage());
        }
        return records;
    }

    /** 截断 WAL（checkpoint 后调用 — 删除所有历史段并重置当前段） */
    public synchronized void truncate() throws IOException {
        if (raf != null) {
            raf.setLength(0);
        }
        // 删除所有历史段
        for (int i = 1; i <= segmentIndex; i++) {
            Path seg = dir.resolve("wal_" + i + ".log");
            try {
                Files.deleteIfExists(seg);
            } catch (IOException e) {
                LOG.warn("Failed to delete rotated segment {}: {}", seg, e.getMessage());
            }
        }
        segmentIndex = 0;
        sequenceNumber = 0;
        LOG.info("WAL truncated (rotated segments deleted)");
    }

    /** 当前 WAL 段数（含当前活跃段）。 */
    public int segmentCount() {
        return segmentIndex + 1;
    }

    /** 当前段大小（字节）。 */
    public long currentSegmentSize() throws IOException {
        if (raf == null) return 0;
        return raf.length();
    }

    /** 关闭 */
    public synchronized void close() throws IOException {
        if (raf != null) {
            raf.close();
            raf = null;
        }
    }

    public long getSequenceNumber() { return sequenceNumber; }
    public Path getPath() { return walPath; }

    // ================= =  序列化 / 反序列化  =================

    private byte[] serialize(WalRecord record) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeBytes(WalRecord.MAGIC); // 4B magic
            dos.writeByte(record.getOp().code()); // 1B op
            dos.writeLong(record.getTimestamp()); // 8B timestamp

            byte[] nameBytes = record.getCollection().getBytes(StandardCharsets.UTF_8);
            dos.writeShort(nameBytes.length); // 2B name length
            dos.write(nameBytes); // 变长 name

            byte[] payloadBytes = record.getPayload().getBytes(StandardCharsets.UTF_8);
            dos.writeInt(payloadBytes.length); // 4B payload length
            dos.write(payloadBytes); // 变长 payload

            // CRC32 over (magic + op + timestamp + name + payload)
            byte[] allButCrc = baos.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(allButCrc);
            dos.writeInt((int) crc.getValue()); // 4B CRC
        }
        return baos.toByteArray();
    }

    private WalRecord deserialize(DataInputStream dis) throws IOException {
        // 逐字段读取的同时镜像一份原始字节，用于按 serialize() 同样的范围复算 CRC32
        ByteArrayOutputStream raw = new ByteArrayOutputStream(64);
        DataOutputStream ro = new DataOutputStream(raw);

        byte[] magic = new byte[4];
        dis.readFully(magic);
        ro.write(magic);
        String magicStr = new String(magic, StandardCharsets.US_ASCII);
        if (!WalRecord.MAGIC.equals(magicStr)) {
            throw new VectorException("Invalid WAL magic: " + magicStr);
        }
        byte opCode = dis.readByte();
        ro.writeByte(opCode);
        long timestamp = dis.readLong();
        ro.writeLong(timestamp);
        short nameLen = dis.readShort();
        ro.writeShort(nameLen);
        if (nameLen < 0) {
            throw new IOException("Invalid WAL name length: " + nameLen);
        }
        byte[] nameBytes = new byte[nameLen];
        dis.readFully(nameBytes);
        ro.write(nameBytes);
        String collection = new String(nameBytes, StandardCharsets.UTF_8);
        int payloadLen = dis.readInt();
        ro.writeInt(payloadLen);
        if (payloadLen < 0 || payloadLen > MAX_PAYLOAD_SIZE) {
            throw new IOException("Invalid WAL payload length: " + payloadLen);
        }
        byte[] payloadBytes = new byte[payloadLen];
        dis.readFully(payloadBytes);
        ro.write(payloadBytes);
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        int crcRead = dis.readInt();

        CRC32 crc = new CRC32();
        crc.update(raw.toByteArray());
        if ((int) crc.getValue() != crcRead) {
            throw new IOException("WAL CRC mismatch for record at seq~" + sequenceNumber
                    + " (collection=" + collection + ", computed="
                    + Integer.toHexString((int) crc.getValue())
                    + ", stored=" + Integer.toHexString(crcRead) + ")");
        }

        WalOpType op = WalOpType.fromCode(opCode);
        return new WalRecord(op, timestamp, collection, payload);
    }

    /**
     * 统计当前段已有的记录数（打开时用于恢复 sequenceNumber）。
     * <p>
     * 必须与 {@link #readAll()} 采用<b>完全相同</b>的容错语义：遇到损坏记录就停在此处，
     * 而不是把异常抛出构造流程。启用 CRC 校验之后，如果这里只接 {@code EOFException}，
     * 一条坏记录会让 {@code new WalFile(...)} 直接失败 —— 把"尾部丢一条"升级成
     * "整个库起不来"。共用同一次遍历也保证两者不会再各自漂移。
     */
    private long countRecords() throws IOException {
        if (raf.length() == 0) return 0;
        byte[] allBytes = new byte[(int) raf.length()];
        raf.seek(0);
        raf.readFully(allBytes);
        long count = parseRecords(allBytes).size();
        // 重置指针到末尾（追加模式）
        raf.seek(raf.length());
        return count;
    }
}