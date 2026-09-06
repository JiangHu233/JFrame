package io.github.JiangHu.jframe.content_template.ast;

/**
 * 表达式节点——{@code {{...}}} 内的表达式，由 SpEL 求值后输出。
 *
 * <p>统一处理变量替换（{@code {{serverName}}}）、嵌套属性（{@code {{player.name}}}）、
 * 算术（{@code {{exp * 100 / maxExp}}}）、比较（{@code {{level >= 50}}}）、
 * 三元（{@code {{hp > 0 ? '存活' : '阵亡'}}}）等所有表达式形式。
 *
 * @param expression SpEL 表达式字符串（不含 {@code {{ }}} 定界符）
 */
public record ExpressionNode(String expression) implements TemplateNode {
}
