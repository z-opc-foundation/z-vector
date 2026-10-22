package com.zifang.z.vector.storage.crash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 崩溃恢复验证套件的<b>机器可读结果单</b>。
 *
 * <p><b>为什么要结果单而不是只看断言</b>：本卡的验收标准是「三时点 × 三口径全绿<b>或红点明确</b>」。
 * 「红点明确」意味着光有一句「测试挂了」不够 ——
 * 得能一眼看出：哪个时点、哪个口径、期望值是多少、实际是多少、差在哪。
 * JUnit 的失败信息只出现在控制台，CI 里没人翻；结果单是能进 CI 产物、能被脚本读的东西。
 *
 * <p>输出两份：{@code report.json}（给脚本 / 门禁读）与 {@code report.md}（给人看）。
 */
final class CrashConsistencyReport {

    /** 单条口径的判定结果。 */
    static final class Metric {
        final String id;
        final String name;
        final String status;      // GREEN / RED / N/A
        final String expected;
        final String actual;
        final String detail;

        Metric(String id, String name, String status, String expected, String actual, String detail) {
            this.id = id;
            this.name = name;
            this.status = status;
            this.expected = expected;
            this.actual = actual;
            this.detail = detail;
        }

        boolean isRed() {
            return "RED".equals(status);
        }
    }

    /** 一个时点档的结果。 */
    static final class Scenario {
        final String name;
        final String timing;
        final List<Metric> metrics = new ArrayList<Metric>();
        String killSignal = "";
        String note = "";

        Scenario(String name, String timing) {
            this.name = name;
            this.timing = timing;
        }

        void add(Metric m) {
            metrics.add(m);
        }

        boolean hasRed() {
            for (Metric m : metrics) {
                if (m.isRed()) {
                    return true;
                }
            }
            return false;
        }
    }

    private final List<Scenario> scenarios = new ArrayList<Scenario>();
    private final List<String> findings = new ArrayList<String>();

    Scenario scenario(String name, String timing) {
        Scenario s = new Scenario(name, timing);
        scenarios.add(s);
        return s;
    }

    void finding(String text) {
        findings.add(text);
    }

    boolean hasRed() {
        for (Scenario s : scenarios) {
            if (s.hasRed()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 落盘。目录默认取 {@code target/crash-consistency}（相对模块工作目录），
     * 可用系统属性 {@code crash.report.dir} 覆盖 —— CI 要把产物收走时用得上。
     */
    Path write() throws IOException {
        Path dir = Paths.get(System.getProperty("crash.report.dir", "target/crash-consistency"));
        Files.createDirectories(dir);
        Path json = dir.resolve("report.json");
        Files.write(json, toJson().getBytes(StandardCharsets.UTF_8));
        Path md = dir.resolve("report.md");
        Files.write(md, toMarkdown().getBytes(StandardCharsets.UTF_8));
        return json;
    }

    String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"card\": \"TASK-20261012-024\",\n");
        sb.append("  \"generated_at\": \"").append(now()).append("\",\n");
        sb.append("  \"seed\": ").append(CrashDataset.SEED).append(",\n");
        sb.append("  \"dimension\": ").append(CrashDataset.DIM).append(",\n");
        sb.append("  \"scenarios\": [\n");
        for (int i = 0; i < scenarios.size(); i++) {
            Scenario s = scenarios.get(i);
            sb.append("    {\n");
            sb.append("      \"name\": \"").append(esc(s.name)).append("\",\n");
            sb.append("      \"timing\": \"").append(esc(s.timing)).append("\",\n");
            sb.append("      \"kill_signal\": \"").append(esc(s.killSignal)).append("\",\n");
            sb.append("      \"note\": \"").append(esc(s.note)).append("\",\n");
            sb.append("      \"metrics\": [\n");
            for (int j = 0; j < s.metrics.size(); j++) {
                Metric m = s.metrics.get(j);
                sb.append("        {\"id\": \"").append(esc(m.id)).append("\", ")
                        .append("\"name\": \"").append(esc(m.name)).append("\", ")
                        .append("\"status\": \"").append(m.status).append("\", ")
                        .append("\"expected\": \"").append(esc(m.expected)).append("\", ")
                        .append("\"actual\": \"").append(esc(m.actual)).append("\", ")
                        .append("\"detail\": \"").append(esc(m.detail)).append("\"}");
                if (j < s.metrics.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("      ]\n");
            sb.append("    }");
            if (i < scenarios.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ],\n");
        sb.append("  \"findings\": [\n");
        for (int i = 0; i < findings.size(); i++) {
            sb.append("    \"").append(esc(findings.get(i))).append("\"");
            if (i < findings.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# z-vector 崩溃恢复一致性验证结果单\n\n");
        sb.append("- 卡：TASK-20261012-024\n");
        sb.append("- 生成时间：").append(now()).append("\n");
        sb.append("- 数据集：seed=").append(CrashDataset.SEED)
                .append(" dim=").append(CrashDataset.DIM).append("（固定种子 ⇒ 可重复执行）\n");
        sb.append("- 总判定：").append(hasRed() ? "🔴 有红点" : "🟢 全绿").append("\n\n");
        for (Scenario s : scenarios) {
            sb.append("## ").append(s.name).append("（").append(s.timing).append("）\n\n");
            sb.append("- kill：").append(s.killSignal).append("\n");
            if (s.note != null && !s.note.isEmpty()) {
                sb.append("- 说明：").append(s.note).append("\n");
            }
            sb.append("\n| 口径 | 判定 | 期望 | 实际 | 说明 |\n");
            sb.append("|---|---|---|---|---|\n");
            for (Metric m : s.metrics) {
                sb.append("| ").append(m.name).append(" | ").append(emoji(m.status)).append(" | ")
                        .append(nullSafe(m.expected)).append(" | ").append(nullSafe(m.actual))
                        .append(" | ").append(nullSafe(m.detail)).append(" |\n");
            }
            sb.append("\n");
        }
        if (!findings.isEmpty()) {
            sb.append("## 红点 / 发现\n\n");
            for (String f : findings) {
                sb.append("- ").append(f).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String emoji(String status) {
        if ("GREEN".equals(status)) {
            return "🟢 GREEN";
        }
        if ("RED".equals(status)) {
            return "🔴 RED";
        }
        return "⚪ " + status;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static String now() {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        return f.format(new java.util.Date());
    }
}
