package com.zifang.z.vector.storage.wal;

/**
 * WAL 落盘模式 —— 决定「{@code upsert()} 返回时，数据是否已经在磁盘上」。
 *
 * <h2>两种模式的语义</h2>
 * <table border="1">
 *   <caption>模式对照</caption>
 *   <tr><th>模式</th><th>写路径</th><th>返回即持久？</th><th>RPO（已 ack 却丢的条数）</th></tr>
 *   <tr><td>{@link #SYNC}（<b>默认</b>）</td>
 *       <td>每条记录一次 write + 一次 fsync（{@link WalFile#append}）</td>
 *       <td><b>是</b></td>
 *       <td><b>0 条</b> —— 已 ack 的写入不丢</td></tr>
 *   <tr><td>{@link #ASYNC}</td>
 *       <td>入队即返回，后台 flusher 按 batch/超时整批 fsync（{@link AsyncWalFile}）</td>
 *       <td><b>否</b></td>
 *       <td>崩溃瞬间<b>队列中尚未落盘的那一段</b>：自然窗口实测丢 1~4/126 条，
 *           人为拖慢 fsync 时可丢全批（实测 200/200）</td></tr>
 * </table>
 *
 * <h2>为什么默认是 SYNC</h2>
 * 引擎 javadoc 一直承诺「先写 WAL（强制 fsync），再更新内存」，而默认 v2 引擎此前走的是
 * 异步入队 ⇒ 这条承诺在默认配置下<b>不成立</b>（TASK-20261012-024 实测 kill -9 后已 ack
 * 的写入丢 1~4/126 条）。默认值因此翻转为 SYNC：<b>ack 即持久</b>，RPO = 0。
 * ASYNC 降级为<b>显式性能选项</b>，代价写在下面，用它的人必须自己接受 RPO ≠ 0。
 *
 * <h2>ASYNC 的代价（用之前先读完这段）</h2>
 * <ul>
 *   <li><b>RPO ≠ 0</b>：进程被 SIGKILL / 机器断电时，崩溃瞬间还在内存队列里的记录全部丢失，
 *       而调用方早已收到成功返回。</li>
 *   <li><b>丢失条数上界</b>：未落盘条数 ≤ min(队列容量 10000, 本批 64 条 + fsync 期间的到达条数)；
 *       <b>时间上界</b>：入队到落盘 ≤ {@link AsyncWalFile#DEFAULT_MAX_LATENCY_MS} ms + 一次 fsync 耗时
 *       （flusher 被阻塞时无上界）。</li>
 *   <li><b>吞吐收益</b>：group commit 把「每条一次 fsync」摊成「每批一次 fsync」
 *       （同机实测批量落盘比逐条 fsync 快两个数量级），代价就是上面这条丢失窗口。</li>
 * </ul>
 *
 * <p>相关缺陷修复卡：{@code TASK-20261022-042}（异步模式下的已 ack 丢失窗口）。
 * 崩溃恢复一致性验证套件：{@code _doc/003_script/run_crash_consistency_suite.sh}。
 *
 * <h2>取值解析</h2>
 * {@link #from(String)} 只认 {@code sync} / {@code async}（忽略大小写与首尾空白），
 * 拼错一律抛异常 —— 配置写错必须让应用起不来，而不是安静地退回某个默认值。
 */
public enum WalMode {

    /** 同步落盘：每条记录写完即 fsync 才返回。默认模式，RPO = 0。 */
    SYNC,

    /** 异步落盘：入队即返回，后台整批 fsync。性能选项，RPO ≠ 0（见类注释）。 */
    ASYNC;

    /**
     * 解析模式名。
     *
     * @param raw {@code sync} / {@code async}（大小写不敏感，允许首尾空白）
     * @return 对应模式
     * @throws IllegalArgumentException 取值不合法（消息里列出全部合法取值）
     */
    public static WalMode from(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("WAL mode must not be null. Supported: sync, async");
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("sync".equals(v)) return SYNC;
        if ("async".equals(v)) return ASYNC;
        throw new IllegalArgumentException("Unknown WAL mode \"" + raw
                + "\". Supported: sync, async");
    }

    /** 配置里的字面量（与 {@link #from(String)} 互逆）。 */
    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** 系统属性名（与 Spring 的 {@code zvector.wal.mode}、环境变量同名族）。 */
    public static final String MODE_PROPERTY = "zvector.wal.mode";

    /** 环境变量名（与 z-vector-server 的 {@code ZVECTOR_*} 同族）。 */
    public static final String MODE_ENV = "ZVECTOR_WAL_MODE";

    /**
     * 解析<b>进程级默认模式</b>：系统属性 {@value #MODE_PROPERTY} &gt; 环境变量
     * {@value #MODE_ENV} &gt; {@link #SYNC}。
     *
     * <p>两个环境入口都<b>只认</b> {@code sync} / {@code async}，写错一律抛异常 ——
     * 配置写错必须让应用起不来，而不是安静地退回某个默认值。
     *
     * <p>为什么需要进程级入口：{@code z-vector-server} 这类裸 {@code main()} 进程没有
     * Spring 的属性绑定，而 WAL 模式是<b>持久性语义</b>，运维必须在不改代码的前提下能显式
     * 切到 async（或切回 sync）。
     */
    public static WalMode resolveDefault() {
        String raw = System.getProperty(MODE_PROPERTY);
        String source = "system property " + MODE_PROPERTY;
        if (raw == null || raw.trim().isEmpty()) {
            raw = System.getenv(MODE_ENV);
            source = "environment variable " + MODE_ENV;
        }
        if (raw == null || raw.trim().isEmpty()) {
            return SYNC;
        }
        try {
            return from(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage()
                    + " (from " + source + ")", e);
        }
    }
}
