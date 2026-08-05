package io.github.JiangHu.jframe.content_template.ast;

import java.util.List;

/**
 * 行级别模板条目（sealed）——模板中每行的结构描述。
 *
 * <p>两种实现：
 * <ul>
 *   <li>{@link StaticLine} — {@code <line>} 静态行（可带 {@code if}/{@code else} 属性）</li>
 *   <li>{@link DynamicLine} — {@code <line-each>} 动态行展开（每个列表元素生成一行）</li>
 * </ul>
 *
 * @see TemplateNode 行内节点（渲染为字符串的一部分）
 */
public sealed interface LineEntry permits StaticLine, DynamicLine {
}
