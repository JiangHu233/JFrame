package io.github.JiangHu.jframe.content_template.ast;

/**
 * 模板 AST 行内节点基类（sealed）。
 *
 * <p>行内节点渲染为字符串的一部分，可嵌套在 {@code <line>} / {@code <title>} 文本中。
 * 节点本身是纯数据（record），渲染逻辑由 {@code TemplateRenderer} 统一处理。
 *
 * <p>实现类：
 * <ul>
 *   <li>{@link TextNode} — 纯文本，原样输出</li>
 *   <li>{@link ExpressionNode} — {@code {{...}}} 表达式，SpEL 求值后输出</li>
 *   <li>{@link IfNode} — {@code <if>/<elif>/<else>} 条件分支</li>
 *   <li>{@link EachNode} — {@code <each>} 行内循环（拼接成一行）</li>
 * </ul>
 */
public sealed interface TemplateNode permits TextNode, ExpressionNode, IfNode, EachNode {
}
