package io.github.JiangHu.jframe.scoreboard;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.network.protocol.types.SortOrder;
import cn.nukkit.scoreboard.scoreboard.IScoreboard;
import cn.nukkit.scoreboard.scoreboard.Scoreboard;
import cn.nukkit.scoreboard.scorer.FakeScorer;
import io.github.JiangHu.jframe.content_template.IncrementalRenderResult;
import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.content_template.TemplateEngine;
import io.github.JiangHu.jframe.content_template.render.IncrementalRenderer;
import io.github.JiangHu.jframe.core.data.reactive.ChangeSet;
import io.github.JiangHu.jframe.core.data.reactive.DataContext;
import io.github.JiangHu.jframe.core.data.reactive.EffectScope;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 单个玩家的计分板视图——将 {@link ScoreboardTemplate}（模板配置）、{@link DataContext}（数据）
 * 和 <b>Nukkit 原生 Scoreboard API</b> 三者绑定。
 *
 * <h3>三段式生命周期（KeepAlive）</h3>
 * <table>
 *   <tr><th>方法</th><th>显示</th><th>dataContext</th><th>渲染监听</th><th>用途</th></tr>
 *   <tr><td>{@link #show} / {@link #reactivate}</td><td>显示</td><td>不动</td><td>注册</td><td>进入或恢复激活</td></tr>
 *   <tr><td>{@link #deactivate}</td><td>隐藏</td><td>保留</td><td>移除（停渲染）</td><td>切换/隐藏（KeepAlive）</td></tr>
 *   <tr><td>{@link #dispose}</td><td>隐藏</td><td>销毁</td><td>全部摘除</td><td>玩家退出</td></tr>
 * </table>
 * <p>切换计分板时调用 deactivate（保留数据和渲染状态），玩家退出时调用 dispose（彻底释放）。
 * {@link #hide} 为 {@link #deactivate} 的向后兼容别名。
 *
 * <h3>EffectScope 自动清理</h3>
 * <p>构造时将 {@code dataContext::dispose} 注册到 {@link EffectScope}。dispose 时 effectScope
 * 自动执行清理链，保证 dataContext 与 parent（玩家全局）的监听引用必定断开，杜绝内存泄漏。
 *
 * <h3>核心机制（原生 API + 三层增量更新）</h3>
 * <ol>
 *   <li><b>创建</b>：构造 Nukkit {@link Scoreboard} 对象，添加所有行，再 {@code addViewer} 显示给玩家</li>
 *   <li><b>全量渲染</b>：首次 show 时用 {@link IncrementalRenderer#renderFull} 渲染全部内容 + 建立依赖图缓存</li>
 *   <li><b>增量渲染</b>：DataContext 变更时用 {@link IncrementalRenderer#renderIncremental}，
 *       基于变量依赖图只重渲染受影响的行</li>
 *   <li><b>混合输出</b>：根据变更行数自适应选择逐行更新或全量重建</li>
 * </ol>
 *
 * <h3>数据流（原生 API）</h3>
 * <pre>{@code
 * DataContext.put(key, value)
 *     ↓ onChange(ChangeSet)
 * IncrementalRenderer.renderIncremental(data, changeSet)
 *     ↓ 基于依赖图，只重渲染受影响行
 * IncrementalRenderResult (allLines + changedLineIndices)
 *     ↓
 * applyIncrementalOutput(result):
 *     变更行数 ≤ 3  → 逐行 removeLine(oldScorer) + addLine(newScorer)
 *     变更行数 > 3  → 全量 removeAllLine(true) + 重建 addLine 循环
 *     无变更       → 跳过（不触发任何网络操作）
 * }</pre>
 *
 * <h3>FakeScorer 管理与 makeUniqueName</h3>
 * <p>每行使用 {@link FakeScorer}（自定义文本行）作为 scorer。FakeScorer 的 {@code equals/hashCode}
 * 基于 fakeName，<b>重复 fakeName 会导致行合并</b>（Map key 覆盖）。因此通过 {@link #makeUniqueName}
 * 给每行追加唯一颜色代码后缀（{@code §0}~{@code §f}），确保每行唯一。
 *
 * <h3>线程安全</h3>
 * <p>所有操作同步执行。{@link #refresh} 自动检测主线程，若从异步线程触发数据变更，
 * 自动调度到主线程执行（Nukkit Scoreboard 操作必须在主线程发包）。
 *
 * @see ScoreboardTemplate
 * @see IScoreboard
 * @see FakeScorer
 * @see DataContext
 * @see EffectScope
 * @see IncrementalRenderer
 */
public class ScoreboardView {

    /**
     * 基岩版颜色代码池（§0~§9, §a~§f），用于给每行生成唯一后缀。
     * <p>基岩版计分板用显示文本作为 fake player name，重复文本会导致客户端合并行。
     * 颜色代码追加在文本末尾且后面无可见字符，玩家不可见，不影响显示效果。
     */
    private static final String[] TEAM_COLORS = {
            "\u00A70", "\u00A71", "\u00A72", "\u00A73", "\u00A74", "\u00A75",
            "\u00A76", "\u00A77", "\u00A78", "\u00A79", "\u00A7a", "\u00A7b",
            "\u00A7c", "\u00A7d", "\u00A7e", "\u00A7f"
    };

    private final UUID playerId;
    private final ScoreboardTemplate sbTemplate;
    private final DataContext dataContext;
    private final TemplateEngine engine;

    /** Nukkit 原生 Scoreboard 对象（激活时非 null，deactivate/dispose 后置 null） */
    private IScoreboard nukkitScoreboard;

    /** 每行的 FakeScorer（与 currentLines 一一对应，用于增量更新定位旧行） */
    private List<FakeScorer> scorers = new ArrayList<>();

    /** 当前绑定的玩家（激活时设置，deactivate 时清空） */
    private Player currentPlayer;

    /** 增量渲染器（有状态，每个 View 独立持有；deactivate 时保留用于 KeepAlive） */
    private IncrementalRenderer incrementalRenderer;

    /** 当前显示的行文本镜像（用于判断是否需要更新；deactivate 时保留用于 KeepAlive） */
    private List<String> currentLines = new ArrayList<>();

    /** DataContext 变更监听器引用（用于 deactivate/dispose 时移除） */
    private final Consumer<ChangeSet> changeListener;

    /** 副作用作用域——集中管理清理逻辑，dispose 时自动执行全部清理链 */
    private final EffectScope effectScope = new EffectScope();

    /** 是否正在显示（激活） */
    private boolean shown = false;

    /** 是否已彻底销毁（dispose 后不可再用） */
    private volatile boolean disposed = false;

    /**
     * @param playerId    玩家 UUID
     * @param sbTemplate  计分板模板配置
     * @param dataContext 玩家专属数据上下文（parent 应为玩家全局，构成单计分板局部层）
     * @param engine      模板引擎
     */
    public ScoreboardView(UUID playerId,
                          ScoreboardTemplate sbTemplate,
                          DataContext dataContext,
                          TemplateEngine engine) {
        this.playerId = playerId;
        this.sbTemplate = sbTemplate;
        this.dataContext = dataContext;
        this.engine = engine;
        // 响应式监听：数据变化 → 增量渲染 + 混合输出
        this.changeListener = this::refresh;
        // 注册 dataContext 清理到 effectScope——dispose 时自动断开 parent 监听，防止内存泄漏
        this.effectScope.register(dataContext::dispose);
    }

    // ===== 生命周期 =====

    /**
     * 向玩家显示计分板（首次显示入口）。
     * <p>创建 Nukkit {@link Scoreboard} 对象，添加所有行（{@link FakeScorer}），
     * 再调用 {@code addViewer} 显示给玩家。{@code addViewer} 时 Nukkit 内部自动发送
     * {@code SetDisplayObjectivePacket} + 全部行的 {@code SetScorePacket}。
     *
     * @param player 目标玩家（实现了 {@code IScoreboardViewer}）
     */
    public synchronized void show(Player player) {
        activate(player);
    }

    /**
     * 从 deactivate 状态恢复显示（KeepAlive 场景）。
     * <p>重建 Nukkit Scoreboard 对象 + 重新添加行 + addViewer + 注册渲染监听。
     * 数据和渲染状态（dataContext / incrementalRenderer）在 deactivate 期间保留。
     *
     * @param player 目标玩家
     */
    public synchronized void reactivate(Player player) {
        activate(player);
    }

    /**
     * 激活计分板——show 和 reactivate 的共用逻辑。
     * <p>全量渲染 + 创建 Nukkit Scoreboard + 添加行 + addViewer + 注册渲染监听。
     *
     * @param player 目标玩家
     */
    private void activate(Player player) {
        if (shown || disposed) {
            return;
        }

        // 1. 全量渲染初始内容 + 建立依赖图缓存
        IncrementalRenderResult result = renderFull();
        if (result == null) {
            return; // 模板加载失败
        }

        this.currentPlayer = player;

        // 2. 创建 Nukkit Scoreboard 对象
        String objName = ScoreboardConstants.OBJECTIVE_NAME_PREFIX + playerId;
        String displayName = result.getTitle() != null ? result.getTitle() : "";
        nukkitScoreboard = new Scoreboard(
                objName, displayName,
                sbTemplate.getCriteriaName(),
                sbTemplate.getSortOrder()
        );

        // 3. 添加所有行（此时还没有 viewer，不会触发网络发包）
        List<String> lines = result.getAllLines();
        scorers = addAllLines(lines);
        currentLines = new ArrayList<>(lines);

        // 4. 显示给玩家（addViewer 时 Nukkit 自动发送完整状态）
        nukkitScoreboard.addViewer(player, sbTemplate.getDisplaySlot());

        // 5. 注册响应式监听
        dataContext.onChange(changeListener);

        shown = true;
    }

    /**
     * 隐藏计分板（向后兼容，等价于 {@link #deactivate}——保留数据和渲染状态）。
     *
     * @param player 目标玩家
     */
    public synchronized void hide(Player player) {
        deactivate(player);
    }

    /**
     * 停用计分板——移除显示和渲染监听，但<b>保留</b>数据和渲染状态（KeepAlive）。
     * <p>切换计分板或临时隐藏时调用，后续可通过 {@link #reactivate} 恢复显示。
     * <p>调用 {@code removeViewer} 移除玩家视图，Nukkit 内部自动发送
     * {@code RemoveObjectivePacket} 清除客户端计分板。
     *
     * @param player 目标玩家
     */
    public synchronized void deactivate(Player player) {
        if (!shown || disposed) {
            return;
        }

        // 1. 移除响应式监听（停止响应数据变化）
        dataContext.removeListener(changeListener);

        // 2. 移除玩家视图（Nukkit 自动发送 RemoveObjectivePacket）
        if (nukkitScoreboard != null) {
            nukkitScoreboard.removeViewer(player, sbTemplate.getDisplaySlot());
            nukkitScoreboard = null;
        }

        // 3. 清理显示层状态（保留 dataContext / incrementalRenderer / currentLines 用于 KeepAlive）
        scorers = new ArrayList<>();
        currentPlayer = null;
        shown = false;
    }

    /**
     * 彻底销毁计分板——释放全部资源，不可再使用。
     * <p>玩家退出时调用。effectScope 自动执行 {@code dataContext.dispose()}，
     * 断开与玩家全局的监听引用，防止内存泄漏。
     *
     * @param player 目标玩家（可能为 null，如玩家已离线时清理）
     */
    public synchronized void dispose(Player player) {
        if (disposed) {
            return;
        }

        // 1. 如果还在显示，先移除视图
        if (shown && nukkitScoreboard != null && player != null) {
            nukkitScoreboard.removeViewer(player, sbTemplate.getDisplaySlot());
        }

        // 2. 移除渲染监听
        dataContext.removeListener(changeListener);

        // 3. effectScope 清理（执行 dataContext.dispose，断开 parent 监听，防止内存泄漏）
        effectScope.dispose();

        // 4. 清理全部状态
        nukkitScoreboard = null;
        scorers = new ArrayList<>();
        currentLines = new ArrayList<>();
        incrementalRenderer = null;
        currentPlayer = null;
        shown = false;
        disposed = true;
    }

    /**
     * 增量刷新——根据 {@link ChangeSet} 只重新渲染受影响的行，并自适应选择输出策略。
     * <p>由 {@code DataContext.onChange} 自动触发。
     * <p><b>线程安全</b>：自动检测主线程，若从异步线程触发，调度到主线程执行
     * （Nukkit Scoreboard 操作必须在主线程发包）。
     *
     * @param changeSet 本次变更集
     */
    public synchronized void refresh(ChangeSet changeSet) {
        if (!shown || nukkitScoreboard == null || incrementalRenderer == null || disposed) {
            return;
        }

        // 发包线程安全：Nukkit Scoreboard 操作必须在主线程执行
        try {
            if (!Server.getInstance().isPrimaryThread()) {
                Server.getInstance().getScheduler().scheduleTask(() -> doRefresh(changeSet));
                return;
            }
        } catch (Exception e) {
            // Server 尚未初始化（如单元测试环境），直接在当前线程执行
        }

        doRefresh(changeSet);
    }

    /**
     * 实际执行增量刷新（确保在主线程调用）。
     *
     * @param changeSet 本次变更集
     */
    private synchronized void doRefresh(ChangeSet changeSet) {
        if (!shown || nukkitScoreboard == null || incrementalRenderer == null || disposed) {
            return;
        }
        IncrementalRenderResult result = incrementalRenderer.renderIncremental(dataContext, changeSet);
        applyIncrementalOutput(result);
    }

    /**
     * 全量刷新——重新渲染整个模板并全量更新行。
     * <p>用于手动触发或需要强制刷新的场景（如模板热重载后）。
     */
    public synchronized void refresh() {
        if (!shown || nukkitScoreboard == null || disposed) {
            return;
        }

        IncrementalRenderResult result = renderFull();
        if (result == null) {
            return;
        }
        applyFullUpdate(result.getAllLines());
    }

    // ===== 增量输出策略 =====

    /**
     * 根据增量渲染结果自适应选择输出策略。
     * <pre>{@code
     * 无变更         → 跳过（不触发网络操作）
     * 结构变更/大量变更 → 全量重建（removeAllLine + 重新 addLine）
     * 少量内容变更     → 逐行更新（removeLine 旧行 + addLine 新行）
     * }</pre>
     *
     * @param result 增量渲染结果
     */
    private void applyIncrementalOutput(IncrementalRenderResult result) {
        // 无变更 → 跳过
        if (!result.hasChanges()) {
            return;
        }

        // 结构变更或变更行数超过阈值 → 全量重建
        if (result.isStructureChanged()
                || result.getChangedLineCount() > ScoreboardConstants.INCREMENTAL_LINE_THRESHOLD) {
            applyFullUpdate(result.getAllLines());
            return;
        }

        // 少量内容变更 → 逐行更新
        applyPerLineUpdate(result);
    }

    /**
     * 全量更新——先 {@code removeAllLine(true)} 清除所有行（并通知 viewer），
     * 再逐行 {@code addLine} 添加新行（每次自动通知 viewer）。
     *
     * @param newLines 新的全量行列表
     */
    private void applyFullUpdate(List<String> newLines) {
        // 1. 移除所有旧行并通知 viewer（true = resend，客户端清除所有行）
        nukkitScoreboard.removeAllLine(true);

        // 2. 重新添加所有行（每次 addLine 自动通知 viewer 添加新行）
        scorers = addAllLines(newLines);

        // 3. 更新镜像
        currentLines = new ArrayList<>(newLines);
    }

    /**
     * 逐行更新——对每个变更行执行 {@code removeLine}(旧 scorer) + {@code addLine}(新 scorer)。
     * <p>Nukkit 原生 API 在每次 removeLine / addLine 时自动发送增量数据包给 viewer。
     *
     * @param result 增量渲染结果（仅含少量内容变更）
     */
    private void applyPerLineUpdate(IncrementalRenderResult result) {
        List<String> newLines = result.getAllLines();

        for (int idx : result.getChangedLineIndices()) {
            if (idx >= scorers.size() || idx >= newLines.size()) {
                continue;
            }

            // 移除旧行（Nukkit 自动发送 REMOVE 包）
            nukkitScoreboard.removeLine(scorers.get(idx));

            // 添加新行（Nukkit 自动发送 SET 包）
            FakeScorer newScorer = new FakeScorer(makeUniqueName(newLines.get(idx), idx));
            int score = calculateScore(idx, newLines.size());
            nukkitScoreboard.addLine(newScorer, score);

            // 更新映射
            scorers.set(idx, newScorer);
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

    /** 是否正在显示（激活） */
    public boolean isShown() {
        return shown;
    }

    /** 是否已彻底销毁（dispose 后不可再用） */
    public boolean isDisposed() {
        return disposed;
    }

    // ===== 内部方法 =====

    /**
     * 给行文本追加唯一的颜色代码后缀，确保 FakeScorer 的 fakeName 唯一。
     * <p>解决 FakeScorer 重复 fakeName 导致行合并问题：FakeScorer 的 {@code equals/hashCode}
     * 基于 fakeName，相同 fakeName 会被 {@code IScoreboard.lines} Map 视为同一个 key 而覆盖。
     * 追加颜色代码后，每行 fakeName 唯一，避免合并。
     *
     * @param lineText 原始行文本
     * @param index    行索引（用于选择不同颜色代码）
     * @return 追加了唯一后缀的文本
     */
    static String makeUniqueName(String lineText, int index) {
        return lineText + TEAM_COLORS[index % TEAM_COLORS.length];
    }

    /**
     * 为行列表创建 FakeScorer 并添加到 Nukkit Scoreboard。
     * <p>为每行创建 {@link FakeScorer}（使用 {@link #makeUniqueName} 保证唯一），
     * 计算 score，然后调用 {@code addLine}。
     *
     * @param lines 行文本列表
     * @return 创建的 FakeScorer 列表（与 lines 一一对应）
     */
    private List<FakeScorer> addAllLines(List<String> lines) {
        List<FakeScorer> result = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            FakeScorer scorer = new FakeScorer(makeUniqueName(lines.get(i), i));
            int score = calculateScore(i, lines.size());
            nukkitScoreboard.addLine(scorer, score);
            result.add(scorer);
        }
        return result;
    }

    /**
     * 根据行索引和排序方式计算 score（决定显示位置）。
     * <p>模板中的第一行（index 0）应显示在计分板顶部：
     * <ul>
     *   <li>DESCENDING（默认）：第一行 score 最高 → {@code totalLines - index}</li>
     *   <li>ASCENDING：第一行 score 最低 → {@code index + 1}</li>
     * </ul>
     *
     * @param index      行索引（0-based）
     * @param totalLines 总行数
     * @return 该行的 score 值
     */
    private int calculateScore(int index, int totalLines) {
        if (sbTemplate.getSortOrder() == SortOrder.DESCENDING) {
            return totalLines - index;
        }
        return index + 1;
    }

    /**
     * 全量渲染——编译模板 + 建立依赖图 + 渲染全部内容。
     * <p>首次 show 或手动 refresh 时调用。
     *
     * @return 全量渲染结果，模板加载失败时返回 null
     */
    private IncrementalRenderResult renderFull() {
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
