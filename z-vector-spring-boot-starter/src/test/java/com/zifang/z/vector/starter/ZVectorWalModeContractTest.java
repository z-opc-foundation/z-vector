package com.zifang.z.vector.starter;

import com.zifang.z.vector.starter.autoconfigure.ZVectorAutoConfiguration;
import com.zifang.z.vector.starter.autoconfigure.ZVectorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code zvector.wal.*} 的<b>兑现契约</b>尺。
 *
 * <p>WAL 模式不是调优开关，是<b>持久性语义</b>：配了就得兑现，兑现不了就得拒绝启动。
 * 这一族缺陷的原型是「属性有值、日志有值、行为没有值」—— 所以尺从外部事实判
 * （上下文起没起来、报错点没点名那个键），不看日志里写了什么。
 *
 * <p>注意本模块**没有** {@code z-vector-storage} 依赖（starter 不替应用把存储实现塞进来），
 * 所以「配了 wal.mode 却兑现不了」这条在本模块里是<b>常态</b>，正好用来钉「拒绝静默退回内存」。
 */
class ZVectorWalModeContractTest {

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ZVectorAutoConfiguration.class));
    }

    /** 同 ZVectorConfigContractTest：上下文没起来时 consumer 一条都不执行，那种绿是空跑。 */
    private static void requireReached(AtomicBoolean ran) {
        assertTrue(ran.get(), "上下文没有起来，里面的断言一条都没跑 ⇒ 这一例什么都没测");
    }

    private static String causeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            sb.append(" | ").append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
        }
        return sb.toString();
    }

    /** W1：默认档是 {@code sync}（ack 即持久，RPO = 0），且能绑上。 */
    @Test
    void w1_defaultModeIsSyncAndBinds() {
        AtomicBoolean ran = new AtomicBoolean();
        runner().run(ctx -> {
            ran.set(true);
            assertNull(ctx.getStartupFailure(), String.valueOf(ctx.getStartupFailure()));
            ZVectorProperties p = ctx.getBean(ZVectorProperties.class);
            assertEquals("sync", p.getWal().getMode(),
                    "默认 WAL 模式必须是 sync（ack 即持久、RPO=0）");
            assertEquals(1000L, p.getCheckpointInterval(),
                    "默认 checkpoint 间隔必须是 1000 条");
        });
        requireReached(ran);
    }

    /** W2：显式配了 {@code wal.mode} 时，值必须绑进属性面（不是绑了个影子）。 */
    @Test
    void w2_explicitModeBinds() {
        AtomicBoolean ran = new AtomicBoolean();
        runner().withPropertyValues("zvector.wal.mode=async").run(ctx -> {
            ran.set(true);
            assertNull(ctx.getStartupFailure(), String.valueOf(ctx.getStartupFailure()));
            assertEquals("async", ctx.getBean(ZVectorProperties.class).getWal().getMode(),
                    "zvector.wal.mode 没绑上");
        });
        requireReached(ran);
    }

    /** W3：写错一个字母必须让应用起不来，而不是安静退回 sync。 */
    @Test
    void w3_bogusModeFailsLoudly() {
        AtomicBoolean ran = new AtomicBoolean();
        runner().withPropertyValues("zvector.wal.mode=asnyc").run(ctx -> {
            ran.set(true);
            Throwable failure = ctx.getStartupFailure();
            assertNotNull(failure, "非法的 zvector.wal.mode 竟然起来了 —— 静默退回 sync 就是这一族的病根");
            String chain = String.valueOf(failure.getMessage()) + causeChain(failure);
            assertTrue(chain.contains("zvector.wal.mode"),
                    "报错没点名是哪个键： " + chain);
            assertTrue(chain.contains("sync") && chain.contains("async"),
                    "报错没列出可选取值： " + chain);
        });
        requireReached(ran);
    }

    /**
     * W4：显式配了 WAL 模式、但 {@code z-vector-storage} 不在 classpath 上 ⇒ <b>拒绝启动</b>。
     * <p>退回内存存储会让「我要持久 / 我要 RPO=0」这类显式要求静默失效 ——
     * 那正是这个仓反复在治的病。
     */
    @Test
    void w4_explicitModeWithoutStorageRefusesToStart() {
        AtomicBoolean ran = new AtomicBoolean();
        runner().withPropertyValues(
                "zvector.storage-type=persistent",
                "zvector.wal.mode=sync").run(ctx -> {
            ran.set(true);
            Throwable failure = ctx.getStartupFailure();
            assertNotNull(failure,
                    "显式配了 wal.mode 却退回内存存储起来了 —— 显式的持久性要求被静默吞掉了");
            String chain = String.valueOf(failure.getMessage()) + causeChain(failure);
            assertTrue(chain.contains("zvector.wal.mode"),
                    "报错没点名是哪个键： " + chain);
        });
        requireReached(ran);
    }

    /**
     * W5：<b>没</b>显式配 WAL 模式时，维持旧行为：{@code z-vector-storage} 不在 classpath 上
     * 就退回内存存储（只警告，不阻断启动）。
     * <p>这一条是 W4 的对照面：W4 钉的是「显式要求必须兑现」，W5 钉的是「没要求就别乱拦」。
     */
    @Test
    void w5_unconfiguredModeKeepsLegacyFallback() {
        AtomicBoolean ran = new AtomicBoolean();
        runner().withPropertyValues("zvector.storage-type=persistent").run(ctx -> {
            ran.set(true);
            assertNull(ctx.getStartupFailure(), String.valueOf(ctx.getStartupFailure()));
        });
        requireReached(ran);
    }

    /** W6：checkpoint 间隔配错必须让应用起不来（0 / 负数都不是合法档位）。 */
    @Test
    void w6_bogusCheckpointIntervalFailsLoudly() {
        AtomicBoolean ran = new AtomicBoolean();
        runner().withPropertyValues("zvector.checkpoint-interval=0").run(ctx -> {
            ran.set(true);
            Throwable failure = ctx.getStartupFailure();
            assertNotNull(failure, "非法的 zvector.checkpoint-interval 竟然起来了");
            String chain = String.valueOf(failure.getMessage()) + causeChain(failure);
            assertTrue(chain.contains("zvector.checkpoint-interval"),
                    "报错没点名是哪个键： " + chain);
        });
        requireReached(ran);
    }
}
