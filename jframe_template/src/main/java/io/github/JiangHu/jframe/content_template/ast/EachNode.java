package io.github.JiangHu.jframe.content_template.ast;

import java.util.List;

/**
 * 行内循环节点——{@code <each items="key">...</each>}，遍历列表拼接成<b>一行</b>文本。
 *
 * <p>与 {@code <line-each>} 的区别：
 * <ul>
 *   <li>{@code <each>} 是<b>行内</b>循环，结果拼接成一行（如好友列表用空格分隔）</li>
 *   <li>{@code <line-each>} 是<b>动态行展开</b>，每个元素生成一个独立行</li>
 * </ul>
 *
 * <p>循环体内可用 {@code {{this}}}（当前元素）、{@code {{index}}}（当前序号）。
 *
 * @param itemsKey  数据列表的键名（SpEL 表达式，如 {@code "friends"} 或 {@code "player.friends"}）
 * @param bodyNodes 循环体的子节点列表
 */
public record EachNode(String itemsKey, List<TemplateNode> bodyNodes) implements TemplateNode {
}
