package io.github.JiangHu.jframe.content_template.render;

import io.github.JiangHu.jframe.content_template.RenderResult;
import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.content_template.ast.DynamicLine;
import io.github.JiangHu.jframe.content_template.ast.EachNode;
import io.github.JiangHu.jframe.content_template.ast.ExpressionNode;
import io.github.JiangHu.jframe.content_template.ast.IfNode;
import io.github.JiangHu.jframe.content_template.ast.LineEntry;
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
 *   <li>遍历行条目（StaticLine / DynamicLine）→ 行列表</li>
 *   <li>封装为 RenderResult 返回</li>
 * </ol>
 *
 * <p>行内节点渲染（{@link #renderNodes}）使用 switch 模式匹配处理四种节点：
 * <ul>
 *   <li>{@link TextNode} → 原样输出</li>
 *   <li>{@link ExpressionNode} → SpEL 求值后 toString</li>
 *   <li>{@link IfNode} → 遍历分支取首个为真者，否则走 else 分支</li>
 *   <li>{@link EachNode} → 遍历列表，每元素注入 this/index 后渲染拼接</li>
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
     * 渲染单个行条目，将结果追加到 lines 列表。
     * <ul>
     *   <li>{@link StaticLine}：检查 ifCond，为真渲染节点为一行，为假输出 elseText（若配置）</li>
     *   <li>{@link DynamicLine}：遍历 itemsKey 列表，每元素渲染为一行，受 max 限制</li>
     * </ul>
     */
    private void renderLineEntry(LineEntry entry, RenderContext ctx, List<String> lines) {
        switch (entry) {
            case StaticLine sl -> {
                if (sl.ifCond() != null && !sl.ifCond().isBlank()) {
                    if (ctx.evaluateBoolean(sl.ifCond())) {
                        lines.add(renderNodes(sl.nodes(), ctx));
                    } else if (sl.elseText() != null) {
                        lines.add(sl.elseText());
                    }
                } else {
                    lines.add(renderNodes(sl.nodes(), ctx));
                }
            }
            case DynamicLine dl -> {
                Iterable<?> items = ctx.evaluateIterable(dl.itemsKey());
                int count = 0;
                for (Object item : items) {
                    if (dl.max() > 0 && count >= dl.max()) {
                        break;
                    }
                    RenderContext itemCtx = ctx.withLoopVariable(item, count);
                    lines.add(renderNodes(dl.bodyNodes(), itemCtx));
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
     * @param entry 行条目（StaticLine / DynamicLine）
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
            case EachNode en -> renderEachNode(en, ctx);
        };
    }

    /**
     * 渲染条件节点：遍历分支取首个为真者，否则走 else 分支。
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
     * 渲染循环节点：遍历列表，每元素注入 this/index 后渲染拼接。
     */
    private String renderEachNode(EachNode node, RenderContext ctx) {
        StringBuilder sb = new StringBuilder();
        Iterable<?> items = ctx.evaluateIterable(node.itemsKey());
        int count = 0;
        for (Object item : items) {
            RenderContext itemCtx = ctx.withLoopVariable(item, count);
            sb.append(renderNodes(node.bodyNodes(), itemCtx));
            count++;
        }
        return sb.toString();
    }
}
