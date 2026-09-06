package io.github.JiangHu.jframe.content_template.ast;

import java.util.List;

/**
 * 静态行——渲染为单行文本。
 *
 * <p>两种来源（解析后等价）：
 * <pre>{@code
 * <line>§e玩家: §f{{player.name}}</line>   <!-- line 标签 -->
 * §e玩家: §f{{player.name}}                <!-- 行区域裸文本 -->
 * }</pre>
 *
 * <p>行级条件/循环请使用块级 {@link ConditionalBlock} / {@link LoopBlock} 包裹。
 *
 * @param nodes 行内节点列表（文本 + 表达式 + 行内 if/for 嵌套）
 */
public record StaticLine(List<TemplateNode> nodes) implements LineEntry {
}
