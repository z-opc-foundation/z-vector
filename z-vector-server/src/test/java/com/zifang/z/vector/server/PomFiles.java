package com.zifang.z.vector.server;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 测试用的 pom 读法：定位聚合 pom + 按 DOM 结构取版本定义点。
 * <p>
 * 两个决定各有理由：
 * <ul>
 *   <li>用 DOM 而不是正则/缩进启发式 —— "模块自己写没写 {@code <version>}"问的是
 *       {@code <project>} 的<b>直接子元素</b>，而 {@code <parent>} 里的 {@code <version>}
 *       缩进一模一样（实测第一版用"四个空格的 &lt;version&gt;"就把合规写法判成违规）。</li>
 *   <li>这两件事有两个独立的尺要读（{@code /health} 兑现的版本、pom 结构契约）。一份实现两把尺共用，
 *       才不会出现"版本只有一个定义点"被两个口径不同的读法分别自证一致。</li>
 * </ul>
 */
final class PomFiles {

    private PomFiles() {
    }

    static String read(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("读不了 " + p + "：" + e, e);
        }
    }

    /**
     * 从当前工作目录（surefire 的 basedir = 模块目录）往上找聚合 pom。
     * 只认 "artifactId=z-vector 且 packaging=pom"：模块自己的 pom 在 {@code <parent>} 块里也写着
     * {@code <artifactId>z-vector</artifactId>}，靠 packaging 才分得开；再往上层的
     * {@code com.zifang:z-opc} 两个条件都不满足。
     */
    static Path rootPom() {
        Path from = Paths.get("").toAbsolutePath();
        for (Path d = from; d != null; d = d.getParent()) {
            Path pom = d.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                continue;
            }
            Element project = parse(read(pom)).getDocumentElement();
            if ("z-vector".equals(childText(project, "artifactId"))
                    && "pom".equals(childText(project, "packaging"))) {
                return pom;
            }
        }
        throw new AssertionError("从 " + from + " 往上找不到聚合 pom —— 聚合 pom 改名或换 packaging 了？");
    }

    /** 全仓版本的唯一定义点：聚合 pom {@code <properties>} 里那一个 {@code <revision>}。 */
    static String revisionOf(String rootPomXml) {
        Element properties = child(parse(rootPomXml).getDocumentElement(), "properties");
        assertNotNull(properties, "聚合 pom 没有 <properties>，版本定义点无从谈起");
        List<Element> revisions = children(properties, "revision");
        assertEquals1(revisions.size(), "聚合 pom 的 <properties> 里 <revision> 的个数");
        String v = text(revisions.get(0));
        assertFalse(v.isEmpty(), "<revision> 是空串");
        assertFalse(v.contains("${"), "<revision> 自身还是表达式：" + v);
        return v;
    }

    /** 聚合 pom {@code <modules>} 列出来的模块目录名。 */
    static List<String> modulesOf(String rootPomXml) {
        Element modules = child(parse(rootPomXml).getDocumentElement(), "modules");
        assertNotNull(modules, "聚合 pom 没有 <modules>：模块清单空了，尺就成了空跑");
        List<String> out = new ArrayList<String>();
        for (Element m : children(modules, "module")) {
            out.add(text(m));
        }
        assertFalse(out.isEmpty(), "聚合 pom 的 <modules> 是空的 ⇒ 下面那些逐模块的尺一条都不会看");
        return out;
    }

    static String declaredVersionOf(String pomXml) {
        return childText(parse(pomXml).getDocumentElement(), "version");
    }

    /**
     * {@code <properties>} 里 {@code <revision>} 的个数（不取值，只数）。定义在聚合 pom 之外
     * 任何一处都是第二个定义点 —— 数个数而不是取值，才能把"模块自己也声明了一个 revision"报出来。
     */
    static int revisionCount(String pomXml) {
        Element properties = child(parse(pomXml).getDocumentElement(), "properties");
        return properties == null ? 0 : children(properties, "revision").size();
    }

    /** {@code <project>} 的直接子元素 {@code <parent>}；没有则 null。 */
    static Element parentOf(String pomXml) {
        return child(parse(pomXml).getDocumentElement(), "parent");
    }

    /** 所有 {@code io.github.yuku123} 且 artifactId 以 z-vector 开头的 {@code <dependency>}。 */
    static List<Element> intraRepoDependencies(String pomXml) {
        List<Element> out = new ArrayList<Element>();
        NodeList all = parse(pomXml).getElementsByTagName("dependency");
        for (int i = 0; i < all.getLength(); i++) {
            Node node = all.item(i);
            if (!(node instanceof Element)) {
                continue;
            }
            Element dep = (Element) node;
            if ("io.github.yuku123".equals(childText(dep, "groupId"))
                    && String.valueOf(childText(dep, "artifactId")).startsWith("z-vector")) {
                out.add(dep);
            }
        }
        return out;
    }

    static String artifactIdOf(Element dependency) {
        return childText(dependency, "artifactId");
    }

    static Document parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            // 读的都是仓里自己的 pom，但默认配置会解析外部实体：一律关掉。
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            return b.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new AssertionError("pom 解析不了（XML 本身坏了？先修被测量的）：" + e, e);
        }
    }

    // ==================== DOM 小工具 ====================

    static Element child(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? null : found.get(0);
    }

    static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<Element>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n instanceof Element && name.equals(n.getNodeName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    static String childText(Element parent, String name) {
        Element c = child(parent, name);
        return c == null ? null : text(c);
    }

    static String text(Element el) {
        return el.getTextContent() == null ? "" : el.getTextContent().trim();
    }

    private static void assertEquals1(int actual, String what) {
        assertTrue(actual == 1, what + " 应当恰好 1 个，实际 " + actual
                + " 个 ⇒「全仓版本只有一个定义点」当场不成立");
    }
}
