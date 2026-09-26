package com.zifang.z.vector.starter;

import com.zifang.z.vector.api.DistanceMetric;
import com.zifang.z.vector.api.IndexType;
import com.zifang.z.vector.api.VectorCollection;
import com.zifang.z.vector.api.VectorStore;
import com.zifang.z.vector.starter.autoconfigure.ZVectorAutoConfiguration;
import com.zifang.z.vector.starter.autoconfigure.ZVectorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code zvector.*} 这一族配置的<b>兑现契约</b>尺。
 * <p>
 * 改前的实测事实（探针输出留在 {@code ~/.cache/zv-config/probe-before.txt}）：
 * <ul>
 *   <li>README 的 {@code zvector.default-index: {type, params}} 那种嵌套写法<b>一个键都绑不上</b>，
 *       而且不报错 —— {@code ignoreUnknownFields} 默认开着，静默丢掉；</li>
 *   <li>改成扁平写法（{@code default-index=IVF}、{@code default-index-params.M=8}）能绑进属性对象，
 *       但建出来的集合<b>仍然是 FLAT、config 仍然为空</b> —— 全仓没有一个读取方；</li>
 *   <li>README"方式二"教的 {@code z.vector.{enabled,mode,host,port}} 前缀<b>代码里根本不存在</b>：
 *       配 {@code z.vector.port=49467} 后 REST 起在默认的 6334，用户照着文档端口去连，什么也连不上。</li>
 * </ul>
 * 这和被忽略的 Bloom 配置、没人读的 {@code auto-start} 是同一族缺陷：<b>属性有值、日志有值、行为没有值</b>。
 * <p>
 * 每一把尺都从外部事实判（建出来的集合到底是什么索引、绑没绑上），不看日志。
 */
