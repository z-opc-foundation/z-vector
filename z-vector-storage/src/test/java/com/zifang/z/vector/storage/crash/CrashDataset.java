package com.zifang.z.vector.storage.crash;

import com.zifang.z.vector.api.VectorPoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 崩溃恢复验证套件的固定数据集。
 *
 * <p><b>为什么要固定种子</b>：本套件的断言是「kill -9 之后三口径必须完全一致」，
 * 这类断言只有在「写进去的东西每次都一样」的前提下才可复现 —— 否则一次红点到底是
 * 引擎的锅还是数据集漂移的锅，没人说得清。
 *
 * <p><b>为什么要「每个点一个独立种子」</b>（{@code SEED + i} 而不是共用一个 Random）：
 * 共用一个 Random 时，第 i 个点的向量取决于「它前面写了几个点」——
 * 写入顺序一变、批量写变单条写，向量就全变了，断言的基线跟着漂。
 * 独立种子让「第 i 个点长什么样」只由 i 决定，与任何写入时序无关。
 *
 * <p>{@link java.util.Random} 的算法由 JDK 规范逐字固定（线性同余 + 48bit 种子），
 * 跨 JDK 版本、跨机器产出同一串数 ⇒ 这套断言在任何机器上跑出的基线都相同。
 */
final class CrashDataset {

    /** 向量维度。16 维足够让 HNSW 建出有意义的图，又不会把测试拖慢。 */
    static final int DIM = 16;

    /** 全局种子。改它等于换一份数据集 —— 换之前先想清楚基线断言要不要跟着改。 */
    static final long SEED = 20261022L;

    /** payload 里的语言字段取值集合（等值索引用）。 */
    static final String[] LANGS = {"zh", "en"};

    /** payload 里的分桶字段取值个数（数值索引用）。 */
    static final int BUCKETS = 4;

    /**
     * MID_WAL 档：子进程在「宣布 ready」之前先落盘多少条。
     *
     * <p>为什么要有这个下限：WAL 半途那一档要断的是「写入途中被杀」，
     * 如果连一条都没落盘，恢复出来就是空集，三口径根本无从对账。
     * 先把这一批 fsync 掉，后面那批才真的在「半途」——
     * 于是断言可以写成「恢复出来的条数落在 [下限, 上限] 区间内，且必须是写入序列的前缀」。
     */
    static final int MID_WAL_DURABLE_FLOOR = 50;

    private CrashDataset() {
    }

    /** 第 i 个点的 id。 */
    static String id(int i) {
        return String.format("p-%05d", i);
    }

    /** 第 i 个点的向量（只由 i 决定，与写入时序无关）。 */
    static float[] vector(int i) {
        Random rnd = new Random(SEED + i);
        float[] v = new float[DIM];
        for (int d = 0; d < DIM; d++) {
            v[d] = (float) rnd.nextGaussian();
        }
        return v;
    }

    /** 第 i 个点的完整记录（含 payload：等值字段 lang + 数值字段 bucket + 序号 seq）。 */
    static VectorPoint point(int i) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("lang", langOf(i));
        payload.put("bucket", bucketOf(i));
        payload.put("seq", i);
        return new VectorPoint(id(i), vector(i), payload);
    }

    /** [from, from+count) 区间的点。 */
    static List<VectorPoint> points(int from, int count) {
        List<VectorPoint> out = new ArrayList<VectorPoint>(count);
        for (int i = from; i < from + count; i++) {
            out.add(point(i));
        }
        return out;
    }

    static String langOf(int i) {
        return LANGS[i % LANGS.length];
    }

    static int bucketOf(int i) {
        return i % BUCKETS;
    }

    /** 前 n 个点里 lang == lang 的点数。 */
    static int expectedLangCount(String lang, int n) {
        int c = 0;
        for (int i = 0; i < n; i++) {
            if (langOf(i).equals(lang)) c++;
        }
        return c;
    }

    /** 前 n 个点里 bucket == b 的点数。 */
    static int expectedBucketCount(int b, int n) {
        int c = 0;
        for (int i = 0; i < n; i++) {
            if (bucketOf(i) == b) c++;
        }
        return c;
    }
}
