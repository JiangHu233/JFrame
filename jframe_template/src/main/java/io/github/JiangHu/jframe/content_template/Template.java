package io.github.JiangHu.jframe.content_template;

import io.github.JiangHu.jframe.content_template.ast.LineEntry;
import io.github.JiangHu.jframe.content_template.ast.TemplateNode;
import io.github.JiangHu.jframe.content_template.column.ColumnLayout;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * <p><b>引擎扩展（列格式 + 元数据）</b>：
 * <ul>
 *   <li>{@code columnLayout}：列格式配置，默认 {@link ColumnLayout#DISABLED}（未启用列化，
 *       渲染行为与扩展前完全一致）</li>
 *   <li>{@code metadata}：{@code <meta>} 标签编译期收集的不可变键值对，默认空 Map；
 *       引擎不校验其语义，仅供业务模块读取</li>
 * </ul>
 *
 * @see io.github.JiangHu.jframe.content_template.ast.TemplateNode
 * @see io.github.JiangHu.jframe.content_template.ast.LineEntry
 * @see io.github.JiangHu.jframe.content_template.column.ColumnLayout
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

    /** 列格式配置（不可变），默认 {@link ColumnLayout#DISABLED} 表示未启用列化 */
    private final ColumnLayout columnLayout;

    /** 元数据键值对（不可变，编译期由 {@code <meta>} 标签收集），默认空 Map */
    private final Map<String, String> metadata;

    /** XML 模板构造（列格式禁用、元数据为空——向后兼容入口） */
    public Template(List<TemplateNode> titleNodes, List<LineEntry> lineEntries, String source) {
        this(titleNodes, lineEntries, source, ColumnLayout.DISABLED, Collections.emptyMap());
    }

    /** 纯文本模板构造（列格式禁用、元数据为空——向后兼容入口） */
    public Template(List<TemplateNode> textNodes, String source) {
        this(textNodes, source, ColumnLayout.DISABLED, Collections.emptyMap());
    }

    /** XML 模板全参构造（含列格式与元数据） */
    public Template(List<TemplateNode> titleNodes, List<LineEntry> lineEntries, String source,
                    ColumnLayout columnLayout, Map<String, String> metadata) {
        this.titleNodes = titleNodes;
        this.lineEntries = lineEntries;
        this.textNodes = null;
        this.source = source;
        this.columnLayout = columnLayout == null ? ColumnLayout.DISABLED : columnLayout;
        this.metadata = metadata == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /** 纯文本模板全参构造（含列格式与元数据；纯文本模板通常不启用列化） */
    public Template(List<TemplateNode> textNodes, String source,
                    ColumnLayout columnLayout, Map<String, String> metadata) {
        this.titleNodes = null;
        this.lineEntries = null;
        this.textNodes = textNodes;
        this.source = source;
        this.columnLayout = columnLayout == null ? ColumnLayout.DISABLED : columnLayout;
        this.metadata = metadata == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
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

    /** 列格式配置（不可变），未启用时为 {@link ColumnLayout#DISABLED} */
    public ColumnLayout getColumnLayout() {
        return columnLayout;
    }

    /** 是否启用列格式（启用后渲染产物按分隔符对齐） */
    public boolean isColumnLayoutEnabled() {
        return columnLayout.isEnabled();
    }

    /** 元数据键值对（不可变；无 {@code <meta>} 标签时为空 Map） */
    public Map<String, String> getMetadata() {
        return metadata;
    }

    /** 按 key 读取元数据值，不存在时返回 {@link Optional#empty()} */
    public Optional<String> getMetadata(String key) {
        return Optional.ofNullable(metadata.get(key));
    }
}