class ZVectorConfigContractTest {

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ZVectorAutoConfiguration.class));
    }

    /** 同 ZVectorRestAutoConfigurationTest：上下文没起来时 consumer 一条都不执行，那种绿是空跑。 */
    private static void requireReached(AtomicBoolean ran) {
        assertTrue(ran.get(), "上下文没有起来，里面的断言一条都没跑 ⇒ 这一例什么都没测");
    }

    /**
     * 属性经 system property 进来时 {@code params} 的值是字符串 "16" 而不是 16（真 YAML 配置才是 Integer）。
     * {@code IndexFactory.intParam} 两种都收，尺跟着两种都收 —— 但"根本没到这个键"必须红。
     */
    private static int asInt(Object v, String what) {
        assertNotNull(v, what + " 没传到（键压根不在表里）");
        if (v instanceof Number) return ((Number) v).intValue();
        return Integer.parseInt(String.valueOf(v).trim());
    }

    private static VectorCollection create3Arg(VectorStore store, String name) {
        store.createCollection(name, 8, DistanceMetric.COSINE);
        VectorCollection vc = store.getCollection(name);
        assertNotNull(vc, "集合没建出来: " + name);
        return vc;
    }

    /**
     * C1：README 里那组<b>嵌套</b>写法必须真的绑上。用 IVF（属性默认值是 FLAT 语义的"不覆盖"）
     * 才分得清"绑上了"和"字段初始值"，HNSW 在改前正是被默认值伪装成绑成功的。
     * <p>
     * nlist 用 <b>99</b> 而不是 64：64 正是 {@code IvfIndex.DEFAULT_NLIST}。集合 config 存的是
     * 原样传入的 map（{@code VectorCollection} 构造器），所以 64 在这一条里暂时还能判别；
     * 一旦 config 改成"存解析后的默认值"，64 就永远绿。变异电池第一轮就是把 setParams 摘成空
     * 方法、21 条全绿，才逼出这个巧合（那一支的真相是 binder 走 getter 不走 setter，见 N6 记账）。
     */
    @Test
    void c1_nestedReadmeShapeBinds() {
        AtomicBoolean ran = new AtomicBoolean();
        runner().withPropertyValues(
                "zvector.default-index.type=IVF",
                "zvector.default-index.params.nlist=99")
                .run(ctx -> {
                    ran.set(true);
                    assertNull(ctx.getStartupFailure(), String.valueOf(ctx.getStartupFailure()));
                    ZVectorProperties p = ctx.getBean(ZVectorProperties.class);
                    assertEquals("IVF", p.getDefaultIndex().getType(),
                            "README 的 default-index.type 没绑上（改前就是这一条：静默丢掉）");
                    assertEquals(99, asInt(p.getDefaultIndex().getParams().get("nlist"), "nlist"),
                            "README 的 default-index.params.* 没绑上");
                });
        requireReached(ran);
    }

    /**
     * C2：绑上的配置必须<b>兑现</b>到 3 参 createCollection 建出来的集合上。
     * 两个用例：带 params 的、只给 type 的 —— 后者专抓"只有给了参数才生效"这种半生效形状。
     * <p>
     * M/efConstruction 用 32/400，与 {@code HnswIndex.DEFAULT_M=16 / DEFAULT_EF_CONSTRUCTION=200}
     * 岔开（同 C1 的理由：与默认值同数的取样，一旦 config 改存解析值就永远分不出来）。
     */
    @Test
    void c2_configuredDefaultIndexReachesCollections() {
        AtomicBoolean ran = new AtomicBoolean();
        runner().withPropertyValues(
                "zvector.default-index.type=HNSW",
                "zvector.default-index.params.M=32",
                "zvector.default-index.params.efConstruction=400")
                .run(ctx -> {
                    ran.set(true);
                    VectorCollection vc = create3Arg(ctx.getBean(VectorStore.class), "docs");
                    assertEquals(IndexType.HNSW, vc.getIndexType(),
                            "配了 default-index.type=HNSW，建出来的却是 " + vc.getIndexType()
                            + "（改前恒为 FLAT：属性有人绑、没人读）");
                    Map<String, Object> cfg = vc.getConfig();
                    assertNotNull(cfg, "indexParams 丢了：集合 config 为 null");
                    assertEquals(32, asInt(cfg.get("M"), "M"), "M 没传到集合");
                    assertEquals(400, asInt(cfg.get("efConstruction"), "efConstruction"),
                            "efConstruction 没传到集合");
                });
        requireReached(ran);
    }

    /** C2b：只给 type、不给 params，也必须换索引类型。 */
    @Test
    void c2b_typeWithoutParamsStillApplies() {
        AtomicBoolean ran = new AtomicBoolean();
        runner().withPropertyValues("zvector.default-index.type=IVF")
                .run(ctx -> {
                    ran.set(true);
                    VectorCollection vc = create3Arg(ctx.getBean(VectorStore.class), "vecs");
                    assertEquals(IndexType.IVF, vc.getIndexType(),
                            "没有 params 时默认索引被吞了（半生效形状）");
                });
        requireReached(ran);
    }

    /** C3：显式指定索引的那一支不许被配置覆盖 —— 默认值只该管"没说"的调用。 */
    @Test
    void c3_explicitIndexTypeWinsOverDefault() {
        AtomicBoolean ran = new AtomicBoolean();
        runner().withPropertyValues("zvector.default-index.type=HNSW",
                "zvector.default-index.params.M=16")
                .run(ctx -> {
                    ran.set(true);
                    VectorStore store = ctx.getBean(VectorStore.class);
                    java.util.Map<String, Object> params = new java.util.HashMap<String, Object>();
                    params.put("nlist", 8);
                    store.createCollection("explicit", 8, DistanceMetric.L2, IndexType.IVF, params);
                    VectorCollection vc = store.getCollection("explicit");
                    assertEquals(IndexType.IVF, vc.getIndexType(),
                            "显式的 IVF 被配置的 HNSW 顶掉了");
                    assertEquals(8, asInt(vc.getConfig().get("nlist"), "显式 nlist"),
                            "显式的 indexParams 被顶掉了");
                });
        requireReached(ran);
    }

    /** C4：什么都不配时保持内置 FLAT —— 这一条是 C2 的阳性对照的反面：证明 C2 的 HNSW 来自配置。 */
    @Test
    void c4_unconfiguredKeepsBuiltinFlat() {
        AtomicBoolean ran = new AtomicBoolean();
        runner().run(ctx -> {
            ran.set(true);
            VectorCollection vc = create3Arg(ctx.getBean(VectorStore.class), "plain");
            assertEquals(IndexType.FLAT, vc.getIndexType(),
                    "没配 default-index 就不该换索引；建出来是 " + vc.getIndexType());
        });
        requireReached(ran);
    }

    /** C5：写错一个字母必须让应用起不来，而不是安静退回 FLAT。 */
    @Test
    void c5_bogusIndexTypeFailsLoudly() {
        AtomicBoolean ran = new AtomicBoolean();
        runner().withPropertyValues("zvector.default-index.type=hnsw-graph")
                .run(ctx -> {
                    ran.set(true);
                    Throwable failure = ctx.getStartupFailure();
                    assertNotNull(failure,
                            "非法的 default-index.type 竟然起来了 —— 静默退回默认索引就是这一族的病根");
                    String chain = String.valueOf(failure.getMessage()) + causeChain(failure);
                    assertTrue(chain.contains("zvector.default-index.type"),
                            "报错没点名是哪个键： " + chain);
                    assertTrue(chain.contains("HNSW") && chain.contains("IVF"),
                            "报错没列出可选取值： " + chain);
                });
        requireReached(ran);
    }

    private static String causeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            sb.append(" | ").append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
        }
        return sb.toString();
    }

    /**
     * C6：README 里广告出来的 {@code zvector.*} 键，必须逐个能在真实属性面上绑上。
     * 这是防"文档再次漂回代码前面"的闸 —— 把 {@code z.vector.mode} 那种不存在的键塞回 README，
     * 这一条就红（C6control 就是钉这个的猎物对照，见 {@link #c6control_scannerActuallyFakes}）。
     */
    @Test
    void c6_readmeKeysAllBind() throws IOException {
        Path readme = repoFile("README.md");
        String text = new String(Files.readAllBytes(readme), StandardCharsets.UTF_8);
        List<String> keys = documentedVectorKeys(text);
        assertFalse(keys.isEmpty(),
                "README 里一个 zvector/z.vector 配置键都没抓到 ⇒ 尺看不见东西，判绿没有意义"
                        + "（检查 " + readme + " 的 ```yaml 围栏还在不在）");
        Set<String> leaves = new LinkedHashSet<>();
        Set<String> maps = new LinkedHashSet<>();
        collectBoundNames(ZVectorProperties.class, declaredPrefix(), leaves, maps);
        List<String> unbound = new ArrayList<>();
        for (String k : keys) {
            if (!bindable(k, leaves, maps)) unbound.add(k);
        }
        assertTrue(unbound.isEmpty(),
                "README 广告了绑不上的配置键 " + unbound + "；真实属性面是 " + leaves + " (可带子键 "
                        + maps + ")");
    }

    /**
     * 属性面前缀从 {@code @ConfigurationProperties} 注解读出来，<b>不在尺里硬编码</b>。
     * 写死 "zvector" 的话，注解里的前缀漂到 {@code zvectorx} 时尺会跟着漂（它照样自认一致），
     * 而 README 的 {@code zvector.*} 一个也绑不上 —— 这正是本闸要抓的病，不能靠猜。
     */
    private static String declaredPrefix() {
        ConfigurationProperties ann =
                ZVectorProperties.class.getAnnotation(ConfigurationProperties.class);
        assertNotNull(ann, "ZVectorProperties 上没有 @ConfigurationProperties，属性面无从谈起");
        String p = ann.prefix();
        assertFalse(p.isEmpty(), "@ConfigurationProperties 的 prefix 为空");
        return p;
    }

    /**
     * C6control：负向断言必须有猎物。同一个扫描器喂一段"假 README"，两种病都要被抓到：
     * 不存在的 {@code z.vector.*} 前缀、绑不上的 {@code zvector.default-index.bogus}。
     * 顺带钉"空输入必须报错"，防止扫描器在没围栏时交出空表当满分。
     */
    @Test
    void c6control_scannerActuallyFakes() {
        String fake = "```yaml\n"
                + "z:\n"
                + "  vector:\n"
                + "    enabled: true\n"
                + "    port: 8181\n"
                + "zvector:\n"
                + "  storage-type: persistent\n"
                + "  default-index:\n"
                + "    type: HNSW\n"
                + "    bogus: 1\n"
                + "```\n";
        List<String> keys = documentedVectorKeys(fake);
        assertEquals(Arrays.asList("z.vector.enabled", "z.vector.port", "zvector.storage-type",
                        "zvector.default-index", "zvector.default-index.type",
                        "zvector.default-index.bogus"), keys,
                "扫描器漏键或串键： " + keys);

        Set<String> leaves = new LinkedHashSet<>();
        Set<String> maps = new LinkedHashSet<>();
        // 这里也读注解前缀，不写死：前缀漂移该由 C6 单独红（消息点名"README 广告了绑不上的键"），
        // 对照项若一起红，报的却是"尺有 bug" —— 那是把诊断引向反方向。
        String pfx = declaredPrefix();
        collectBoundNames(ZVectorProperties.class, pfx, leaves, maps);
        assertFalse(bindable("z.vector.port", leaves, maps), "假前缀 z.vector.* 竟然算绑得上");
        assertFalse(bindable(pfx + ".default-index.bogus", leaves, maps), "不存在的叶子键竟然算绑得上");
        assertTrue(bindable(pfx + ".default-index", leaves, maps),
                "嵌套 Bean 的中间名必须算合法，否则 README 的 default-index: 那一行会被误判");
        assertTrue(bindable(pfx + ".default-index.params.M", leaves, maps),
                "params 是 Map，任意子键都该算合法（这条红说明 Map 通配没生效）");
        assertTrue(bindable(pfx + ".server.auto-start", leaves, maps), "真实键判成绑不上 = 尺有 bug");
        assertTrue(bindable(pfx + ".storage-type", leaves, maps), "真实键判成绑不上 = 尺有 bug");
        assertFalse(leaves.contains(pfx + ".default-index-params"),
                "旧的扁平兄弟键 default-index-params 早就不该在属性面上（在的话 C6 会放过它）");

        assertThrows(AssertionError.class, () -> requireFound(documentedVectorKeys("没有围栏")),
                "空输入必须报错，不能交白卷当满分");
    }

    private static void requireFound(List<String> keys) {
        assertFalse(keys.isEmpty(), "empty");
    }

    /**
     * C7：Dockerfile 不许往 VectorServerApplication 传 {@code --x.y=} 属性 —— 它是裸 main()，
     * 属性只从环境变量来（{@code ZVECTOR_PORT} / {@code ZVECTOR_DATA_DIR}）。改前 ENTRYPOINT 里那句
     * {@code --z.vector.data-dir=$ZVECTOR_DATA_DIR} 就是"广告了一个没人认的旋钮"，值其实靠 env 生效，
     * 谁照着它在命令行加 {@code --z.vector.port=...} 就会被静默忽略。
     */
    @Test
    void c7_dockerfilePassesNoPhantomProperties() throws IOException {
        String docker = new String(Files.readAllBytes(repoFile("Dockerfile")), StandardCharsets.UTF_8);
        assertTrue(phantomArgsOf(docker).isEmpty(),
                "Dockerfile 给一个不认属性的进程传了命令行属性 " + phantomArgsOf(docker)
                        + "（真正的旋钮是 ZVECTOR_PORT / ZVECTOR_DATA_DIR 环境变量）");
        // 阳性对照：取样器对"确实写了 --z.vector.data-dir=..."必须抓到，否则上面那条绿没有意义。
        assertEquals(java.util.Collections.singletonList("--z.vector.data-dir=/x"),
                phantomArgsOf("ENTRYPOINT [\"sh\", \"-c\", \"exec java -jar /app/z-vector-server.jar"
                        + " --z.vector.data-dir=/x\"]"), "C7 的取样器抓不到反例");
        // 反面对照：COPY --from=builder 那种同名 flag 不该被抓（抓到过 ⇒ 尺在冤枉人）。
        assertTrue(phantomArgsOf("COPY --from=builder /x/z-vector-server.jar /app/").isEmpty(),
                "取样器把 COPY 的 --from 当成了启动属性");
    }

    /** 只看"真的在起进程"的那几行：既要跑 java，又点名 server 的 jar 或主类。 */
    private static List<String> phantomArgsOf(String docker) {
        List<String> bad = new ArrayList<>();
        for (String line : docker.split("\n")) {
            boolean launches = line.contains("java")
                    && (line.contains("z-vector-server.jar") || line.contains("VectorServerApplication"));
            if (!launches) continue;
            for (String tok : line.trim().split("\\s+")) {
                String t = tok.replaceAll("[\"\\]\\[]+$", "");
                if (t.startsWith("--") && t.length() > 2) bad.add(t);
            }
        }
        return bad;
    }

    /**
     * C8：README 里的 maven 坐标必须是真坐标。改前"快速开始"教的是
     * {@code com.zifang:z-vector-spring-boot-starter:1.0.0-SNAPSHOT} —— repo1 实测 404，
     * 而真坐标 {@code io.github.yuku123:…:1.0.1} 实测 200。这一条钉三件事：groupId 对、
     * artifactId 是仓库里真有的模块、已发布的文档不许写 SNAPSHOT。
     */
    @Test
    void c8_readmeMavenCoordinatesAreReal() throws IOException {
        String readme = new String(Files.readAllBytes(repoFile("README.md")), StandardCharsets.UTF_8);
        String rootPom = new String(Files.readAllBytes(repoFile("pom.xml")), StandardCharsets.UTF_8);
        String projectGroupId = projectGroupId(rootPom);
        List<String[]> deps = mavenDeps(readme);
        assertFalse(deps.isEmpty(), "README 里一个 <dependency> 块都没抓到 ⇒ 尺看不见东西");
        for (String[] d : deps) {
            assertEquals(projectGroupId, d[0],
                    "README 教的 groupId 是 " + d[0] + "，工程真实坐标是 " + projectGroupId
                            + "（照抄的人拉不到东西）");
            assertTrue(isReactorModule(rootPom, d[1]) || !d[1].startsWith("z-vector"),
                    "README 引用了一个不存在的模块 " + d[1]);
            assertFalse(d[2].endsWith("-SNAPSHOT"),
                    "面向用户的依赖示例写着 SNAPSHOT：" + d[1] + ":" + d[2]);
        }
        // 阳性对照：把改前那组坏坐标喂进来，三类病都得被抓到。
        List<String[]> bad = mavenDeps("<dependency><groupId>com.zifang</groupId>"
                + "<artifactId>z-vector-spring-boot-starter</artifactId>"
                + "<version>1.0.0-SNAPSHOT</version></dependency>");
        assertEquals(1, bad.size(), "坐标取样器抓不到反例 ⇒ 上面的绿没有意义");
        assertEquals("com.zifang", bad.get(0)[0]);
        assertTrue(bad.get(0)[2].endsWith("-SNAPSHOT"));
        assertTrue(isReactorModule(rootPom, "z-vector-core"),
                "模块存在性判断连真模块都不认 ⇒ 上面那条断言是空跑");
        assertFalse(isReactorModule(rootPom, "z-vector-does-not-exist"),
                "模块存在性判断对编出来的模块也放行 ⇒ 尺没牙");
    }

    /** 抓 README 里每个 {@code <dependency>} 块的 (groupId, artifactId, version)。 */
    private static List<String[]> mavenDeps(String text) {
        List<String[]> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?s)<dependency>(.*?)</dependency>").matcher(text);
        while (m.find()) {
            String block = m.group(1);
            String g = tag(block, "groupId");
            String a = tag(block, "artifactId");
            String v = tag(block, "version");
            if (g != null && a != null && v != null) out.add(new String[]{g, a, v});
        }
        return out;
    }

    private static String firstElement(String xml, String tag) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?s)<" + tag + ">([^<]*)</" + tag + ">").matcher(xml);
        if (m.find()) return m.group(1).trim();
        throw new AssertionError("找不到 <" + tag + ">");
    }

    /**
     * 工程自己的 groupId。必须先切掉 {@code <parent>} 那一截 —— 根 pom 里第一个 {@code <groupId>}
     * 是父 pom 的（com.zifang），照着它取会把"要修的错坐标"当成"正确答案"，尺就反过来为错误背书。
     */
    private static String projectGroupId(String rootPom) {
        int end = rootPom.indexOf("</parent>");
        String rest = end < 0 ? rootPom : rootPom.substring(end + "</parent>".length());
        String g = firstElement(rest, "groupId");
        assertTrue(g.contains("yuku123"),
                "从 pom 里派生出的工程 groupId 是 " + g + " ⇒ 取样方式不对，别用它当参照集");
        return g;
    }

    private static String tag(String block, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?s)<" + name + ">\\s*([^<\\s][^<]*?)\\s*</" + name + ">").matcher(block);
        return m.find() ? m.group(1).trim() : null;
    }

    private static boolean isReactorModule(String rootPom, String artifactId) {
        return rootPom.contains("<module>" + artifactId + "</module>");
    }

    // ===================== 扫描/属性面工具 =====================

    /**
     * 从 markdown 的 ```yaml 围栏里按缩进还原出点号键，只收 {@code zvector} 与 {@code z.vector} 两族。
     * 只认 yaml 围栏：bash/python 块里的 {@code foo: bar} 不是配置。围栏状态必须分"在不在围栏内"和
     * "这个围栏是不是 yaml"两半 —— 早先写成一半时，关掉一个 bash 围栏会顺手打开 yaml 模式，
     * 整篇 README 的键被错配到 bash 块里，扫出来是 0 个键（尺瞎了而不是文档干净）。
     */
    private static List<String> documentedVectorKeys(String markdown) {
        List<String> out = new ArrayList<>();
        List<String> stack = new ArrayList<>();     // 形如 ["zvector", "default-index"]，与 indents 一一对应
        List<Integer> indents = new ArrayList<>();
        boolean inFence = false;
        boolean yaml = false;
        for (String raw : markdown.split("\n")) {
            if (raw.trim().startsWith("```")) {
                if (inFence) {
                    inFence = false;
                    yaml = false;
                } else {
                    inFence = true;
                    yaml = isYamlFence(raw);
                }
                stack.clear();
                indents.clear();
                continue;
            }
            if (!inFence || !yaml) continue;
            String line = stripComment(raw);
            if (line.trim().isEmpty()) continue;
            String t = line.replaceAll("\\s+$", "");
            int indent = 0;
            while (indent < t.length() && t.charAt(indent) == ' ') indent++;
            String body = t.trim();
            int colon = body.indexOf(':');
            if (colon <= 0) continue;
            String key = body.substring(0, colon).trim();
            if (key.isEmpty() || key.startsWith("-")) continue;
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                stack.remove(stack.size() - 1);
            }
            stack.add(key);
            indents.add(indent);
            String path = joinPath(stack);
            if (path.startsWith("zvector.") || path.startsWith("z.vector.")) out.add(path);
        }
        return out;
    }

    private static boolean isYamlFence(String fenceLine) {
        String tag = fenceLine.trim().substring(3).trim().toLowerCase();
        return tag.isEmpty() || tag.equals("yaml") || tag.equals("yml");
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    /** {@code [z, vector, port]} → {@code z.vector.port}；{@code [zvector, default-index]} → 同形。 */
    private static String joinPath(List<String> stack) {
        StringBuilder sb = new StringBuilder();
        for (String s : stack) {
            if (sb.length() > 0) sb.append('.');
            sb.append(s);
        }
        return sb.toString();
    }

    /** 反射走属性类：叶子键进 leaves，Map 型字段进 maps（其任意子键都算合法）。 */
    private static void collectBoundNames(Class<?> type, String prefix,
                                          Set<String> leaves, Set<String> maps) {
        for (Field f : type.getDeclaredFields()) {
            if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) continue;
            String name = prefix + "." + kebab(f.getName());
            Class<?> ft = f.getType();
            if (Map.class.isAssignableFrom(ft)) {
                maps.add(name);
                leaves.add(name);
            } else if (ft.getName().startsWith("com.zifang.") || isNestedBean(ft)) {
                collectBoundNames(ft, name, leaves, maps);
            } else {
                leaves.add(name);
            }
        }
    }

    private static boolean isNestedBean(Class<?> ft) {
        return !ft.isEnum() && !ft.isArray() && !ft.getName().startsWith("java.")
                && ft.getDeclaredFields().length > 0;
    }

    private static String kebab(String fieldName) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fieldName.length(); i++) {
            char c = fieldName.charAt(i);
            if (Character.isUpperCase(c)) {
                sb.append('-').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean bindable(String key, Set<String> leaves, Set<String> maps) {
        if (leaves.contains(key)) return true;
        for (String m : maps) {
            if (key.startsWith(m + ".")) return true;
        }
        // 嵌套 Bean 的中间名（zvector.default-index / zvector.server）本身不是叶子，但它下面有叶子就算合法
        for (String leaf : leaves) {
            if (leaf.startsWith(key + ".")) return true;
        }
        return false;
    }

    /**
     * 从模块目录往上找到<b>仓库根</b>：既有 README.md，又是那个带 {@code <modules>} 的聚合 pom
     * 所在目录。只按"存在 pom.xml"停会停在当前模块自己的 pom 上 —— 那一份没有 {@code <modules>}，
     * 模块存在性检查就会把 {@code z-vector-core} 判成不存在（我第一次就踩在这里）。
     */
    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++, dir = dir.getParent()) {
            Path pom = dir.resolve("pom.xml");
            Path readme = dir.resolve("README.md");
            if (Files.isRegularFile(pom) && Files.isRegularFile(readme)
                    && contains(pom, "<modules>")) {
                return dir;
            }
        }
        throw new AssertionError("从 " + Paths.get("").toAbsolutePath()
                + " 往上找不到仓库根（要有 README.md + 带 <modules> 的 pom.xml）⇒ 这一例其实什么都没检查");
    }

    private static boolean contains(Path file, String needle) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains(needle);
        } catch (IOException e) {
            throw new AssertionError("读 " + file + " 失败: " + e, e);
        }
    }

    /** 仓库根下的相对路径；找不到就报错，不放过"尺根本没读到文件"。 */
    private static Path repoFile(String relative) {
        Path candidate = repoRoot().resolve(relative);
        if (!Files.isRegularFile(candidate)) {
            throw new AssertionError("仓库根 " + repoRoot() + " 下没有 " + relative
                    + " ⇒ 这一例其实什么都没检查");
        }
        return candidate;
    }
}
