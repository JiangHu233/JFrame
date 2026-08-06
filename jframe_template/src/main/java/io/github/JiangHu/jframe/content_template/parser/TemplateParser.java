package io.github.JiangHu.jframe.content_template.parser;

import static io.github.JiangHu.jframe.content_template.TemplateConstants.*;

import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.content_template.ast.DynamicLine;
import io.github.JiangHu.jframe.content_template.ast.EachNode;
import io.github.JiangHu.jframe.content_template.ast.ExpressionNode;
import io.github.JiangHu.jframe.content_template.ast.IfNode;
import io.github.JiangHu.jframe.content_template.ast.LineEntry;
import io.github.JiangHu.jframe.content_template.ast.StaticLine;
import io.github.JiangHu.jframe.content_template.ast.TemplateNode;
import io.github.JiangHu.jframe.content_template.ast.TextNode;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * 模板解析器——将 XML 源码或纯文本编译为 {@link Template}（AST）。
 *
 * <p>支持两种输入：
 * <ul>
 *   <li>{@link #parseXml(String)}：解析 XML 模板（{@code <template>} 包裹，含 title + lines）</li>
 *   <li>{@link #parseText(String)}：解析纯文本模板（仅含 {@code {{ }}} 插值）</li>
 * </ul>
 *
 * <h3>XML 模板结构</h3>
 * <pre>{@code
 * <template>
 *   <title>{{serverName}} - 联机大厅</title>
 *   <line>玩家: {{player.name}}</line>
 *   <line if="player.isVip" else="非会员">VIP: {{player.vipLevel}}</line>
 *   <line-each items="players" max="10">{{index}}. {{this.name}}</line-each>
 *   <line>
 *     <if cond="exp > 1000">高手</if>
 *     <elif cond="exp > 500">进阶</elif>
 *     <else>新手</else>
 *   </line>
 * </template>
 * }</pre>
 *
 * <h3>解析流程</h3>
 * <ol>
 *   <li>JDK DOM 解析 XML 字符串 → Document</li>
 *   <li>遍历 {@code <template>} 子元素：{@code <title>} / {@code <line>} / {@code <line-each>}</li>
 *   <li>行内内容通过 {@link #parseInlineNodes} 递归解析为 AST 节点列表</li>
 *   <li>{@code {{ }}} 插值通过 {@link #parseInlineText} 分割为 TextNode / ExpressionNode</li>
 *   <li>{@code <if>} 链（if + elif* + else?）通过 {@link #parseIfChain} 合并为单个 IfNode</li>
 * </ol>
 */
public class TemplateParser {

    private final DocumentBuilder documentBuilder;

    public TemplateParser() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setIgnoringElementContentWhitespace(true);
            this.documentBuilder = factory.newDocumentBuilder();
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化 XML 解析器", e);
        }
    }

    /**
     * 解析 XML 模板源码。
     *
     * @param xml XML 源码（以 {@code <template>} 为根元素）
     * @return 编译后的模板
     * @throws TemplateParseException XML 格式错误时抛出
     */
    public Template parseXml(String xml) {
        try {
            Document doc = documentBuilder.parse(new InputSource(new StringReader(xml)));
            Element root = doc.getDocumentElement();

            if (!TAG_TEMPLATE.equals(root.getTagName())) {
                throw new TemplateParseException("根元素必须是 <" + TAG_TEMPLATE + ">，实际为 <" + root.getTagName() + ">");
            }

            List<TemplateNode> titleNodes = null;
            List<LineEntry> lineEntries = new ArrayList<>();

            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element el = (Element) child;
                switch (el.getTagName()) {
                    case TAG_TITLE -> titleNodes = parseInlineNodes(el.getChildNodes());
                    case TAG_LINE -> lineEntries.add(parseStaticLine(el));
                    case TAG_LINE_EACH -> lineEntries.add(parseDynamicLine(el));
                    default -> { /* 忽略未知标签 */ }
                }
            }
            return new Template(titleNodes, lineEntries, xml);
        } catch (TemplateParseException e) {
            throw e;
        } catch (Exception e) {
            throw new TemplateParseException("XML 模板解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析纯文本模板（仅含 {@code {{ }}} 插值，无 XML 结构）。
     *
     * @param text 纯文本源码
     * @return 编译后的纯文本模板
     */
    public Template parseText(String text) {
        List<TemplateNode> textNodes = parseInlineText(text);
        return new Template(textNodes, text);
    }

    /**
     * 解析 {@code <line>} 元素为 StaticLine。
     * <p>支持 {@code if} + {@code else} 属性实现行级条件。
     */
    private StaticLine parseStaticLine(Element el) {
        String ifCond = getAttr(el, ATTR_IF);
        String elseText = getAttr(el, ATTR_ELSE);
        List<TemplateNode> nodes = parseInlineNodes(el.getChildNodes());
        return new StaticLine(nodes, ifCond, elseText);
    }

    /**
     * 解析 {@code <line-each>} 元素为 DynamicLine。
     */
    private DynamicLine parseDynamicLine(Element el) {
        String itemsKey = getAttr(el, ATTR_ITEMS);
        int max = getIntAttr(el, ATTR_MAX, 0);
        List<TemplateNode> bodyNodes = parseInlineNodes(el.getChildNodes());
        return new DynamicLine(bodyNodes, itemsKey, max);
    }

    /**
     * 解析节点列表为行内 AST 节点列表。
     * <p>处理文本节点（TextNode / ExpressionNode）、{@code <if>} 链、{@code <each>}。
     * <p><b>空白处理</b>：跳过纯空白文本节点（元素间的换行/缩进），避免渲染输出包含
     * 换行符导致基岩版 sidebar 显示异常。非空白文本节点（含实际内容）正常保留。
     *
     * @param children DOM 子节点列表
     * @return AST 节点列表
     */
    private List<TemplateNode> parseInlineNodes(NodeList children) {
        List<TemplateNode> nodes = new ArrayList<>();
        int i = 0;
        while (i < children.getLength()) {
            Node child = children.item(i);
            if (isTextNode(child)) {
                // 跳过纯空白文本节点（元素间的换行/缩进），避免渲染输出包含 \n 导致 sidebar 显示异常
                if (!isBlankTextNode(child)) {
                    nodes.addAll(parseInlineText(child.getTextContent()));
                }
                i++;
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) child;
                String tag = el.getTagName();
                if (TAG_IF.equals(tag)) {
                    // 解析 if 链，消费后续的 elif / else
                    IfParseResult result = parseIfChain(children, i);
                    nodes.add(result.node());
                    i = result.nextIndex();
                } else if (TAG_EACH.equals(tag)) {
                    nodes.add(parseEachElement(el));
                    i++;
                } else if (TAG_ELIF.equals(tag) || TAG_ELSE.equals(tag)) {
                    // 孤立的 elif / else（没有前置 if），跳过
                    i++;
                } else {
                    i++;
                }
            } else {
                i++;
            }
        }
        return nodes;
    }

    /**
     * 解析 {@code <if>} 链（if + elif* + else?），返回 IfNode 和下一个待处理的索引。
     */
    private IfParseResult parseIfChain(NodeList children, int startIndex) {
        List<IfNode.Branch> branches = new ArrayList<>();
        List<TemplateNode> elseNodes = null;
        int i = startIndex;

        // 第一个 if 分支
        Element ifEl = (Element) children.item(i);
        branches.add(new IfNode.Branch(
                getAttr(ifEl, ATTR_COND),
                parseInlineNodes(ifEl.getChildNodes())
        ));
        i++;

        // 后续的 elif 和 else
        while (i < children.getLength()) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) child;
                String tag = el.getTagName();
                if (TAG_ELIF.equals(tag)) {
                    branches.add(new IfNode.Branch(
                            getAttr(el, ATTR_COND),
                            parseInlineNodes(el.getChildNodes())
                    ));
                    i++;
                } else if (TAG_ELSE.equals(tag)) {
                    elseNodes = parseInlineNodes(el.getChildNodes());
                    i++;
                    break;
                } else {
                    break;
                }
            } else if (isBlankTextNode(child)) {
                i++;
            } else {
                break;
            }
        }
        return new IfParseResult(new IfNode(branches, elseNodes), i);
    }

    /** 解析 {@code <each>} 元素为 EachNode */
    private EachNode parseEachElement(Element el) {
        String itemsKey = getAttr(el, ATTR_ITEMS);
        List<TemplateNode> bodyNodes = parseInlineNodes(el.getChildNodes());
        return new EachNode(itemsKey, bodyNodes);
    }

    /**
     * 将文本按 {@code {{ }}} 分割为 TextNode 和 ExpressionNode。
     *
     * <pre>{@code
     * "Hello {{name}}, age {{age}}" →
     *   TextNode("Hello "), ExpressionNode("name"), TextNode(", age "), ExpressionNode("age")
     * }</pre>
     */
    public List<TemplateNode> parseInlineText(String text) {
        List<TemplateNode> nodes = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return nodes;
        }
        int start = 0;
        while (true) {
            int open = text.indexOf(INTERP_OPEN, start);
            if (open == -1) {
                if (start < text.length()) {
                    nodes.add(new TextNode(text.substring(start)));
                }
                break;
            }
            if (open > start) {
                nodes.add(new TextNode(text.substring(start, open)));
            }
            int close = text.indexOf(INTERP_CLOSE, open + INTERP_OPEN.length());
            if (close == -1) {
                nodes.add(new TextNode(text.substring(open)));
                break;
            }
            String expr = text.substring(open + INTERP_OPEN.length(), close).trim();
            if (!expr.isEmpty()) {
                nodes.add(new ExpressionNode(expr));
            }
            start = close + INTERP_CLOSE.length();
        }
        return nodes;
    }

    // ==================== 工具方法 ====================

    private boolean isTextNode(Node node) {
        return node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE;
    }

    private boolean isBlankTextNode(Node node) {
        return node.getNodeType() == Node.TEXT_NODE && node.getTextContent().isBlank();
    }

    /** 获取属性值，空字符串返回 null */
    private String getAttr(Element el, String attr) {
        String value = el.getAttribute(attr);
        return value != null && !value.isEmpty() ? value : null;
    }

    /** 获取整数属性，缺失或无效返回默认值 */
    private int getIntAttr(Element el, String attr, int defaultValue) {
        String value = el.getAttribute(attr);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** if 链解析结果 */
    private record IfParseResult(IfNode node, int nextIndex) {
    }
}
