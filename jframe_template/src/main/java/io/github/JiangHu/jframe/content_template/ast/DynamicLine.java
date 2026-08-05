package io.github.JiangHu.jframe.content_template.ast;

import java.util.List;

/**
 * 动态行展开——{@code <line-each items="key" max="n">}，遍历列表每个元素生成一个独立行。
 *
 * <p>与 {@code <each>} 的区别：
 * <ul>
 *   <li>{@code <line-each>} 每个元素生成<b>一个独立行</b>（如排行榜每人一行）</li>
 *   <li>{@code <each>} 是<b>行内</b>循环，结果拼接成一行</li>
 * </ul>
 *
 * <p>对应语法：
 * <pre>{@code
 * <line-each items="inventory" max="5">
 *     §f{{index + 1}}. {{this.name}} §7x{{this.count}}
 * </line-each>
 * }</pre>
 *
 * @param bodyNodes 循环体的子节点列表（每个元素渲染为一行）
 * @param itemsKey  数据列表的键名（SpEL 表达式）
 * @param max       最大展开行数，{@code -1} 表示无限制
 */
public record DynamicLine(List<TemplateNode> bodyNodes, String itemsKey, int max) implements LineEntry {
}
