package io.github.JiangHu.jframe.content_template.ast;

import java.util.List;

/**
 * 条件分支节点——{@code <if cond="">...<elif cond="">...<else>...</else></if>}。
 *
 * <p>渲染时依次检查每个分支的 condition（SpEL 求值为 Boolean），
 * 渲染首个为真的分支；全假时渲染 else 分支（若存在）。
 *
 * <p>对应语法：
 * <pre>{@code
 * <if cond="level >= 100">§c[大师]
 *   <elif cond="level >= 50">§e[高手]
 *   <elif cond="level >= 10">§a[进阶]
 *   <else>§7[新手]
 * </if>
 * }</pre>
 *
 * @param branches  条件分支列表（if + 所有 elif），按顺序检查
 * @param elseNodes else 分支的子节点列表，无 else 时为空列表
 */
public record IfNode(List<Branch> branches, List<TemplateNode> elseNodes) implements TemplateNode {

    /**
     * 单个条件分支（if 或 elif）。
     *
     * @param condition SpEL 条件表达式
     * @param nodes     条件为真时渲染的子节点列表
     */
    public record Branch(String condition, List<TemplateNode> nodes) {
    }
}
