package io.github.JiangHu.jframe.content_template.render;

import io.github.JiangHu.jframe.content_template.IncrementalRenderResult;
import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.content_template.ast.LineEntry;
import io.github.JiangHu.jframe.core.data.reactive.ChangeSet;
import io.github.JiangHu.jframe.core.data.reactive.DataContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 增量渲染器（Layer 2）——基于变量依赖图，只重新渲染受变更影响的行。
 *
 * <p>与 {@link TemplateRenderer}（全量渲染器）的区别：
 * <ul>
 *   <li>{@code TemplateRenderer}：每次渲染遍历整个 AST，生成所有行</li>
 *   <li>{@code IncrementalRenderer}：利用 {@link DependencyExtractor} 构建的依赖图，
 *       只重新渲染依赖了变更变量的行条目，未受影响的行直接复用缓存</li>
 * </ul>
 *
 * <h3>核心算法</h3>
 * <pre>{@code
 * renderIncremental(data, changeSet):
 *   changedKeys = changeSet.changedKeys()
 *   1. 标题：如果 titleDependencies ∩ changedKeys ≠ ∅ → 重新渲染标题
 *   2. 逐行遍历 lineEntries：
 *      a. 如果 entry.deps ∩ changedKeys ≠ ∅ → 重新渲染该条目
 *      b. 如果行数变化（结构变更）→ 回退全量渲染
 *      c. 否则逐行比较，收集变更索引
 *   3. 未受影响的行 → 直接复用缓存（跳过 SpEL 求值）
 * }</pre>
 *
 * <h3>状态管理</h3>
 * <p>本类<b>有状态</b>，每个 {@code ScoreboardView} 应持有独立实例。
 * 内部缓存上次渲染结果（标题 + 行列表 + 每个条目的行数），用于增量比较。
 *
 * <h3>线程安全</h3>
 * <p><b>非线程安全</b>。调用方需自行同步（如 {@code ScoreboardView.refresh()} 已加 {@code synchronized}）。
 *
 * @see DependencyExtractor 变量依赖提取（Layer 1）
 * @see IncrementalRenderResult 增量渲染结果
 */
public class IncrementalRenderer {

    private final TemplateRenderer delegate;
    private final DependencyExtractor extractor;

    // ===== 模板级状态（首次渲染或模板变更时设置）=====

    private Template template;
    private Map<LineEntry, Set<String>> dependencyMap;
    private Set<String> titleDependencies;

    // ===== 上次渲染缓存 =====

    private String lastTitle;
    private List<String> lastLines;
    /** 上次渲染中，每个行条目产生的行数（用于增量遍历时定位行索引） */
    private int[] lastEntryLineCounts;

    /**
     * 使用指定的全量渲染器和依赖提取器构造。
     *
     * @param delegate  全量渲染器（用于首次渲染和结构变更回退）
     * @param extractor 依赖提取器（用于构建依赖图）
     */
    public IncrementalRenderer(TemplateRenderer delegate, DependencyExtractor extractor) {
        this.delegate = delegate;
        this.extractor = extractor;
    }

    /** 无参构造，内部创建默认的 TemplateRenderer 和 DependencyExtractor */
    public IncrementalRenderer() {
        this(new TemplateRenderer(), new DependencyExtractor());
    }

    // ===== 全量渲染（首次 / 模板变更）=====

    /**
     * 全量渲染——渲染整个模板并建立缓存。
     *
     * <p>在以下场景调用：
     * <ul>
     *   <li>首次渲染（show 计分板时）</li>
     *   <li>模板变更（切换计分板模板时）</li>
     *   <li>结构变更回退（DynamicLine 展开数变化时）</li>
     * </ul>
     *
     * @param template 编译后的模板
     * @param data     数据上下文
     * @return 全量渲染结果（标记为结构变更，触发全量输出）
     */
    public IncrementalRenderResult renderFull(Template template, DataContext data) {
        // 设置模板级状态
        this.template = template;
        this.dependencyMap = extractor.buildDependencyMap(template);
        this.titleDependencies = extractor.extractTitleDependencies(template);

        // 渲染
        Map<String, Object> snapshot = data.asMap();
        RenderContext ctx = new RenderContext(snapshot);

        // 标题
        String title = null;
        if (template.getTitleNodes() != null) {
            title = delegate.renderNodes(template.getTitleNodes(), ctx);
        }

        // 逐条目渲染，同时记录每个条目的行数
        List<String> lines = new ArrayList<>();
        List<LineEntry> entries = template.getLineEntries();
        if (entries != null) {
            lastEntryLineCounts = new int[entries.size()];
            int entryIdx = 0;
            for (LineEntry entry : entries) {
                int before = lines.size();
                List<String> entryLines = delegate.renderLineEntry(entry, ctx);
                lines.addAll(entryLines);
                lastEntryLineCounts[entryIdx] = lines.size() - before;
                entryIdx++;
            }
        } else {
            lastEntryLineCounts = new int[0];
        }

        // 更新缓存
        this.lastTitle = title;
        this.lastLines = new ArrayList<>(lines);

        // 全量渲染标记为结构变更（首次渲染需要全量发送）
        return new IncrementalRenderResult(title, lines, Set.of(), false, true);
    }

