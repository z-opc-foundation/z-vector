package com.zifang.z.vector.server;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "全仓版本只有一个定义点"的结构契约。
 * <p>
 * 为什么要这一层：{@code /health} 那条尺只量得到 server 模块自己盖章出来的值，量不到
 * "别的模块又偷偷写回一个 {@code <version>1.0.x</version>}"。而后者才是真会咬人的形状 ——
 * 兄弟依赖一旦写死字面量，漏 {@code -am} 时 reactor 里是 1.0.4、声明要的却是 1.0.3，
 * Maven 会安静地去仓库拉旧构件（本机 ~/.m2 与 Central 上都有 1.0.x 全套，实测 200），
 * 编译/测试全绿而测的是线上旧字节。
 * <p>
 * 三条判据都读 DOM 而不是正则：{@code <parent>} 里的 {@code <version>} 和模块自己声明的
 * {@code <version>} 缩进一模一样，第一版用"四个空格的 &lt;version&gt;"当"顶层"判据，
 * 直接合规的替身 pom 上假红了一轮（见 {@link #p4_theChecksActuallyFireOnTheShapesTheyForbid}）。
 */
class PomVersionContractTest {

    // ==================== 真树 ====================

    /** P1：版本只写在聚合 pom 的 {@code <revision>} 一处；其它 pom 的自身坐标都得是引用。 */
    @Test
    void p1_revisionIsTheOnlyVersionDefinition() {
        Path root = PomFiles.rootPom();
        String rootText = PomFiles.read(root);
        // revisionOf 自己钉"聚合 pom 的 <revision> 恰好 1 个、非空、自身不是表达式"
        PomFiles.revisionOf(rootText);

        List<String> problems = new ArrayList<String>();
        problems.addAll(ownVersionProblems(rootText, "pom.xml", true));
        for (Path module : modulePoms(root)) {
            problems.addAll(ownVersionProblems(
                    PomFiles.read(module), rel(root, module), false));
        }
        assertEquals(0, problems.size(), "版本不再只有一个定义点：\n  " + join(problems));
    }

    /** P2：每个模块 pom 引的都是聚合 pom，且版本写 {@code ${revision}}（抄字面量就要跟着同步）。 */
    @Test
    void p2_modulesInheritInsteadOfRepeatingTheVersion() {
        Path root = PomFiles.rootPom();
        List<String> problems = new ArrayList<String>();
        for (Path module : modulePoms(root)) {
            problems.addAll(inheritanceProblems(PomFiles.read(module), rel(root, module)));
        }
        assertEquals(0, problems.size(), "模块 pom 没有老老实实继承：\n  " + join(problems));
    }

    /**
     * P3：兄弟（io.github.yuku123:z-vector-*）依赖在模块里不许带 {@code <version>} —— 版本由聚合
     * pom 的 dependencyManagement 统一给；反过来聚合 pom 那儿必须给，且给的必须是引用。
     * 两个方向合起来钉的才是"漏 -am 静默测线上旧字节"那一族。
     */
    @Test
    void p3_intraRepoDependenciesAreManagedNotPinned() {
        Path root = PomFiles.rootPom();
        List<String> problems = new ArrayList<String>();
        problems.addAll(siblingDependencyProblems(PomFiles.read(root), "pom.xml", true));
        for (Path module : modulePoms(root)) {
            problems.addAll(siblingDependencyProblems(
                    PomFiles.read(module), rel(root, module), false));
        }
        assertEquals(0, problems.size(), "兄弟依赖的版本写错了地方：\n  " + join(problems));
    }

    // ==================== 猎物（正向对照：判据真能报出问题） ====================
    //
    // 上面三条都是"不许出现 X"。没有猎物的负向断言会把"判据失效"和"真的没有 X"读成同一个结果，
    // 所以每条判据都要有喂进去必红的合成 pom，外加一支"合规写法不许报"的反向对照 ——
    // 全部与真树走同一个判定函数。期望命中数写死成具体条数：判据互相串味也读得出来。

    @Test
    void p4_theChecksActuallyFireOnTheShapesTheyForbid() {
        // (1) 聚合 pom 把自己的版本写成字面量 ⇒ P1
        String rootPinned = pom(
                "  <artifactId>z-vector</artifactId>\n  <version>1.2.3</version>\n"
                        + "  <packaging>pom</packaging>\n"
                        + "  <properties><revision>1.2.3</revision></properties>\n");
        assertEquals(1, ownVersionProblems(rootPinned, "synthetic-root", true).size(),
                "P1 判据对\"版本又写回 <version> 字面量\"无感 ⇒ 上一节那个 0 问题不算证据");

        String rootClean = pom(
                "  <artifactId>z-vector</artifactId>\n  <version>${revision}</version>\n"
                        + "  <packaging>pom</packaging>\n"
                        + "  <properties><revision>1.2.3</revision></properties>\n");
        assertEquals(0, ownVersionProblems(rootClean, "synthetic-root", true).size(),
                "合规的聚合 pom 被 P1 误伤（假红）：" + join(ownVersionProblems(rootClean, "s", true)));

        // (2) 模块抄了一份版本 + 引父写死字面量 ⇒ P1 一条、P2 一条，各抓各的
        String repeating = pom(
                "  <parent>\n    <groupId>io.github.yuku123</groupId>\n"
                        + "    <artifactId>z-vector</artifactId>\n    <version>1.2.3</version>\n  </parent>\n"
                        + "  <artifactId>z-vector-core</artifactId>\n  <version>1.2.3</version>\n");
        assertEquals(1, ownVersionProblems(repeating, "synthetic-module", false).size(),
                "P1 判据对\"模块又写了自己的 <version>\"无感");
        assertEquals(1, inheritanceProblems(repeating, "synthetic-module").size(),
                "P2 判据对\"<parent> 版本写死字面量\"无感");

        // 反向对照：真树那种写法（只引父、不写自身版本）三条判据都不许报
        String moduleClean = pom(
                "  <parent>\n    <groupId>io.github.yuku123</groupId>\n"
                        + "    <artifactId>z-vector</artifactId>\n    <version>${revision}</version>\n  </parent>\n"
                        + "  <artifactId>z-vector-core</artifactId>\n  <packaging>jar</packaging>\n"
                        + siblingDep("z-vector-api", null)
                        + thirdPartyDep("jackson-databind", "2.18.6") + "\n");
        assertEquals(0, ownVersionProblems(moduleClean, "synthetic-module", false).size(),
                "P1 误伤合规模块：" + join(ownVersionProblems(moduleClean, "s", false)));
        assertEquals(0, inheritanceProblems(moduleClean, "synthetic-module").size(),
                "P2 误伤合规模块：" + join(inheritanceProblems(moduleClean, "s")));
        assertEquals(0, siblingDependencyProblems(moduleClean, "synthetic-module", false).size(),
                "P3 误伤合规模块：" + join(siblingDependencyProblems(moduleClean, "s", false)));

        // (3) 兄弟依赖在模块里被写死 ⇒ P3；第三方依赖同处不许被误伤
        String pinnedSibling = pom(
                "  <artifactId>z-vector-core</artifactId>\n"
                        + siblingDep("z-vector-api", "1.2.3") + thirdPartyDep("jackson-databind", "2.18.6")
                        + "\n");
        List<String> p3Hits = siblingDependencyProblems(pinnedSibling, "synthetic-module", false);
        assertEquals(1, p3Hits.size(), "P3 判据对\"兄弟依赖写死版本\"无感（或把第三方也算进来了）：" + join(p3Hits));
        assertTrue(p3Hits.get(0).contains("z-vector-api"), "报的不是那条兄弟依赖：" + join(p3Hits));

        // (4) 聚合 pom 的 dependencyManagement 反过来出错：写成字面量 / 干脆不给版本
        //     后者会让模块里那句不带 <version> 的依赖悬空 —— 是同一枚契约的另一半。
        String managedLiteral = pom("  <artifactId>z-vector</artifactId>\n"
                + siblingDep("z-vector-api", "1.2.3") + "\n");
        assertEquals(1, siblingDependencyProblems(managedLiteral, "synthetic-root", true).size(),
                "P3 判据对\"dependencyManagement 里写成字面量\"无感");

        String managedMissing = pom("  <artifactId>z-vector</artifactId>\n"
                + siblingDep("z-vector-api", null) + "\n");
        assertEquals(1, siblingDependencyProblems(managedMissing, "synthetic-root", true).size(),
                "P3 判据对\"dependencyManagement 漏给版本\"无感 ⇒ 模块那边会静默解析不出来");
    }

    // ==================== 判定 ====================

    /**
     * 「这个 pom 有没有把本仓版本抄成第二处」。
     *
     * @param isAggregate 聚合 pom 自己：{@code <version>} 必须是 {@code ${revision}}；
     *                    模块：不许写 {@code <version>}，也不许另定义 {@code <revision>}。
     */
    private static List<String> ownVersionProblems(String pomXml, String where, boolean isAggregate) {
        List<String> out = new ArrayList<String>();
        String declared = PomFiles.declaredVersionOf(pomXml);
        if (isAggregate) {
            if (!"${revision}".equals(declared)) {
                out.add(where + "：聚合 pom 自身的 <version> 是 " + declared + "，不是 ${revision}");
            }
            return out;
        }
        int revisions = PomFiles.revisionCount(pomXml);
        if (revisions != 0) {
            out.add(where + "：又定义了 " + revisions + " 个 <revision> ⇒ 全仓版本不再只有一个定义点");
        }
        if (declared != null) {
            out.add(where + "：写了 <version>" + declared + "</version>"
                    + "（继承即可；抄一份就意味着改号时要记得同步这里）");
        }
        return out;
    }

    /** 「模块引父引对了没有」：必须存在 {@code <parent>}、指的是 z-vector、版本写 {@code ${revision}}。 */
    private static List<String> inheritanceProblems(String pomXml, String where) {
        List<String> out = new ArrayList<String>();
        Element parent = PomFiles.parentOf(pomXml);
        if (parent == null) {
            out.add(where + "：没有 <parent> —— 那它的版本从哪儿来？");
            return out;
        }
        String parentArtifact = PomFiles.childText(parent, "artifactId");
        if (!"z-vector".equals(parentArtifact)) {
            out.add(where + "：<parent> 指的是 " + parentArtifact + "，不是聚合 pom z-vector");
        }
        String parentVersion = PomFiles.childText(parent, "version");
        if (!"${revision}".equals(parentVersion)) {
            out.add(where + "：<parent><version> 写的是 " + parentVersion + "，必须是 ${revision}");
        }
        return out;
    }

    /**
     * 「兄弟依赖的版本写在哪」。
     *
     * @param isAggregate 聚合 pom（dependencyManagement）：必须带 {@code <version>} 且是引用 ——
     *                    模块那边才有的可继承；模块：一条 {@code <version>} 都不许带。
     */
    private static List<String> siblingDependencyProblems(String pomXml, String where, boolean isAggregate) {
        List<String> out = new ArrayList<String>();
        for (Element dep : PomFiles.intraRepoDependencies(pomXml)) {
            String artifactId = PomFiles.artifactIdOf(dep);
            String version = PomFiles.childText(dep, "version");
            if (isAggregate) {
                if (version == null) {
                    out.add(where + "：dependencyManagement 没给 " + artifactId
                            + " 版本 ⇒ 模块里那句不带 <version> 的依赖悬空");
                } else if (!"${revision}".equals(version) && !"${project.version}".equals(version)) {
                    out.add(where + "：dependencyManagement 把 " + artifactId
                            + " 写成字面量 " + version + "（该引 ${revision}）");
                }
            } else if (version != null) {
                out.add(where + "：" + artifactId + " 带了 <version>" + version + "</version>："
                        + "版本只该在聚合 pom 一处。写了它，漏 -am 时 reactor 里是新版本而声明要的是"
                        + "仓库里的旧构件 ⇒ 静默编译/测试线上旧字节");
            }
        }
        return out;
    }

    // ==================== 小工具 ====================

    private static List<Path> modulePoms(Path rootPom) {
        List<Path> out = new ArrayList<Path>();
        for (String module : PomFiles.modulesOf(PomFiles.read(rootPom))) {
            Path pom = rootPom.getParent().resolve(module).resolve("pom.xml");
            assertTrue(Files.isRegularFile(pom),
                    "聚合 pom 列了模块 " + module + "，但那儿的 pom.xml 不存在：" + pom);
            out.add(pom);
        }
        assertEquals(7, out.size(), "模块数变了：这一族的尺要跟着复核，实际 " + out.size());
        return out;
    }

    private static String rel(Path root, Path module) {
        return root.getParent().relativize(module).toString();
    }

    private static String pom(String body) {
        return "<project>\n" + body + "</project>\n";
    }

    private static String siblingDep(String artifactId, String version) {
        return "  <dependencies>\n    <dependency>\n      <groupId>io.github.yuku123</groupId>\n"
                + "      <artifactId>" + artifactId + "</artifactId>\n"
                + (version == null ? "" : "      <version>" + version + "</version>\n")
                + "    </dependency>\n  </dependencies>\n";
    }

    private static String thirdPartyDep(String artifactId, String version) {
        return "  <dependencies>\n    <dependency>\n      <groupId>com.fasterxml.jackson.core</groupId>\n"
                + "      <artifactId>" + artifactId + "</artifactId>\n"
                + "      <version>" + version + "</version>\n"
                + "    </dependency>\n  </dependencies>\n";
    }

    private static String join(List<String> rows) {
        return rows.isEmpty() ? "[]" : String.join("\n  ", rows);
    }
}
