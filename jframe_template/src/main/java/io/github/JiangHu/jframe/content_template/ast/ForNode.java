package io.github.JiangHu.jframe.content_template.ast;

import java.util.List;

/**
 * 行内循环节点——{@code <for items="..." var="..." index="..." max="n">...</for>} 嵌套在
 * {@code <line>} / {@code <title>} 内时的 AST 节点，遍历列表拼接成<b>一行</b>文本。
 *
 * <p>与块级 {@link LoopBlock} 的区别：
 * <ul>
 *   <li>{@code ForNode} 是<b>行内</b>循环，每元素的渲染结果拼接成一行（如好友列表用逗号分隔）</li>
 *   <li>{@code LoopBlock} 是<b>块级</b>循环，每元素渲染整组独立行</li>
 * </ul>
 *
 * <p>循环变量名可通过 {@code var}/{@code index} 属性自定义（缺省 {@code this}/{@code index}）。
 *
 * <p>对应语法：
 * <pre>{@code
 * <line>好友: <for items="friends" var="f">{{f.name}}, </for></line>
 * }</pre>
 *
 * @param itemsKey     数据列表的键名（SpEL 表达式）
 * @param varName      当前元素变量名，缺省 {@code this}
 * @param indexVarName 当前序号变量名（从 0 开始），缺省 {@code index}
 * @param max          最大迭代数，超出截断不渲染；{@code 0} 或负数表示不限
 * @param bodyNodes    每次迭代渲染的行内子节点列表
 */
public record ForNode(String itemsKey, String varName, String indexVarName, int max,
                      List<TemplateNode> bodyNodes) implements TemplateNode {
}
