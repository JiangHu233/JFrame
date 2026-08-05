package io.github.JiangHu.jframe.scoreboard;

import cn.nukkit.Player;
import cn.nukkit.network.protocol.types.DisplaySlot;
import cn.nukkit.scoreboard.manager.IScoreboardManager;
import cn.nukkit.scoreboard.scoreboard.IScoreboard;
import cn.nukkit.scoreboard.scoreboard.IScoreboardLine;
import cn.nukkit.scoreboard.scoreboard.Scoreboard;
import cn.nukkit.scoreboard.scorer.FakeScorer;
import io.github.JiangHu.jframe.content_template.IncrementalRenderResult;
import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.content_template.TemplateEngine;
import io.github.JiangHu.jframe.content_template.TemplateNotFoundException;
import io.github.JiangHu.jframe.content_template.render.IncrementalRenderer;
import io.github.JiangHu.jframe.core.data.reactive.ChangeSet;
import io.github.JiangHu.jframe.core.data.reactive.DataContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 单个玩家的计分板视图——将 {@link ScoreboardTemplate}（模板配置）、{@link DataContext}（数据）
 * 和 Nukkit {@link IScoreboard}（底层计分板实例）三者绑定。
 *
 * <h3>核心机制（三层增量更新）</h3>
 * <ol>
 *   <li><b>创建</b>：为玩家创建独立的 Nukkit {@link Scoreboard}（objectiveName 唯一）</li>
 *   <li><b>全量渲染</b>：首次 show 时用 {@link IncrementalRenderer#renderFull} 渲染全部内容 + 建立依赖图缓存</li>
 *   <li><b>增量渲染</b>：DataContext 变更时用 {@link IncrementalRenderer#renderIncremental}，
 *       基于变量依赖图只重渲染受影响的行（Layer 1+2）</li>
 *   <li><b>混合输出</b>：根据变更行数自适应选择逐行 {@code removeLine+addLine} 或全量 {@code setLines}（Layer 3）</li>
 * </ol>
 *
 * <h3>数据流（增量更新）</h3>
 * <pre>{@code
 * DataContext.put(key, value)
 *     ↓ onChange(ChangeSet)
 * IncrementalRenderer.renderIncremental(data, changeSet)
 *     ↓ 基于依赖图，只重渲染受影响行
 * IncrementalRenderResult (allLines + changedLineIndices)
 *     ↓
 * applyIncrementalOutput(result):
 *     变更行数 ≤ 3  → 逐行 removeLine(oldText) + addLine(newText, score)
 *     变更行数 > 3  → 全量 setLines(allLines)
 *     无变更       → 跳过（不发送任何数据包）
 * }</pre>
 *
 * <h3>已知限制</h3>
 * <p>Nukkit {@link IScoreboard} 没有 {@code setDisplayName} 方法，标题在构造时固定。
 * 标题变更不会反映到已显示的计分板上（需 hide + show 重建）。
 *
 * <h3>线程安全</h3>
 * <p>所有操作同步执行。Nukkit 计分板 API 应在主线程调用，
 * 若从异步线程更新数据，建议通过 {@code Server.getScheduler().scheduleTask} 调度。
 *
 * @see ScoreboardTemplate
 * @see DataContext
 * @see IncrementalRenderer
 * @see IncrementalRenderResult
 */
public class ScoreboardView {

    private final UUID playerId;
    private final ScoreboardTemplate sbTemplate;
    private final DataContext dataContext;
    private final TemplateEngine engine;
    private final IScoreboardManager manager;

    /** Nukkit 底层计分板实例（show 后非 null，hide 后置 null） */
    private IScoreboard nukkitScoreboard;

    /** 增量渲染器（有状态，每个 View 独立持有） */
    private IncrementalRenderer incrementalRenderer;

    /** 当前显示在 Nukkit 计分板上的行文本镜像（用于逐行更新时定位旧行） */
    private List<String> currentLines = new ArrayList<>();

    /** DataContext 变更监听器引用（用于 hide 时移除） */
    private final Consumer<ChangeSet> changeListener;

    /** 是否已显示 */
    private boolean shown = false;

    /**
     * @param playerId    玩家 UUID
     * @param sbTemplate  计分板模板配置
     * @param dataContext 玩家专属数据上下文
     * @param engine      模板引擎
     * @param manager     Nukkit 计分板管理器
     */
    public ScoreboardView(UUID playerId,
                          ScoreboardTemplate sbTemplate,
                          DataContext dataContext,
                          TemplateEngine engine,
                          IScoreboardManager manager) {
        this.playerId = playerId;
        this.sbTemplate = sbTemplate;
        this.dataContext = dataContext;
        this.engine = engine;
        this.manager = manager;
        // 响应式监听：数据变化 → 增量渲染 + 混合输出
        this.changeListener = this::refresh;
    }

    // ===== 生命周期 =====

    /**
     * 向玩家显示计分板。
     * <p>创建 Nukkit {@link Scoreboard}，注册到 Manager，全量渲染初始内容，添加玩家为 viewer。
     *
     * @param player 目标玩家
     */
    public synchronized void show(Player player) {
        if (shown) {
            return;
        }

        // 1. 全量渲染初始内容 + 建立依赖图缓存
        IncrementalRenderResult result = renderFull();
        if (result == null) {
            return; // 模板加载失败
        }

        // 2. 创建 Nukkit 计分板（objectiveName 唯一）
        String objectiveName = ScoreboardConstants.OBJECTIVE_NAME_PREFIX + playerId;
        String displayName = result.getTitle() != null ? result.getTitle() : "";
        nukkitScoreboard = new Scoreboard(
                objectiveName,
                displayName,
                sbTemplate.getCriteriaName(),
                sbTemplate.getSortOrder()
        );

        // 3. 注册到 Manager
        manager.addScoreboard(nukkitScoreboard);

        // 4. 设置行内容
        nukkitScoreboard.setLines(result.getAllLines());
        currentLines = new ArrayList<>(result.getAllLines());

        // 5. 显示给玩家
        nukkitScoreboard.addViewer(player, sbTemplate.getDisplaySlot());

        // 6. 注册响应式监听
        dataContext.onChange(changeListener);

        shown = true;
    }

    /**
     * 隐藏计分板，释放 Nukkit 资源。
     *
     * @param player 目标玩家
     */
    public synchronized void hide(Player player) {
        if (!shown || nukkitScoreboard == null) {
            return;
        }

        // 1. 移除响应式监听
        dataContext.removeListener(changeListener);

        // 2. 移除 viewer
        nukkitScoreboard.removeViewer(player, sbTemplate.getDisplaySlot());

        // 3. 从 Manager 注销
        manager.removeScoreboard(nukkitScoreboard);

        // 4. 清理状态
        nukkitScoreboard = null;
        incrementalRenderer = null;
        currentLines = new ArrayList<>();
        shown = false;
    }

    /**
     * 增量刷新——根据 {@link ChangeSet} 只重新渲染受影响的行，并自适应选择输出策略。
     * <p>由 {@code DataContext.onChange} 自动触发。
     *
     * @param changeSet 本次变更集
     */
    public synchronized void refresh(ChangeSet changeSet) {
        if (!shown || nukkitScoreboard == null || incrementalRenderer == null) {
            return;
        }

        IncrementalRenderResult result = incrementalRenderer.renderIncremental(dataContext, changeSet);
        applyIncrementalOutput(result);
    }

    /**
     * 全量刷新——重新渲染整个模板并全量发送。
     * <p>用于手动触发或需要强制刷新的场景（如模板热重载后）。
     */
    public synchronized void refresh() {
        if (!shown || nukkitScoreboard == null) {
            return;
        }

        IncrementalRenderResult result = renderFull();
        if (result == null) {
            return;
        }
        nukkitScoreboard.setLines(result.getAllLines());
        currentLines = new ArrayList<>(result.getAllLines());
    }

    // ===== 增量输出策略（Layer 3）=====

    /**
     * 根据增量渲染结果自适应选择输出策略。
     * <pre>{@code
     * 无变更         → 跳过（不发送数据包）
     * 结构变更/大量变更 → 全量 setLines
     * 少量内容变更     → 逐行 removeLine + addLine
     * }</pre>
     *
     * @param result 增量渲染结果
     */
    private void applyIncrementalOutput(IncrementalRenderResult result) {
        // 无变更 → 跳过
        if (!result.hasChanges()) {
            return;
        }

        // 结构变更或变更行数超过阈值 → 全量 setLines
        if (result.isStructureChanged()
                || result.getChangedLineCount() > ScoreboardConstants.INCREMENTAL_LINE_THRESHOLD) {
            nukkitScoreboard.setLines(result.getAllLines());
            currentLines = new ArrayList<>(result.getAllLines());
            return;
        }

        // 少量内容变更 → 逐行 removeLine + addLine
        applyPerLineUpdate(result);
    }

    /**
     * 逐行更新——对每个变更行执行 removeLine(旧文本) + addLine(新文本, score)。
     * <p>利用 {@link FakeScorer#equals(Object)} 基于文本匹配的特性，
     * 通过旧文本定位并删除旧行，再用相同 score 添加新行，保持显示位置不变。
     *
     * @param result 增量渲染结果（仅含少量内容变更）
     */
    private void applyPerLineUpdate(IncrementalRenderResult result) {
        List<String> newLines = result.getAllLines();

        for (int idx : result.getChangedLineIndices()) {
            if (idx >= currentLines.size() || idx >= newLines.size()) {
                continue;
            }

            String oldText = currentLines.get(idx);
            String newText = newLines.get(idx);

            // 查询旧行的 score（保持显示位置不变）
            IScoreboardLine oldLine = nukkitScoreboard.getLine(new FakeScorer(oldText));
            int score = (oldLine != null) ? oldLine.getScore() : (newLines.size() - idx);

            // 删除旧行
            if (oldLine != null) {
                nukkitScoreboard.removeLine(new FakeScorer(oldText));
            }

            // 添加新行（相同 score → 相同位置）
            nukkitScoreboard.addLine(newText, score);
        }

        // 更新镜像
        currentLines = new ArrayList<>(newLines);
    }

    // ===== 数据操作 =====

    /**
     * 更新单个数据项，自动触发增量渲染。
     *
     * @param key   键名
     * @param value 值
     * @return this（链式调用）
     */
    public ScoreboardView set(String key, Object value) {
        dataContext.put(key, value);
        return this;
    }

    /**
     * 批量更新数据，只触发一次增量渲染。
     *
     * @param entries 键值对
     * @return this（链式调用）
     */
    public ScoreboardView setAll(java.util.Map<String, Object> entries) {
        dataContext.putAll(entries);
        return this;
    }

    /** 获取数据上下文（可直接操作数据，变更会自动触发增量渲染） */
    public DataContext getDataContext() {
        return dataContext;
    }

    /** 获取计分板模板配置 */
    public ScoreboardTemplate getTemplate() {
        return sbTemplate;
    }

    /** 是否正在显示 */
    public boolean isShown() {
        return shown;
    }

    // ===== 内部方法 =====

    /**
     * 全量渲染——编译模板 + 建立依赖图 + 渲染全部内容。
     * <p>首次 show 或手动 refresh 时调用。
     *
     * @return 全量渲染结果，模板加载失败时返回 null
     */
    private IncrementalRenderResult renderFull() {
        // sbTemplate.getTemplate() 已返回编译后的 Template 对象，无需再通过 engine 加载
        Template template = sbTemplate.getTemplate();
        if (template == null) {
            return null;
        }
        // 首次或模板变更时创建新的 IncrementalRenderer
        if (incrementalRenderer == null) {
            incrementalRenderer = new IncrementalRenderer();
        }
        return incrementalRenderer.renderFull(template, dataContext);
    }
}