    // ===== 增量渲染 =====

    /**
     * 增量渲染——根据 {@link ChangeSet} 只重新渲染受影响的行。
     *
     * <p>如果模板尚未设置（未调用过 {@link #renderFull}），自动回退到全量渲染。
     *
     * @param data      数据上下文（已包含变更后的数据）
     * @param changeSet 本次变更集（来自 {@link DataContext#onChange}）
     * @return 增量渲染结果，包含全量行列表 + 变更信息
     */
    public IncrementalRenderResult renderIncremental(DataContext data, ChangeSet changeSet) {
        // 模板未设置 → 全量渲染
        if (template == null) {
            throw new IllegalStateException("尚未调用 renderFull 设置模板，无法增量渲染");
        }

        // 无变更 → 返回空结果
        if (changeSet == null || changeSet.isEmpty()) {
            return new IncrementalRenderResult(lastTitle, lastLines, Set.of(), false, false);
        }

        Set<String> changedKeys = changeSet.changedKeys();
        Map<String, Object> snapshot = data.asMap();
        RenderContext ctx = new RenderContext(snapshot);

        // ===== 1. 标题增量 =====
        String newTitle = lastTitle;
        boolean titleChanged = false;
        if (DependencyExtractor.isAffected(titleDependencies, changedKeys)) {
            newTitle = delegate.renderNodes(template.getTitleNodes(), ctx);
            titleChanged = !Objects.equals(newTitle, lastTitle);
        }

        // ===== 2. 行增量 =====
        List<LineEntry> entries = template.getLineEntries();
        if (entries == null || entries.isEmpty()) {
            // 无行条目（纯文本模板），只更新标题缓存
            lastTitle = newTitle;
            return new IncrementalRenderResult(newTitle, List.of(), Set.of(), titleChanged, false);
        }

        // 复制上次行列表（增量修改副本）
        List<String> newLines = new ArrayList<>(lastLines);
        Set<Integer> changedIndices = new LinkedHashSet<>();
        int lineIndex = 0;
        boolean structureChanged = false;

        for (int entryIdx = 0; entryIdx < entries.size(); entryIdx++) {
            LineEntry entry = entries.get(entryIdx);
            Set<String> deps = dependencyMap.get(entry);
            int oldCount = lastEntryLineCounts[entryIdx];

            if (DependencyExtractor.isAffected(deps, changedKeys)) {
                // 受影响 → 重新渲染该条目
                List<String> entryLines = delegate.renderLineEntry(entry, ctx);
                int newCount = entryLines.size();

                if (newCount != oldCount) {
                    // 行数变化 → 结构变更，回退全量
                    structureChanged = true;
                    break;
                }

                // 行数不变 → 逐行比较，收集变更
                for (int i = 0; i < newCount; i++) {
                    int idx = lineIndex + i;
                    String oldLine = lastLines.get(idx);
                    String newLine = entryLines.get(i);
                    if (!Objects.equals(oldLine, newLine)) {
                        newLines.set(idx, newLine);
                        changedIndices.add(idx);
                    }
                }
            }

            lineIndex += oldCount;
        }

        // ===== 3. 结构变更回退 =====
        if (structureChanged) {
            return renderFull(template, data);
        }

        // ===== 4. 更新缓存并返回 =====
        lastTitle = newTitle;
        lastLines = newLines;

        return new IncrementalRenderResult(newTitle, newLines, changedIndices, titleChanged, false);
    }

    // ===== 状态查询 =====

    /** 是否已初始化（已调用 renderFull 设置模板） */
    public boolean isInitialized() {
        return template != null;
    }

    /** 当前绑定的模板 */
    public Template getTemplate() {
        return template;
    }

    /** 上次渲染的标题缓存 */
    public String getLastTitle() {
        return lastTitle;
    }

    /** 上次渲染的行列表缓存 */
    public List<String> getLastLines() {
        return lastLines != null ? List.copyOf(lastLines) : List.of();
    }

    /** 重置状态（清除模板绑定和缓存） */
    public void reset() {
        template = null;
        dependencyMap = null;
        titleDependencies = null;
        lastTitle = null;
        lastLines = null;
        lastEntryLineCounts = null;
    }
}
