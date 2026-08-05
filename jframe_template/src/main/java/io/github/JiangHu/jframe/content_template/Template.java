package io.github.JiangHu.jframe.content_template;

import io.github.JiangHu.jframe.content_template.ast.LineEntry;
import io.github.JiangHu.jframe.content_template.ast.TemplateNode;
import java.util.List;

/**
 * 编译后的模板对象——不可变，可安全缓存和并发渲染。
 *
 * <p>支持两种模板形式：
 * <ul>
 *   <li><b>XML 模板</b>：有标题（{@code titleNodes}）+ 多行结构（{@code lineEntries}），
 *       适用于计分板等需要 title + lines 的场景</li>
 *   <li><b>纯文本模板</b>：只有一段文本（{@code textNodes}），适用于 title、浮空字等单字符串场景</li>
 * </ul>
 *
 * <p>由 {@code TemplateParser} 编译 XML 源码或纯文本生成，由 {@code TemplateRenderer} 渲染。
 *
 * @see io.github.JiangHu.jframe.content_template.ast.TemplateNode
 * @see io.github.JiangHu.jframe.content_template.ast.LineEntry
 */
public class Template {

    /** 标题的行内节点列表，{@code null} 表示无标题（纯文本模板也没有标题） */
    private final List<TemplateNode> titleNodes;

    /** 行级别条目列表（XML 模板），{@code null} 表示纯文本模板 */
    private final List<LineEntry> lineEntries;

    /** 纯文本的行内节点列表（纯文本模板），{@code null} 表示 XML 模板 */
    private final List<TemplateNode> textNodes;

    /** 模板源码（用于缓存 key 和调试） */
    private final String source;

    /** XML 模板构造 */
    public Template(List<TemplateNode> titleNodes, List<LineEntry> lineEntries, String source) {
        this.titleNodes = titleNodes;
        this.lineEntries = lineEntries;
        this.textNodes = null;
        this.source = source;
    }

    /** 纯文本模板构造 */
    public Template(List<TemplateNode> textNodes, String source) {
        this.titleNodes = null;
        this.lineEntries = null;
        this.textNodes = textNodes;
        this.source = source;
    }

    /** 是否为 XML 模板（有 title + lines 结构） */
    public boolean isXmlTemplate() {
        return lineEntries != null;
    }

    /** 标题节点列表（XML 模板），纯文本模板返回 {@code null} */
    public List<TemplateNode> getTitleNodes() {
        return titleNodes;
    }

    /** 行条目列表（XML 模板），纯文本模板返回 {@code null} */
    public List<LineEntry> getLineEntries() {
        return lineEntries;
    }

    /** 纯文本节点列表（纯文本模板），XML 模板返回 {@code null} */
    public List<TemplateNode> getTextNodes() {
        return textNodes;
    }

    /** 模板源码 */
    public String getSource() {
        return source;
    }
}
