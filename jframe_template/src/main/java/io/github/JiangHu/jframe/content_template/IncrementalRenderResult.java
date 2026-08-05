package io.github.JiangHu.jframe.content_template;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 增量渲染结果——携带全量行列表 + 变更信息，供 {@code ScoreboardView} 做选择性输出。
 *
 * <p>与 {@link RenderResult} 的区别：
 * <ul>
 *   <li>{@link RenderResult} 只有 {@code title + lines}，调用方必须全量发送</li>
 *   <li>{@code IncrementalRenderResult} 额外携带 {@code changedLineIndices}、
 *       {@code titleChanged}、{@code structureChanged}，调用方可据此做增量输出</li>
 * </ul>
 *
 * <h3>变更类型</h3>
 * <ul>
 *   <li><b>内容变更</b>（{@code changedLineIndices} 非空）：某些行的文本变了，行数不变</li>
 *   <li><b>结构变更</b>（{@code structureChanged = true}）：行数变化（DynamicLine 展开数变化、
 *       StaticLine 条件显隐切换），必须全量重建</li>
 *   <li><b>标题变更</b>（{@code titleChanged = true}）：标题文本变化</li>
 *   <li><b>无变更</b>（{@code hasChanges() = false}）：可完全跳过，不发送任何数据包</li>
 * </ul>
 *
 * @see io.github.JiangHu.jframe.content_template.render.IncrementalRenderer
 */
public class IncrementalRenderResult {

    private final String title;
    private final List<String> allLines;
    private final Set<Integer> changedLineIndices;
    private final boolean titleChanged;
    private final boolean structureChanged;

    /**
     * @param title              渲染后的标题
     * @param allLines           全量行列表（始终包含所有行，用于全量回退）
     * @param changedLineIndices 内容发生变化的行索引集合（结构变更时为空集，因为需要全量重建）
     * @param titleChanged       标题是否变化
     * @param structureChanged   行结构是否变化（行数变化）
     */
    public IncrementalRenderResult(String title, List<String> allLines,
                                   Set<Integer> changedLineIndices,
                                   boolean titleChanged, boolean structureChanged) {
        this.title = title;
        this.allLines = allLines != null ? Collections.unmodifiableList(allLines) : List.of();
        this.changedLineIndices = changedLineIndices != null
                ? Collections.unmodifiableSet(changedLineIndices) : Set.of();
        this.titleChanged = titleChanged;
        this.structureChanged = structureChanged;
    }

    /** 渲染后的标题 */
    public String getTitle() {
        return title;
    }

    /** 全量行列表（始终可用，用于全量 setLines 回退） */
    public List<String> getAllLines() {
        return allLines;
    }

    /**
     * 内容变化的行索引集合。
     * <p>结构变更时为空集（因为需要全量重建，逐行更新无意义）。
     */
    public Set<Integer> getChangedLineIndices() {
        return changedLineIndices;
    }

    /** 标题是否变化 */
    public boolean isTitleChanged() {
        return titleChanged;
    }

    /** 行结构是否变化（行数变化） */
    public boolean isStructureChanged() {
        return structureChanged;
    }

    /**
     * 是否有任何变更（标题、内容或结构）。
     * <p>返回 {@code false} 时调用方可完全跳过，不发送任何数据包。
     */
    public boolean hasChanges() {
        return titleChanged || structureChanged || !changedLineIndices.isEmpty();
    }

    /**
     * 变更行数（内容变更的行数，不含结构变更）。
     */
    public int getChangedLineCount() {
        return changedLineIndices.size();
    }

    /**
     * 转换为普通 {@link RenderResult}（丢弃增量信息，仅保留全量数据）。
     * <p>用于需要兼容旧 API 的场景。
     */
    public RenderResult toRenderResult() {
        return new RenderResult(title, allLines);
    }

    @Override
    public String toString() {
        return "IncrementalRenderResult{"
                + "lines=" + allLines.size()
                + ", changed=" + changedLineIndices.size()
                + ", titleChanged=" + titleChanged
                + ", structureChanged=" + structureChanged
                + '}';
    }
}
