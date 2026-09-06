package io.github.JiangHu.jframe.content_template.render;

import io.github.JiangHu.jframe.content_template.RenderResult;
import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.content_template.ast.ConditionalBlock;
import io.github.JiangHu.jframe.content_template.column.ColumnAligner;
import io.github.JiangHu.jframe.content_template.ast.ExpressionNode;
import io.github.JiangHu.jframe.content_template.ast.ForNode;
import io.github.JiangHu.jframe.content_template.ast.IfNode;
import io.github.JiangHu.jframe.content_template.ast.LineEntry;
import io.github.JiangHu.jframe.content_template.ast.LoopBlock;
import io.github.JiangHu.jframe.content_template.ast.StaticLine;
import io.github.JiangHu.jframe.content_template.ast.TemplateNode;
import io.github.JiangHu.jframe.content_template.ast.TextNode;
import io.github.JiangHu.jframe.core.data.reactive.DataContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模板渲染器——将编译后的 {@link Template}（AST）与 {@link DataContext}（数据）结合，
 * 通过 SpEL 求值生成 {@link RenderResult}。
 *
 * <p>渲染流程（{@link #render}）：
 * <ol>
 *   <li>从 DataContext 取数据快照，构建 {@link RenderContext}</li>
 *   <li>渲染标题节点列表 → 标题字符串</li>
 *   <li>遍历行条目（StaticLine / ConditionalBlock / LoopBlock）→ 行列表</li>
 *   <li>列格式启用时，行列表经 {@link ColumnAligner} 列化对齐（产出结果前的最后阶段）</li>
 *   <li>封装为 RenderResult 返回</li>
 * </ol>
 *
 * <p>行条目渲染（{@link #renderLineEntry}）递归处理三种条目：
 * <ul>
 *   <li>{@link StaticLine} → 渲染行内节点为一行</li>
 *   <li>{@link ConditionalBlock} → 遍历分支取首个为真者，递归渲染其子条目；全假走 else</li>
 *   <li>{@link LoopBlock} → 遍历列表，每元素注入 var/index 变量后递归渲染整组子条目，受 max 限制</li>
 * </ul>
 *
 * <p>行内节点渲染（{@link #renderNodes}）使用 switch 模式匹配处理四种节点：
 * <ul>
 *   <li>{@link TextNode} → 原样输出</li>
 *   <li>{@link ExpressionNode} → SpEL 求值后 toString</li>
 *   <li>{@link IfNode} → 遍历分支取首个为真者，否则走 else 分支</li>
 *   <li>{@link ForNode} → 遍历列表，每元素注入 var/index 变量后渲染拼接（受 max 限制）</li>
 * </ul>
 *
 * <p>该类无状态、线程安全，可作为单例使用。
 */
public class TemplateRenderer {

    /**
     * 渲染 XML 模板（有 title + lines 结构）。
     *
     * @param template 编译后的模板
     * @param data     数据上下文
     * @return 渲染结果（标题 + 行列表）
     */
    public RenderResult render(Template template, DataContext data) {
        Map<String, Object> snapshot = data.asMap();
        RenderContext ctx = new RenderContext(snapshot);

        String title = null;
        if (template.getTitleNodes() != null) {
            title = renderNodes(template.getTitleNodes(), ctx);
        }

        List<String> lines = new ArrayList<>();
        if (template.getLineEntries() != null) {
            for (LineEntry entry : template.getLineEntries()) {
                renderLineEntry(entry, ctx, lines);
            }
        }
        // 列格式：启用时作为产出结果前的最后阶段（标题不参与列化；禁用时行为与扩展前一致）
        if (template.isColumnLayoutEnabled()) {
            lines = new ArrayList<>(ColumnAligner.align(lines, template.getColumnLayout()));
        }
        return new RenderResult(title, lines);
    }

    /**
     * 渲染纯文本模板（无 title/lines 结构，输出单个字符串）。
     *
     * @param template 编译后的纯文本模板
     * @param data     数据上下文
     * @return 渲染后的文本
     */
    public String renderText(Template template, DataContext data) {
        if (template.getTextNodes() == null) {
            throw new IllegalArgumentException("模板不是纯文本模板，无法调用 renderText");
        }
        Map<String, Object> snapshot = data.asMap();
        RenderContext ctx = new RenderContext(snapshot);
        return renderNodes(template.getTextNodes(), ctx);
    }

    /**
     * 渲染单个行条目，将结果追加到 lines 列表（递归入口）。
     * <p>每行渲染后调用 {@link String#strip()} 去除首尾空白（换行/缩进），
     * 避免多行写法的元素内容前后换行符导致基岩版 sidebar 显示异常。
     * <ul>
     *   <li>{@link StaticLine}：渲染行内节点为一行</li>
     *   <li>{@link ConditionalBlock}：选中首个为真的分支后递归渲染其子条目（0~N 行）</li>
     *   <li>{@link LoopBlock}：遍历 itemsKey 列表，每元素注入 var/index 变量后
     *       递归渲染整组子条目（0~N×M 行），受 max 限制</li>
     * </ul>
     */
    private void renderLineEntry(LineEntry entry, RenderContext ctx, List<String> lines) {
        switch (entry) {
            case StaticLine sl -> lines.add(renderNodes(sl.nodes(), ctx).strip());
            case ConditionalBlock cb -> {
                for (ConditionalBlock.Branch branch : cb.branches()) {
                    if (ctx.evaluateBoolean(branch.condition())) {
                        for (LineEntry sub : branch.entries()) {
                            renderLineEntry(sub, ctx, lines);
                        }
                        return;
                    }
                }
                if (cb.elseEntries() != null) {
                    for (LineEntry sub : cb.elseEntries()) {
                        renderLineEntry(sub, ctx, lines);
                    }
                }
            }
            case LoopBlock lb -> {
                Iterable<?> items = ctx.evaluateIterable(lb.itemsKey());
                int count = 0;
                for (Object item : items) {
                    if (lb.max() > 0 && count >= lb.max()) {
                        break;
                    }
                    RenderContext itemCtx = ctx.withLoopVariable(lb.varName(), item, lb.indexVarName(), count);
                    for (LineEntry sub : lb.bodyEntries()) {
                        renderLineEntry(sub, itemCtx, lines);
                    }
                    count++;
                }
            }
        }
    }

    /**
     * 渲染单个行条目，返回该条目产生的所有行（不追加到外部列表）。
     *
     * <p>用于增量渲染：只需重新渲染受影响的行条目，而非整个模板。
     *
     * @param entry 行条目（StaticLine / ConditionalBlock / LoopBlock）
     * @param ctx   渲染上下文
     * @return 该条目渲染后的行列表（可能为 0 行、1 行或多行）
     */
    public List<String> renderLineEntry(LineEntry entry, RenderContext ctx) {
        List<String> result = new ArrayList<>();
        renderLineEntry(entry, ctx, result);
        return result;
    }

    /**
     * 渲染行内节点列表为单个字符串。
     *
     * @param nodes 节点列表
     * @param ctx   渲染上下文
     * @return 拼接后的字符串
     */
    public String renderNodes(List<TemplateNode> nodes, RenderContext ctx) {
        StringBuilder sb = new StringBuilder();
        for (TemplateNode node : nodes) {
            sb.append(renderNode(node, ctx));
        }
        return sb.toString();
    }

    /**
     * 渲染单个行内节点。
     */
    private String renderNode(TemplateNode node, RenderContext ctx) {
        return switch (node) {
            case TextNode tn -> tn.text();
            case ExpressionNode en -> ctx.evaluateString(en.expression());
            case IfNode ifn -> renderIfNode(ifn, ctx);
            case ForNode fn -> renderForNode(fn, ctx);
        };
    }

    /**
     * 渲染行内条件节点：遍历分支取首个为真者，否则走 else 分支。
     */
    private String renderIfNode(IfNode node, RenderContext ctx) {
        for (IfNode.Branch branch : node.branches()) {
            if (ctx.evaluateBoolean(branch.condition())) {
                return renderNodes(branch.nodes(), ctx);
            }
        }
        if (node.elseNodes() != null) {
            return renderNodes(node.elseNodes(), ctx);
        }
        return "";
    }

    /**
     * 渲染行内循环节点：遍历列表，每元素注入 var/index 变量后渲染拼接，受 max 限制。
     */
    private String renderForNode(ForNode node, RenderContext ctx) {
        StringBuilder sb = new StringBuilder();
        Iterable<?> items = ctx.evaluateIterable(node.itemsKey());
        int count = 0;
        for (Object item : items) {
            if (node.max() > 0 && count >= node.max()) {
                break;
            }
            RenderContext itemCtx = ctx.withLoopVariable(node.varName(), item, node.indexVarName(), count);
            sb.append(renderNodes(node.bodyNodes(), itemCtx));
            count++;
        }
        return sb.toString();
    }
}
