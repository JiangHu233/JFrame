package io.github.JiangHu.jframe.content_template.ast;

import java.util.List;

/**
 * 块级循环——{@code <for items="..." var="..." index="..." max="n">...</for>} 出现在行区域时的 AST 节点。
 *
 * <p>遍历列表，<b>每个元素渲染整组子条目</b>（子条目可包含 line / if / 嵌套 for / 裸文本行）。
 * 与行内 {@link ForNode} 的区别：ForNode 每元素的渲染结果拼接为<b>一行</b>。
 *
 * <p>循环变量名可通过 {@code var}/{@code index} 属性自定义（缺省 {@code this}/{@code index}），
 * 嵌套 for 使用不同变量名即可互访；同名时内层遮蔽外层。
 *
 * <p>对应语法：
 * <pre>{@code
 * <for items="teams" var="team" index="ti" max="10">
 *   §e[{{ti + 1}}] {{team.name}}
 *   <for items="team.members" var="m">
 *     <line>§f  {{m.name}}</line>
 *   </for>
 * </for>
 * }</pre>
 *
 * @param itemsKey    数据列表的键名（SpEL 表达式）
 * @param varName     当前元素变量名，缺省 {@code this}
 * @param indexVarName 当前序号变量名（从 0 开始），缺省 {@code index}
 * @param max         最大迭代数，超出截断不渲染；{@code 0} 或负数表示不限
 * @param bodyEntries 每次迭代渲染的子条目列表
 */
public record LoopBlock(String itemsKey, String varName, String indexVarName, int max,
                        List<LineEntry> bodyEntries) implements LineEntry {
}
