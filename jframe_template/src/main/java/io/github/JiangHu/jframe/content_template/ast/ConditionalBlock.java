package io.github.JiangHu.jframe.content_template.ast;

import java.util.List;

/**
 * 块级条件分支——{@code <if cond="">...<elif cond="">...<else>...</else></if>} 出现在行区域时的 AST 节点。
 *
 * <p>与行内 {@link IfNode} 的区别：
 * <ul>
 *   <li>{@code ConditionalBlock} 是<b>行级别条目</b>，分支内容为 {@link LineEntry} 列表，
 *       控制一整组行的显隐（可包含 line / for / 嵌套 if / 裸文本行）</li>
 *   <li>{@code IfNode} 是<b>行内节点</b>，输出单行文本的一部分</li>
 * </ul>
 *
 * <p>渲染时依次检查每个分支的 condition（SpEL 求值为 Boolean），
 * 渲染首个为真分支的全部子条目；全假时渲染 else 分支（若存在）；全假无 else 输出 0 行。
 *
 * <p>对应语法：
 * <pre>{@code
 * <if cond="player.vip">
 *   §6⭐ VIP 专区
 *   <line>§e到期: {{player.vipExpire}}</line>
 * </if>
 * <elif cond="player.level > 50">
 *   <line>§7高级玩家</line>
 * </elif>
 * <else>
 *   <line>§8普通玩家</line>
 * </else>
 * }</pre>
 *
 * @param branches    条件分支列表（if + 所有 elif），按顺序检查
 * @param elseEntries else 分支的子条目列表，无 else 时为 null
 */
public record ConditionalBlock(List<Branch> branches, List<LineEntry> elseEntries) implements LineEntry {

    /**
     * 单个条件分支（if 或 elif）。
     *
     * @param condition SpEL 条件表达式
     * @param entries   条件为真时渲染的子条目列表
     */
    public record Branch(String condition, List<LineEntry> entries) {
    }
}
