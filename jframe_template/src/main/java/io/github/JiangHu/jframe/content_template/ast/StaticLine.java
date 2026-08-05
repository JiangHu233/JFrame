package io.github.JiangHu.jframe.content_template.ast;

import java.util.List;

/**
 * 静态行——{@code <line>} 标签，渲染为单行文本。
 *
 * <p>支持行级条件：
 * <pre>{@code
 * <!-- 无条件：始终显示 -->
 * <line>§e玩家: §f{{player.name}}</line>
 *
 * <!-- 条件行：if 为真才显示 -->
 * <line if="vip">§6⭐ VIP 会员</line>
 *
 * <!-- 条件行带 else：为真/假显示不同文本 -->
 * <line if="vip" else="§7普通玩家">§6⭐ VIP 会员</line>
 * }</pre>
 *
 * @param nodes    行内节点列表（文本 + 表达式 + if/each 嵌套）
 * @param ifCond   行级条件表达式（SpEL），{@code null} 表示无条件
 * @param elseText 条件为假时的替代文本，{@code null} 表示条件为假时整行不显示
 */
public record StaticLine(List<TemplateNode> nodes, String ifCond, String elseText) implements LineEntry {
}
