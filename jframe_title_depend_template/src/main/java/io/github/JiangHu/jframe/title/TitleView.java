package io.github.JiangHu.jframe.title;

import cn.nukkit.Player;
import io.github.JiangHu.jframe.content_template.IncrementalRenderResult;
import io.github.JiangHu.jframe.content_template.TemplateEngine;
import io.github.JiangHu.jframe.content_template.render.IncrementalRenderer;
import io.github.JiangHu.jframe.core.data.reactive.ChangeSet;
import io.github.JiangHu.jframe.core.data.reactive.DataContext;
import io.github.JiangHu.jframe.core.data.reactive.EffectScope;
import io.github.JiangHu.jframe.title.channel.TitleChannelMapper;
import io.github.JiangHu.jframe.title.channel.TitleChannels;
import io.github.JiangHu.jframe.title.nukkit.TitleScheduler;
import io.github.JiangHu.jframe.title.nukkit.TitleSender;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 单个玩家的标题视图——将 {@link TitleTemplate}（模板配置）、{@link DataContext}（数据）
 * 与 <b>Nukkit 标题 API</b>（经 {@link TitleSender} 适配）三者绑定。
 *
 * <h3>三段式生命周期（KeepAlive，常驻模式）</h3>
 * <table>
 *   <tr><th>方法</th><th>显示</th><th>dataContext</th><th>渲染监听</th><th>定时任务</th><th>用途</th></tr>
 *   <tr><td>{@link #show} / {@link #reactivate}</td><td>显示</td><td>不动</td><td>注册</td><td>重建</td><td>进入或恢复激活</td></tr>
 *   <tr><td>{@link #deactivate}</td><td>清除</td><td>保留</td><td>移除</td><td>取消</td><td>槽位切换 / 隐藏</td></tr>
 *   <tr><td>{@link #dispose}</td><td>清除</td><td>销毁</td><td>全部摘除</td><td>取消</td><td>玩家退出 / 到期</td></tr>
 * </table>
 *
 * <h3>双模式</h3>
 * <ul>
 *   <li><b>PERSISTENT（常驻）</b>：KeepAlive 生命周期；启用动作栏且配置续期间隔 > 0 时，
 *       激活期间按周期重发动作栏文本（对抗客户端动作栏自然超时消失）；</li>
 *   <li><b>TRANSIENT（瞬时）</b>：show 后按 fadeIn + stay + fadeOut 总时长调度到期任务，
 *       到期时（autoClear 开启且占用 TITLE 槽）清除标题 → dispose → 经回调通知
 *       {@link TitleManager} 移除缓存；stay 期间仍响应数据更新。</li>
 * </ul>
 *
 * <h3>通道级 diff 输出</h3>
 * <p>渲染结果经 {@link TitleChannelMapper} 切分为三通道后与 {@code lastChannels} 镜像比较：
 * title / subtitle 视为一个发送单元（一次 sendTitle 携带时序），actionbar 独立发送；
 * 无变化的通道不重复发包；渲染无变更（hasChanges == false）时零发包。</p>
 *
 * <h3>EffectScope 自动清理</h3>
 * <p>构造时将 {@code dataContext::dispose} 与定时任务取消注册到 {@link EffectScope}，
 * dispose 时自动执行清理链，保证与玩家全局层的监听引用必定断开。</p>
 *
 * <h3>线程安全</h3>
 * <p>所有生命周期方法 synchronized；{@link #refresh(ChangeSet)} 经
 * {@link TitleScheduler#runOnPrimaryThread} 保证发包在主线程执行。</p>
 */
public class TitleView {

    private final UUID playerId;
    private final TitleTemplate template;
    private final DataContext dataContext;
    private final TemplateEngine engine;
    private final TitleSender sender;
    private final TitleScheduler scheduler;

    /** 瞬时模式到期后的回调（由 Manager 注册，用于移除缓存；可为 null） */
    private final Consumer<TitleView> onTransientExpired;

    /** 增量渲染器（有状态，每个 View 独立持有；deactivate 时保留用于 KeepAlive） */
    private IncrementalRenderer incrementalRenderer;

    /** 当前已发送的通道文本镜像（用于通道级 diff） */
    private TitleChannels lastChannels;

    /** 当前绑定的玩家（激活时设置，deactivate 时清空；发包经 sender，不直接调用 Player） */
    private Player currentPlayer;

    /** DataContext 变更监听器引用（用于 deactivate / dispose 时移除） */
    private final Consumer<ChangeSet> changeListener;

    /** 副作用作用域——集中管理清理逻辑，dispose 时自动执行全部清理链 */
    private final EffectScope effectScope = new EffectScope();

    /** 瞬时模式到期任务句柄（null 表示未调度） */
    private Object expireTaskHandle;

    /** 动作栏周期续期任务句柄（null 表示未调度） */
    private Object renewTaskHandle;

    /** 是否正在显示（激活） */
    private boolean shown = false;

    /** 是否已彻底销毁（dispose 后不可再用） */
    private volatile boolean disposed = false;

    /**
     * @param playerId           玩家 UUID
     * @param template           标题模板配置
     * @param dataContext        玩家专属数据上下文（parent 应为玩家全局，构成单模板局部层）
     * @param engine             模板引擎
     * @param sender             标题发送器（已绑定目标玩家）
     * @param scheduler          调度器
     * @param onTransientExpired 瞬时模式到期回调（可为 null）
     */
    public TitleView(UUID playerId,
                     TitleTemplate template,
                     DataContext dataContext,
                     TemplateEngine engine,
                     TitleSender sender,
                     TitleScheduler scheduler,
                     Consumer<TitleView> onTransientExpired) {
        this.playerId = Objects.requireNonNull(playerId, "玩家 UUID 不得为 null");
        this.template = Objects.requireNonNull(template, "标题模板不得为 null");
        this.dataContext = Objects.requireNonNull(dataContext, "数据上下文不得为 null");
        this.engine = engine;
        this.sender = Objects.requireNonNull(sender, "标题发送器不得为 null");
        this.scheduler = Objects.requireNonNull(scheduler, "调度器不得为 null");
        this.onTransientExpired = onTransientExpired;
        // 响应式监听：数据变化 → 增量渲染 + 通道级 diff 输出
        this.changeListener = this::refresh;
        // 注册清理链：dispose 时断开 parent 监听 + 取消全部定时任务
        this.effectScope.register(dataContext::dispose);
        this.effectScope.register(() -> scheduler.cancel(expireTaskHandle));
        this.effectScope.register(() -> scheduler.cancel(renewTaskHandle));
    }

    // ===== 生命周期 =====

    /**
     * 向玩家显示标题（首次显示入口）
     *
     * @param player 目标玩家（发包经 sender，参数仅用于记录）
     */
    public synchronized void show(Player player) {
        activate(player);
    }

    /**
     * 从 deactivate 状态恢复显示（KeepAlive 场景）
     *
     * @param player 目标玩家
     */
    public synchronized void reactivate(Player player) {
        activate(player);
    }

    /**
     * 激活标题——show 与 reactivate 的共用逻辑。
     * <p>全量渲染 + 通道切分 + 全量发包（客户端状态未知，强制重发）+ 注册渲染监听
     * + 按模式调度定时任务（瞬时到期 / 动作栏续期）。</p>
     */
    private void activate(Player player) {
        if (shown || disposed) {
            return;
        }

        // 1. 全量渲染初始内容 + 建立依赖图缓存
        IncrementalRenderResult result = renderFullInternal();

        this.currentPlayer = player;

        // 2. 通道切分 + 全量发包（activate 恒重发，客户端可能已被其他视图覆盖或清除；
        //    仅发送本视图占用的槽位通道，避免顶掉另一槽位的共存视图）
        TitleChannels channels = TitleChannelMapper.map(result.toRenderResult(), template);
        if (template.occupiesTitleSlot()) {
            sendTitlePacket(channels);
        }
        if (template.occupiesActionBarSlot()) {
            sendActionBarPacket(channels);
        }
        lastChannels = channels;

        // 3. 注册响应式监听
        dataContext.onChange(changeListener);

        // 4. 按模式调度定时任务
        if (template.getMode() == TitleMode.TRANSIENT) {
            expireTaskHandle = scheduler.scheduleDelayed(this::onExpire,
                    template.getTiming().totalTicks());
        } else if (template.getMode() == TitleMode.PERSISTENT
                && template.occupiesActionBarSlot()
                && template.getActionBarRefreshInterval() > 0) {
            renewTaskHandle = scheduler.scheduleRepeating(this::renewActionBar,
                    template.getActionBarRefreshInterval());
        }

        shown = true;
    }

    /**
     * 隐藏标题（{@link #deactivate} 的别名，保留数据与渲染状态）
     *
     * @param player 目标玩家
     */
    public synchronized void hide(Player player) {
        deactivate(player);
    }

    /**
     * 停用标题——移除渲染监听与定时任务，清除 TITLE 槽显示，
     * 但<b>保留</b>数据与渲染状态（KeepAlive）。动作栏无清除 API，停止续发后自然消失。
     *
     * @param player 目标玩家
     */
    public synchronized void deactivate(Player player) {
        if (!shown || disposed) {
            return;
        }

        // 1. 移除响应式监听（停止响应数据变化）
        dataContext.removeListener(changeListener);

        // 2. 清除 TITLE 槽显示（仅当本视图占用 TITLE 槽，避免误清其他视图）
        if (template.occupiesTitleSlot()) {
            sender.clearTitle();
        }

        // 3. 取消定时任务（保留 dataContext / incrementalRenderer / lastChannels 用于 KeepAlive）
        cancelScheduledTasks();
        currentPlayer = null;
        shown = false;
    }

    /**
     * 彻底销毁视图——释放全部资源，不可再使用。
     * <p>玩家退出或瞬时模式到期时调用。effectScope 自动执行清理链
     * （dataContext.dispose + 取消全部定时任务）。</p>
     *
     * @param player 目标玩家（可为 null）
     */
    public synchronized void dispose(Player player) {
        if (disposed) {
            return;
        }

        // 1. 移除渲染监听
        dataContext.removeListener(changeListener);

        // 2. effectScope 清理（dataContext.dispose + 取消定时任务）
        effectScope.dispose();

        // 3. 清理全部状态
        incrementalRenderer = null;
        lastChannels = null;
        currentPlayer = null;
        expireTaskHandle = null;
        renewTaskHandle = null;
        shown = false;
        disposed = true;
    }

    // ===== 刷新 =====

    /**
     * 增量刷新——根据 {@link ChangeSet} 只重新渲染受影响的行，按通道级 diff 输出。
     * <p>由 {@code DataContext.onChange} 自动触发；经调度器保证在主线程执行。</p>
     *
     * @param changeSet 本次变更集
     */
    public synchronized void refresh(ChangeSet changeSet) {
        if (!shown || disposed || incrementalRenderer == null) {
            return;
        }
        scheduler.runOnPrimaryThread(() -> doRefresh(changeSet));
    }

    /** 实际执行增量刷新（确保在主线程调用） */
    private synchronized void doRefresh(ChangeSet changeSet) {
        if (!shown || disposed || incrementalRenderer == null) {
            return;
        }
        IncrementalRenderResult result = incrementalRenderer.renderIncremental(dataContext, changeSet);
        if (!result.hasChanges()) {
            return; // 渲染无变更 → 零发包
        }
        applyChannels(TitleChannelMapper.map(result.toRenderResult(), template));
    }

    /**
     * 全量刷新——重新渲染整个模板，按通道级 diff 输出。
     * <p>用于手动触发或需要强制刷新的场景。</p>
     */
    public synchronized void refresh() {
        if (!shown || disposed) {
            return;
        }
        applyChannels(TitleChannelMapper.map(renderFullInternal().toRenderResult(), template));
    }

    /** 渲染全量并更新渲染器缓存 */
    private IncrementalRenderResult renderFullInternal() {
        if (incrementalRenderer == null) {
            incrementalRenderer = new IncrementalRenderer();
        }
        return incrementalRenderer.renderFull(template.getTemplate(), dataContext);
    }

    /** 通道级 diff 输出：仅重发有变化且本视图占用的槽位通道 */
    private void applyChannels(TitleChannels newChannels) {
        if (template.occupiesTitleSlot() && newChannels.titleOrSubtitleChanged(lastChannels)) {
            sendTitlePacket(newChannels);
        }
        if (template.occupiesActionBarSlot() && newChannels.actionbarChanged(lastChannels)) {
            sendActionBarPacket(newChannels);
        }
        lastChannels = newChannels;
    }

    /** 发送 title / subtitle 通道（一个发送单元，携带时序） */
    private void sendTitlePacket(TitleChannels channels) {
        TitleTiming timing = template.getTiming();
        sender.sendTitle(channels.getTitle(), channels.getSubtitle(),
                timing.getFadeIn(), timing.getStay(), timing.getFadeOut());
    }

    /** 发送 actionbar 通道（无内容时跳过） */
    private void sendActionBarPacket(TitleChannels channels) {
        if (channels.getActionbar() != null) {
            sender.sendActionBar(channels.getActionbar());
        }
    }

    // ===== 定时任务回调（包级，供调度器触发） =====

    /**
     * 瞬时模式到期回调：autoClear 开启且占用 TITLE 槽时清除标题，
     * 随后 dispose 并通知 Manager 移除缓存。
     */
    synchronized void onExpire() {
        if (disposed) {
            return;
        }
        if (shown && template.isAutoClear() && template.occupiesTitleSlot()) {
            sender.clearTitle();
        }
        expireTaskHandle = null; // 任务已自然到期
        dispose(null);
        if (onTransientExpired != null) {
            onTransientExpired.accept(this);
        }
    }

    /**
     * 动作栏周期续期回调：重发上次动作栏文本（不重新渲染，纯续期）。
     */
    synchronized void renewActionBar() {
        if (!shown || disposed) {
            return; // 防御取消竞态：任务触发时视图可能已停用
        }
        if (lastChannels != null && lastChannels.getActionbar() != null) {
            sender.sendActionBar(lastChannels.getActionbar());
        }
    }

    /** 取消全部定时任务（deactivate 用，可重入） */
    private void cancelScheduledTasks() {
        if (expireTaskHandle != null) {
            scheduler.cancel(expireTaskHandle);
            expireTaskHandle = null;
        }
        if (renewTaskHandle != null) {
            scheduler.cancel(renewTaskHandle);
            renewTaskHandle = null;
        }
    }

    // ===== 数据操作 =====

    /**
     * 更新单个数据项，自动触发增量刷新
     *
     * @param key   键名
     * @param value 值
     * @return this（链式调用）
     */
    public TitleView set(String key, Object value) {
        dataContext.put(key, value);
        return this;
    }

    /**
     * 批量更新数据，只触发一次增量刷新
     *
     * @param entries 键值对
     * @return this（链式调用）
     */
    public TitleView setAll(Map<String, Object> entries) {
        dataContext.putAll(entries);
        return this;
    }

    // ===== 状态查询 =====

    /** 玩家 UUID */
    public UUID getPlayerId() {
        return playerId;
    }

    /** 标题模板配置 */
    public TitleTemplate getTemplate() {
        return template;
    }

    /** 数据上下文（可直接操作数据，变更会自动触发增量刷新） */
    public DataContext getDataContext() {
        return dataContext;
    }

    /** 模板引擎 */
    public TemplateEngine getEngine() {
        return engine;
    }

    /** 当前绑定的玩家（未激活时为 null） */
    public Player getCurrentPlayer() {
        return currentPlayer;
    }

    /** 是否正在显示（激活） */
    public boolean isShown() {
        return shown;
    }

    /** 是否已彻底销毁（dispose 后不可再用） */
    public boolean isDisposed() {
        return disposed;
    }
}
