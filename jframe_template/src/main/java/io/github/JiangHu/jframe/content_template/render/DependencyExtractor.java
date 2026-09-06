package io.github.JiangHu.jframe.content_template.render;

import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.content_template.ast.ConditionalBlock;
import io.github.JiangHu.jframe.content_template.ast.ExpressionNode;
import io.github.JiangHu.jframe.content_template.ast.ForNode;
import io.github.JiangHu.jframe.content_template.ast.IfNode;
import io.github.JiangHu.jframe.content_template.ast.LineEntry;
import io.github.JiangHu.jframe.content_template.ast.LoopBlock;
import io.github.JiangHu.jframe.content_template.ast.StaticLine;
import io.github.JiangHu.jframe.content_template.ast.TemplateNode;
import io.github.JiangHu.jframe.content_template.ast.TextNode;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.ast.PropertyOrFieldReference;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * 变量依赖提取器（Layer 1）——分析模板 AST，提取每个行条目依赖的变量集合。
 *
 * <p>这是增量更新的基础：编译期构建 {@code 行条目 → 变量集合} 映射，
 * 运行时根据 {@code ChangeSet.changedKeys()} 快速判断哪些行需要重新渲染。
 *
 * <h3>提取原理</h3>
 * <p>使用 SpEL 自带的 AST 解析器（{@link SpelExpressionParser}）解析表达式字符串，
 * 遍历 AST 树收集所有 {@link PropertyOrFieldReference} 节点的名称。
 *
 * <p>示例：
 * <pre>{@code
 * 表达式: "player.name"
 * SpEL AST: PropertyOrFieldReference("name") ← 子节点
 *           └─ PropertyOrFieldReference("player") ← 根变量
 * 提取结果: {player, name}
 * }</pre>
 *
 * <p>保守策略：提取所有属性引用名称（包括嵌套路径的每一段）。
 * 过度估计依赖只会导致少量不必要的重渲染，不会漏掉需要更新的行。
 *
 * <h3>无法解析的表达式</h3>
 * <p>如果 SpEL 解析失败（非法语法），返回 {@link #WILDCARD}（通配符），
 * 表示该行依赖所有变量——任何变更都会触发重渲染。
 *
 * <h3>SpEL 关键字过滤</h3>
 * <p>SpEL AST 天然区分了不同节点类型：
 * <ul>
 *   <li>{@code true}/{@code false}/{@code null} → {@code Literal} 节点，不会被收集</li>
 *   <li>{@code T(Math)} → {@code TypeReference} 节点，不会被收集</li>
 *   <li>{@code and}/{@code or} → 运算符节点，不会被收集</li>
 *   <li>只有真正的属性/字段引用才会被收集</li>
 * </ul>
 */
public class DependencyExtractor {

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    /** 通配符：表示依赖所有变量（用于无法解析的表达式） */
    public static final Set<String> WILDCARD = Collections.unmodifiableSet(new HashSet<>(Set.of("*")));

    /** 空依赖集：纯文本行（无任何表达式） */
    public static final Set<String> EMPTY = Collections.unmodifiableSet(new HashSet<>());

    // ===== 行内节点依赖提取 =====

    /**
     * 从行内节点列表提取变量依赖。
     *
     * @param nodes 行内节点列表
     * @return 依赖的变量名集合
     */
    public Set<String> extractFromNodes(java.util.List<TemplateNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return EMPTY;
        }
        Set<String> deps = new HashSet<>();
        for (TemplateNode node : nodes) {
            extractFromNode(node, deps);
        }
        return Collections.unmodifiableSet(deps);
    }

    /**
     * 从单个行内节点提取依赖（递归处理嵌套的 if/for）。
     */
    private void extractFromNode(TemplateNode node, Set<String> deps) {
        switch (node) {
            case TextNode ignored -> {
                // 纯文本节点无依赖
            }
            case ExpressionNode en -> extractFromSpelExpression(en.expression(), deps);
            case IfNode ifn -> {
                for (IfNode.Branch branch : ifn.branches()) {
                    extractFromSpelExpression(branch.condition(), deps);
                    extractFromNodesInto(branch.nodes(), deps);
                }
                if (ifn.elseNodes() != null) {
                    extractFromNodesInto(ifn.elseNodes(), deps);
                }
            }
            case ForNode fn -> {
                extractFromSpelExpression(fn.itemsKey(), deps);
                extractFromNodesInto(fn.bodyNodes(), deps);
            }
        }
    }

    /**
     * 从节点列表提取依赖，追加到已有集合（内部方法，避免创建中间集合）。
     */
    private void extractFromNodesInto(java.util.List<TemplateNode> nodes, Set<String> deps) {
        if (nodes == null) {
            return;
        }
        for (TemplateNode node : nodes) {
            extractFromNode(node, deps);
        }
    }

    // ===== SpEL 表达式依赖提取 =====

    /**
     * 从 SpEL 表达式字符串提取变量依赖。
     *
     * <p>解析表达式为 SpEL AST，遍历收集所有 {@link PropertyOrFieldReference} 节点名称。
     * 解析失败时返回 {@link #WILDCARD}（保守策略）。
     *
     * @param expression SpEL 表达式字符串
     * @param deps       依赖收集目标集合
     */
    void extractFromSpelExpression(String expression, Set<String> deps) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        try {
            Expression parsed = PARSER.parseExpression(expression);
            if (parsed instanceof SpelExpression spel) {
                collectPropertyReferences(spel.getAST(), deps);
            }
        } catch (Exception e) {
            // 解析失败：保守标记为依赖所有变量
            deps.add("*");
        }
    }

    /**
     * 递归遍历 SpEL AST，收集所有 PropertyOrFieldReference 节点名称。
     *
     * @param node SpEL AST 节点
     * @param deps 依赖收集目标集合
     */
    private void collectPropertyReferences(SpelNode node, Set<String> deps) {
        if (node == null) {
            return;
        }
        if (node instanceof PropertyOrFieldReference pfr) {
            deps.add(pfr.getName());
        }
        // 递归遍历子节点
        for (int i = 0; i < node.getChildCount(); i++) {
            collectPropertyReferences(node.getChild(i), deps);
        }
    }

    // ===== 行条目依赖提取 =====

    /**
     * 从行条目（StaticLine / ConditionalBlock / LoopBlock）提取变量依赖。
     *
     * <p>对于 {@link StaticLine}：提取行内节点的依赖。
     * <br>对于 {@link ConditionalBlock}：提取所有分支条件 + 所有子条目的依赖（保守并集，
     * 因为分支选择依赖运行时数据）。
     * <br>对于 {@link LoopBlock}：提取 {@code itemsKey} 列表键 + 循环体子条目的依赖
     * （列表本身变化会影响展开行数）。
     *
     * @param entry 行条目
     * @return 依赖的变量名集合
     */
    public Set<String> extractFromLineEntry(LineEntry entry) {
        Set<String> deps = new HashSet<>();
        extractFromLineEntryInto(entry, deps);
        return Collections.unmodifiableSet(deps);
    }

    /**
     * 从行条目提取依赖，追加到已有集合（递归处理块级嵌套）。
     */
    private void extractFromLineEntryInto(LineEntry entry, Set<String> deps) {
        switch (entry) {
            case StaticLine sl -> extractFromNodesInto(sl.nodes(), deps);
            case ConditionalBlock cb -> {
                for (ConditionalBlock.Branch branch : cb.branches()) {
                    extractFromSpelExpression(branch.condition(), deps);
                    for (LineEntry sub : branch.entries()) {
                        extractFromLineEntryInto(sub, deps);
                    }
                }
                if (cb.elseEntries() != null) {
                    for (LineEntry sub : cb.elseEntries()) {
                        extractFromLineEntryInto(sub, deps);
                    }
                }
            }
            case LoopBlock lb -> {
                extractFromSpelExpression(lb.itemsKey(), deps);
                for (LineEntry sub : lb.bodyEntries()) {
                    extractFromLineEntryInto(sub, deps);
                }
            }
        }
    }

    // ===== 全模板依赖图 =====

    /**
     * 为整个模板构建依赖图：{@code 行条目 → 变量集合}。
     *
     * <p>依赖图在模板编译后构建一次，后续增量渲染时复用。
     *
     * @param template 编译后的模板
     * @return 行条目到依赖集合的映射（保持插入顺序）
     */
    public Map<LineEntry, Set<String>> buildDependencyMap(Template template) {
        if (template == null || template.getLineEntries() == null) {
            return Collections.emptyMap();
        }
        Map<LineEntry, Set<String>> map = new LinkedHashMap<>();
        for (LineEntry entry : template.getLineEntries()) {
            map.put(entry, extractFromLineEntry(entry));
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * 提取标题节点的变量依赖。
     *
     * @param template 编译后的模板
     * @return 标题依赖的变量名集合
     */
    public Set<String> extractTitleDependencies(Template template) {
        if (template == null || template.getTitleNodes() == null) {
            return EMPTY;
        }
        return extractFromNodes(template.getTitleNodes());
    }

    // ===== 依赖匹配 =====

    /**
     * 判断依赖集合是否受到变更键的影响。
     *
     * <p>匹配规则：
     * <ol>
     *   <li>如果依赖集合包含通配符 {@code "*"}，总是返回 {@code true}</li>
     *   <li>如果变更键集合与依赖集合有交集，返回 {@code true}</li>
     *   <li>否则返回 {@code false}</li>
     * </ol>
     *
     * @param dependencies 行的依赖集合
     * @param changedKeys  本次变更的键集合（来自 {@link io.github.JiangHu.jframe.core.data.reactive.ChangeSet}）
     * @return 是否受到影响
     */
    public static boolean isAffected(Set<String> dependencies, Set<String> changedKeys) {
        if (dependencies == null || dependencies.isEmpty() || changedKeys == null || changedKeys.isEmpty()) {
            return false;
        }
        // 通配符：总是受影响
        if (dependencies.contains("*")) {
            return true;
        }
        // 交集检查
        for (String key : changedKeys) {
            if (dependencies.contains(key)) {
                return true;
            }
        }
        return false;
    }
}
