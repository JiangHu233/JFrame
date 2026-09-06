package io.github.JiangHu.jframe.content_template.ast;

import java.util.List;

/**
 * 行级别模板条目（sealed）——模板行区域的结构描述。
 *
 * <p>三种实现：
 * <ul>
 *   <li>{@link StaticLine} — {@code <line>} 或裸文本，渲染为单行文本</li>
 *   <li>{@link ConditionalBlock} — 块级 {@code <if>/<elif>/<else>} 条件，控制一组行的显隐</li>
 *   <li>{@link LoopBlock} — 块级 {@code <for>} 循环，每个列表元素渲染整组子条目</li>
 * </ul>
 *
 * @see TemplateNode 行内节点（渲染为字符串的一部分）
 */
public sealed interface LineEntry permits StaticLine, ConditionalBlock, LoopBlock {
}
