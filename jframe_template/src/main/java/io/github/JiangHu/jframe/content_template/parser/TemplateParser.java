package io.github.JiangHu.jframe.content_template.parser;

import static io.github.JiangHu.jframe.content_template.TemplateConstants.*;

import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.content_template.ast.ConditionalBlock;
import io.github.JiangHu.jframe.content_template.ast.ExpressionNode;
import io.github.JiangHu.jframe.content_template.ast.ForNode;
import io.github.JiangHu.jframe.content_template.ast.IfNode;
import io.github.JiangHu.jframe.content_template.ast.LineEntry;
import io.github.JiangHu.jframe.content_template.ast.LoopBlock;
import io.github.JiangHu.jframe.content_template.ast.StaticLine;
import io.github.JiangHu.jframe.content_template.ast.TemplateNode;
import io.github.JiangHu.jframe.content_template.ast.TextNode;
import io.github.JiangHu.jframe.content_template.column.ColumnAlign;
import io.github.JiangHu.jframe.content_template.column.ColumnLayout;
import io.github.JiangHu.jframe.content_template.column.ColumnSpec;
import io.github.JiangHu.jframe.content_template.column.ColumnWidth;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *   <li>{@link #parseXml(String)}：解析 XML 模板（{@code <template>} 包裹，含 title + 行区域）</li>
 *   <li>{@link #parseText(String)}：解析纯文本模板（仅含 {@code {{ }}} 插值）</li>
 * </ul>
 *
 * <h3>XML 模板结构（块级结构化语法）</h3>
 * <pre>{@code
 * <template>
 *   <title>{{serverName}} - 联机大厅</title>
 *   <line>玩家: {{player.name}}</line>
 *   <if cond="player.vip">                     <!-- 块级条件：控制行组显隐 -->
 *     §6⭐ VIP 专区                             <!-- 裸文本 = 一行 -->
 *     <line>§e到期: {{player.vipExpire}}</line>
 *     <for items="vipRewards" var="r" index="ri" max="3">   <!-- 块级循环 -->
 *       <line>§f{{ri + 1}}. {{r.name}}</line>
 *     </for>
 *   </if>
 *   <elif cond="player.level > 50">
 *     <line>§7高级玩家</line>
 *   </elif>
 *   <else>
 *     <line>§8普通玩家</line>
 *   </else>
 *   <line>好友: <for items="friends" var="f">{{f.name}}, </for></line>   <!-- 行内 for：拼接一行 -->
 * </template>
 * }</pre>
 *
 * <h3>位置区分语义</h3>
 * <ul>
 *   <li>{@code <if>/<elif>/<else>}：行区域=块级条件（{@link ConditionalBlock}）；line/title 内=行内条件（{@link IfNode}）</li>
 *   <li>{@code <for>}：行区域=块级循环（{@link LoopBlock}，每元素渲染整组子条目）；line/title 内=行内循环（{@link ForNode}，拼接一行）</li>
 *   <li>{@code <for>} 属性：{@code items}（数据源，SpEL）、{@code var}（元素变量名，缺省 this）、
 *       {@code index}（序号变量名，缺省 index）、{@code max}（最大迭代数，超出截断）</li>
 * </ul>
 *
 * <h3>引擎扩展语法（列格式 + 元数据）</h3>
 * <pre>{@code
 * <template columns="|">                        <!-- 根属性启用列格式（值为分隔符） -->
 *   <meta key="module" value="scoreboard"/>     <!-- 元数据（可多个，key 必填唯一） -->
 *   <columns>                                   <!-- 可选：逐列策略覆盖（最多一个） -->
 *     <column align="left" width="auto"/>
 *     <column align="right" width="10"/>
 *   </columns>
 *   <line>名称 | 数量</line>
 * </template>
 * }</pre>
 *
 * <h3>解析流程</h3>
 * <ol>
 *   <li>JDK DOM 解析 XML 字符串 → Document</li>
 *   <li>遍历 {@code <template>} 子元素：{@code <title>} / {@code <meta>} / {@code <columns>} 顶层处理，
 *       其余（line / if / for / 裸文本）进入行区域由 {@link #parseLineEntries} 递归解析</li>
 *   <li>行内内容通过 {@link #parseInlineNodes} 递归解析为 AST 节点列表</li>
 *   <li>{@code {{ }}} 插值通过 {@link #parseInlineText} 分割为 TextNode / ExpressionNode</li>
 *   <li>块级 {@code <if>} 链通过 {@link #parseBlockIfChain} 合并为单个 ConditionalBlock</li>
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
            Map<String, String> metadata = new LinkedHashMap<>();
            ColumnLayout columnLayout = parseRootColumnsAttr(root);
            boolean columnsBlockSeen = false;

            // 顶层分类：title/meta/columns 顶层专属处理，其余节点进入行区域
            List<Node> lineAreaNodes = new ArrayList<>();
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    Element el = (Element) child;
                    switch (el.getTagName()) {
                        case TAG_TITLE -> titleNodes = parseInlineNodes(el.getChildNodes());
                        case TAG_META -> collectMeta(el, metadata);
                        case TAG_COLUMNS -> {
                            if (columnsBlockSeen) {
                                throw new TemplateParseException(
                                        "<" + TAG_COLUMNS + "> 配置块最多只能出现一次");
                            }
                            columnsBlockSeen = true;
                            columnLayout = mergeColumnLayout(columnLayout, el);
                        }
                        default -> lineAreaNodes.add(child);
                    }
                } else {
                    lineAreaNodes.add(child);
                }
            }
            List<LineEntry> lineEntries = parseLineEntries(lineAreaNodes);
            return new Template(titleNodes, lineEntries, xml, columnLayout, metadata);
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

    // ==================== 行区域（块级）解析 ====================

    /**
     * 解析行区域节点列表为行条目列表——顶层与块级（if/for 内部）递归复用。
     *
     * <p>识别的节点：
     * <ul>
     *   <li>{@code <line>} → {@link StaticLine}</li>
     *   <li>{@code <if>} 链（if + elif* + else?）→ {@link ConditionalBlock}</li>
     *   <li>{@code <for>} → {@link LoopBlock}</li>
     *   <li>非空白文本节点 → 裸文本行（等价 {@code <line>文本</line>}）</li>
     *   <li>孤立 elif/else（无前置 if）与未知标签 → 跳过（容错）</li>
     * </ul>
     *
     * @param nodes 行区域节点列表
     * @return 行条目列表
     */
    private List<LineEntry> parseLineEntries(List<Node> nodes) {
        List<LineEntry> entries = new ArrayList<>();
        int i = 0;
        while (i < nodes.size()) {
            Node child = nodes.get(i);
            if (isTextNode(child)) {
                // 裸文本行：非空白文本节点解析为一行（空白跳过，避免渲染输出包含换行）
                if (!isBlankTextNode(child)) {
                    entries.add(new StaticLine(parseInlineText(child.getTextContent())));
                }
                i++;
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) child;
                String tag = el.getTagName();
                switch (tag) {
                    case TAG_LINE -> {
                        entries.add(new StaticLine(parseInlineNodes(el.getChildNodes())));
                        i++;
                    }
                    case TAG_IF -> {
                        // 块级 if 链，消费后续的 elif / else
                        BlockIfParseResult result = parseBlockIfChain(nodes, i);
                        entries.add(result.node());
                        i = result.nextIndex();
                    }
                    case TAG_FOR -> {
                        entries.add(parseLoopBlock(el));
                        i++;
                    }
                    case TAG_ELIF, TAG_ELSE -> {
                        // 孤立的 elif / else（没有前置 if），跳过
                        i++;
                    }
                    default -> {
                        // 未知标签忽略
                        i++;
                    }
                }
            } else {
                i++;
            }
        }
        return entries;
    }

    /**
     * 解析块级 {@code <if>} 链（if + elif* + else?），返回 ConditionalBlock 和下一个待处理的索引。
     *
     * <p>每个分支的子节点递归经 {@link #parseLineEntries} 解析为行条目列表。
     * 链中间出现非空白文本节点时链终止（该文本由外层作为裸文本行处理）。
     */
    private BlockIfParseResult parseBlockIfChain(List<Node> nodes, int startIndex) {
        List<ConditionalBlock.Branch> branches = new ArrayList<>();
        List<LineEntry> elseEntries = null;
        int i = startIndex;

        // 第一个 if 分支
        Element ifEl = (Element) nodes.get(i);
        branches.add(new ConditionalBlock.Branch(
                getAttr(ifEl, ATTR_COND),
                parseLineEntries(toList(ifEl.getChildNodes()))
        ));
        i++;

        // 后续的 elif 和 else
        while (i < nodes.size()) {
            Node child = nodes.get(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) child;
                String tag = el.getTagName();
                if (TAG_ELIF.equals(tag)) {
                    branches.add(new ConditionalBlock.Branch(
                            getAttr(el, ATTR_COND),
                            parseLineEntries(toList(el.getChildNodes()))
                    ));
                    i++;
                } else if (TAG_ELSE.equals(tag)) {
                    elseEntries = parseLineEntries(toList(el.getChildNodes()));
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
        return new BlockIfParseResult(new ConditionalBlock(branches, elseEntries), i);
    }

    /**
     * 解析块级 {@code <for>} 元素为 LoopBlock。
     * <p>属性：{@code items}（数据源）、{@code var}（元素变量名）、{@code index}（序号变量名）、{@code max}（最大迭代数）。
     */
    private LoopBlock parseLoopBlock(Element el) {
        String itemsKey = getAttr(el, ATTR_ITEMS);
        String varName = getAttr(el, ATTR_VAR);
        String indexVarName = getAttr(el, ATTR_INDEX);
        int max = getIntAttr(el, ATTR_MAX, 0);
        List<LineEntry> bodyEntries = parseLineEntries(toList(el.getChildNodes()));
        return new LoopBlock(itemsKey, varName, indexVarName, max, bodyEntries);
    }

    // ==================== 行内解析 ====================

    /**
     * 解析节点列表为行内 AST 节点列表。
     * <p>处理文本节点（TextNode / ExpressionNode）、行内 {@code <if>} 链、行内 {@code <for>}。
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
                    // 解析行内 if 链，消费后续的 elif / else
                    IfParseResult result = parseIfChain(children, i);
                    nodes.add(result.node());
                    i = result.nextIndex();
                } else if (TAG_FOR.equals(tag)) {
                    nodes.add(parseForNode(el));
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
     * 解析行内 {@code <if>} 链（if + elif* + else?），返回 IfNode 和下一个待处理的索引。
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

    /**
     * 解析行内 {@code <for>} 元素为 ForNode（每元素渲染结果拼接为一行）。
     * <p>属性与块级 for 一致：{@code items}/{@code var}/{@code index}/{@code max}。
     */
    private ForNode parseForNode(Element el) {
        String itemsKey = getAttr(el, ATTR_ITEMS);
        String varName = getAttr(el, ATTR_VAR);
        String indexVarName = getAttr(el, ATTR_INDEX);
        int max = getIntAttr(el, ATTR_MAX, 0);
        List<TemplateNode> bodyNodes = parseInlineNodes(el.getChildNodes());
        return new ForNode(itemsKey, varName, indexVarName, max, bodyNodes);
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

    // ==================== 列格式与元数据解析 ====================

    /**
     * 解析根属性 {@code columns}（列分隔符）。
     * <p>属性不存在返回 {@link ColumnLayout#DISABLED}；存在但为空串视为语法错误。
     */
    private ColumnLayout parseRootColumnsAttr(Element root) {
        if (!root.hasAttribute(ATTR_COLUMNS)) {
            return ColumnLayout.DISABLED;
        }
        String separator = root.getAttribute(ATTR_COLUMNS);
        if (separator.isEmpty()) {
            throw new TemplateParseException(
                    "根属性 " + ATTR_COLUMNS + " 不能为空：需提供列分隔符（如 columns=\"|\"）");
        }
        return ColumnLayout.enabled(separator, List.of());
    }

    /**
     * 合并 {@code <columns>} 配置块与根属性分隔符。
     * <p>分隔符优先取根属性值，根属性未启用时使用默认分隔符 {@link #DEFAULT_COLUMN_SEPARATOR}。
     */
    private ColumnLayout mergeColumnLayout(ColumnLayout current, Element columnsEl) {
        List<ColumnSpec> specs = parseColumnSpecs(columnsEl);
        String separator = current.isEnabled() ? current.getSeparator() : DEFAULT_COLUMN_SEPARATOR;
        return ColumnLayout.enabled(separator, specs);
    }

    /** 解析 {@code <columns>} 块内的 {@code <column>} 策略列表（忽略未知子标签） */
    private List<ColumnSpec> parseColumnSpecs(Element columnsEl) {
        List<ColumnSpec> specs = new ArrayList<>();
        NodeList children = columnsEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element el = (Element) child;
            if (TAG_COLUMN.equals(el.getTagName())) {
                specs.add(parseColumnSpec(el));
            }
        }
        return specs;
    }

    /** 解析单个 {@code <column align="left|center|right" width="auto|n"/>}，属性缺省为 left + auto */
    private ColumnSpec parseColumnSpec(Element el) {
        ColumnAlign align = ColumnAlign.LEFT;
        String alignAttr = getAttr(el, ATTR_ALIGN);
        if (alignAttr != null) {
            align = switch (alignAttr.toLowerCase()) {
                case "left" -> ColumnAlign.LEFT;
                case "center" -> ColumnAlign.CENTER;
                case "right" -> ColumnAlign.RIGHT;
                default -> throw new TemplateParseException(
                        "<" + TAG_COLUMN + "> 属性 " + ATTR_ALIGN + " 非法: " + alignAttr + "（可选 left/center/right）");
            };
        }
        ColumnWidth width = ColumnWidth.auto();
        String widthAttr = getAttr(el, ATTR_WIDTH);
        if (widthAttr != null) {
            width = parseColumnWidth(widthAttr);
        }
        return ColumnSpec.of(align, width);
    }

    /** 解析列宽属性值：{@code auto} 或正整数 */
    private ColumnWidth parseColumnWidth(String value) {
        if (value.equalsIgnoreCase("auto")) {
            return ColumnWidth.auto();
        }
        try {
            return ColumnWidth.fixed(Integer.parseInt(value.trim()));
        } catch (IllegalArgumentException e) {
            throw new TemplateParseException(
                    "<" + TAG_COLUMN + "> 属性 " + ATTR_WIDTH + " 非法: " + value + "（可选 auto 或正整数）");
        }
    }

    /**
     * 收集 {@code <meta key="..." value="..."/>} 到元数据 Map。
     * <p>key 必填非空；重复 key 后者覆盖（不报错）；value 缺省为空串。引擎不校验语义。
     */
    private void collectMeta(Element el, Map<String, String> metadata) {
        String key = getAttr(el, ATTR_KEY);
        if (key == null) {
            throw new TemplateParseException(
                    "<" + TAG_META + "> 标签缺少必填属性 " + ATTR_KEY);
        }
        String value = el.getAttribute(ATTR_VALUE);
        metadata.put(key, value == null ? "" : value);
    }

    // ==================== 工具方法 ====================

    private boolean isTextNode(Node node) {
        return node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE;
    }

    private boolean isBlankTextNode(Node node) {
        return node.getNodeType() == Node.TEXT_NODE && node.getTextContent().isBlank();
    }

    /** NodeList 转 List（块级解析基于 List 索引消费 if 链） */
    private List<Node> toList(NodeList children) {
        List<Node> nodes = new ArrayList<>(children.getLength());
        for (int i = 0; i < children.getLength(); i++) {
            nodes.add(children.item(i));
        }
        return nodes;
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

    /** 块级 if 链解析结果 */
    private record BlockIfParseResult(ConditionalBlock node, int nextIndex) {
    }

    /** 行内 if 链解析结果 */
    private record IfParseResult(IfNode node, int nextIndex) {
    }
}
