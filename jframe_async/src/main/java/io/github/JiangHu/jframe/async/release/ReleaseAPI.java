package io.github.JiangHu.jframe.async.release;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.scheduler.TaskHandler;
import io.github.JiangHu.jframe.core.module.PluginAware;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 主线程多通道缓释调度器。
 * <p>
 * 解决「异步线程批量向 Nukkit 主线程提交任务」的拥堵问题：任务进入各通道队列后，
 * 由每 tick 的仲裁（{@link #drain()}，仅主线程执行）在<b>全局预算</b>内按通道策略
 * （{@link ReleasePolicy}）分批释放执行，不拥堵主线程。
 *
 * <h3>三种策略</h3>
 * <ul>
 *   <li><b>DEADLINE</b>：声明 N tick 内完成，速率 = ceil(pending / 剩余tick) 动态收敛；
 *       超期退化为弹性（与 BEST_EFFORT 同组分食剩余带宽）；</li>
 *   <li><b>RATE</b>：固定速率（支持小数配额累积，如 0.5/tick）；</li>
 *   <li><b>BEST_EFFORT</b>：吃全局剩余预算。</li>
 * </ul>
 *
 * <h3>通道获取</h3>
 * <pre>{@code
 * ReleaseChannel ch = release.acquire(ReleasePolicy.deadlineSeconds(20));      // MANUAL（默认）
 * ReleaseChannel ch2 = release.acquireAuto(ReleasePolicy.ratePerTick(5));      // AUTO（自动回收，显式申请）
 * ReleaseChannel ch3 = release.channel("castle", ReleasePolicy.bestEffort());  // 命名（等效 MANUAL）
 * }</pre>
 *
 * <h3>架构定位</h3>
 * 独立基础设施原语（与 ThreadAPI / TaskAPI / ThreadFlow 平级）：
 * 「跨线程 → 主线程的限流桥梁」。本类<b>零依赖</b> flow 包；
 * {@code FlowContext.awaitChannel} 为其提供的单向糖接口。
 *
 * <h3>实现要点（线程模型）</h3>
 * <ul>
 *   <li><b>记账单线程化</b>：配额、时钟、累积器、回收全部在主线程 drain 内演进——
 *       零锁、零竞态、可单测（drain 为包级可见纯步骤）；</li>
 *   <li><b>提交任意线程</b>：任务队列用 {@link ConcurrentLinkedQueue}，
 *       pending 计数用 {@link AtomicInteger}（避免 CLQ.size() 的 O(n)）；</li>
 *   <li><b>挂起 / 唤醒</b>：全部空闲时取消 repeating task（零开销），
 *       新提交时在锁内二次检查重新调度，无丢唤醒；</li>
 *   <li>{@code scheduleRepeating / cancelHandler / logError} 为 {@code protected}，
 *       单测子类覆盖即可脱离真实 Nukkit Server。</li>
 * </ul>
 *
 * @see ReleasePolicy
 * @see ReleaseChannel
 */
public class ReleaseAPI implements PluginAware {

    /** 全局每 tick 释放预算默认值 */
    public static final int DEFAULT_GLOBAL_BUDGET = 200;

    /** AUTO 通道空闲回收阈值默认值（tick） */
    public static final int DEFAULT_IDLE_RECYCLE_TICKS = 100;

    /** 内置默认通道名（best-effort 便捷提交） */
    public static final String DEFAULT_CHANNEL = "default";

    /** 全局每 tick 释放预算：所有通道每 tick 释放的任务总数上限 */
    @Getter
    @Setter
    private volatile int globalBudgetPerTick = DEFAULT_GLOBAL_BUDGET;

    /** AUTO 通道空闲回收阈值：队列清空后空闲超过该 tick 数即自动回收 */
    @Getter
    @Setter
    private volatile int idleRecycleTicks = DEFAULT_IDLE_RECYCLE_TICKS;

    /** 任务执行异常处理器（null 时仅记录日志） */
    @Getter
    @Setter
    private Consumer<Exception> exceptionHandler = null;

    /** 关联插件，由 PluginAware 机制自动注入，用于 Nukkit 主线程调度 */
    @Getter
    private Plugin plugin;

    /** 通道注册表：name -> 通道（命名与匿名统一管理） */
    private final ConcurrentHashMap<String, ChannelImpl> channels = new ConcurrentHashMap<>();

    /** 匿名通道自增序号 */
    private final AtomicInteger autoIndex = new AtomicInteger(0);

    /** 全局待执行任务计数（挂起判定 O(1)） */
    private final AtomicInteger totalPending = new AtomicInteger(0);

    /** 调度互斥锁：挂起 / 唤醒的检查与状态变更 */
    private final Object schedLock = new Object();

    /** 当前 repeating task 句柄（null = 挂起）。仅 schedLock 内变更 */
    private TaskHandler handler;

    /** 调度时钟：drain 每执行一次 +1（提交线程读作 deadline 起算基准，volatile） */
    private volatile long currentTick = 0;

    // ==================== PluginAware ====================

    @Override
    public void bindPlugin(Plugin plugin) {
        this.plugin = plugin;
    }

    // ==================== 通道获取 ====================

    /**
     * 匿名申请通道（<b>MANUAL，默认</b>）：零自动管理开销，需显式 {@link ReleaseChannel#close()}
     * / {@link ReleaseChannel#abort()} 终结，适合有明确收尾点的批次 + 完成回调。
     *
     * @param policy 释放策略
     * @return 新的匿名 MANUAL 通道
     */
    public ReleaseChannel acquire(ReleasePolicy policy) {
        return acquire(policy, 0);
    }

    /**
     * 匿名申请通道（MANUAL）+ 指定背压上限。
     *
     * @param policy     释放策略
     * @param maxBacklog 背压上限（{@code <= 0} 表示不限）
     * @return 新的匿名 MANUAL 通道
     */
    public ReleaseChannel acquire(ReleasePolicy policy, int maxBacklog) {
        return newChannel(nextAutoName(), policy, maxBacklog, false);
    }

    /**
     * 匿名申请通道（<b>AUTO，显式</b>）：空闲自动回收 + 惰性复活。
     * <p>
     * AUTO 含自动管理开销（空闲回收扫描 / 复活记账），故须显式申请；
     * 适合 for 循环内每批一条、用完即弃、无法显式收尾的场景。
     *
     * @param policy 释放策略
     * @return 新的匿名 AUTO 通道
     */
    public ReleaseChannel acquireAuto(ReleasePolicy policy) {
        return acquireAuto(policy, 0);
    }

    /**
     * 匿名申请通道（AUTO）+ 指定背压上限。
     *
     * @param policy     释放策略
     * @param maxBacklog 背压上限（{@code <= 0} 表示不限）
     * @return 新的匿名 AUTO 通道
     */
    public ReleaseChannel acquireAuto(ReleasePolicy policy, int maxBacklog) {
        return newChannel(nextAutoName(), policy, maxBacklog, true);
    }

    /**
     * 获取 / 创建命名 best-effort 通道（等效 MANUAL 生命周期）。
     *
     * @param name 通道名
     * @return 该名称对应的通道（已存在则复用）
     */
    public ReleaseChannel channel(String name) {
        return channel(name, ReleasePolicy.bestEffort());
    }

    /**
     * 获取 / 创建指定策略命名通道（等效 MANUAL 生命周期）。
     *
     * @param name   通道名
     * @param policy 释放策略
     * @return 该名称对应的通道（已存在且策略相同则复用）
     * @throws IllegalArgumentException 同名通道已存在且策略不同时抛出
     */
    public ReleaseChannel channel(String name, ReleasePolicy policy) {
        return channel(name, policy, 0);
    }

    /**
     * 获取 / 创建指定策略 + 背压上限的命名通道（等效 MANUAL 生命周期）。
     *
     * @param name       通道名
     * @param policy     释放策略
     * @param maxBacklog 背压上限（{@code <= 0} 表示不限）
     * @return 该名称对应的通道
     * @throws IllegalArgumentException 同名通道已存在且策略不同时抛出
     */
    public ReleaseChannel channel(String name, ReleasePolicy policy, int maxBacklog) {
        ChannelImpl existing = channels.get(name);
        if (existing != null) {
            if (!existing.policy.equals(policy)) {
                throw new IllegalArgumentException(
                        "通道 '" + name + "' 已存在且策略为 " + existing.policy + "，与请求的 " + policy + " 不一致");
            }
            return existing;
        }
        return channels.computeIfAbsent(name, n -> createChannel(n, policy, maxBacklog, false));
    }

    /**
     * 注销命名通道（abort 语义：丢弃未执行任务、不触发回调）。
     *
     * @param name 通道名
     * @return {@code true} 存在并已注销
     */
    public boolean removeChannel(String name) {
        ChannelImpl ch = channels.get(name);
        if (ch == null) {
            return false;
        }
        ch.abort();
        return true;
    }

    // ==================== 便捷提交（内置 default 通道） ====================

    /**
     * 向内置 {@code default} 通道（best-effort）便捷提交任务。
     *
     * @param task 主线程任务
     */
    public void submit(Runnable task) {
        channel(DEFAULT_CHANNEL).submit(task);
    }

    /**
     * 向内置 {@code default} 通道非阻塞提交：背压满返回 {@code false}。
     *
     * @param task 主线程任务
     * @return {@code true} 入队成功
     */
    public boolean trySubmit(Runnable task) {
        return channel(DEFAULT_CHANNEL).trySubmit(task);
    }

    // ==================== 全局状态与关闭 ====================

    /**
     * 所有通道待执行任务总数。
     *
     * @return 待执行总数
     */
    public int size() {
        return totalPending.get();
    }

    /**
     * 是否全部空闲（无任何待执行任务）。
     *
     * @return {@code true} 空闲
     */
    public boolean isIdle() {
        return totalPending.get() == 0;
    }

    /**
     * 调度器是否处于挂起态（全部空闲时自动取消 repeating task，零调度开销）。
     *
     * @return {@code true} 挂起（无 repeating task 在调度）
     */
    public boolean isSuspended() {
        synchronized (schedLock) {
            return handler == null;
        }
    }

    /**
     * 清空全部通道的未执行任务（不清终结状态，不触发回调）。
     */
    public void clear() {
        for (ChannelImpl ch : channels.values()) {
            ch.clear();
        }
    }

    /**
     * 关闭服务（插件卸载时）：挂起调度并丢弃全部未执行任务，
     * 各通道当前批次 future 以正常方式完成（释放等待者）。
     */
    public void close() {
        synchronized (schedLock) {
            if (handler != null) {
                cancelHandler(handler);
                handler = null;
            }
        }
        for (ChannelImpl ch : channels.values()) {
            ch.discardAll();
        }
    }

    // ==================== 核心调度（drain，仅主线程；包级可见便于单测） ====================

    /**
     * 每 tick 仲裁：计算各通道配额 → 刚性 / 弹性两组仲裁 → 按配额 FIFO 出队执行 →
     * 完成处理（future / 回调 / 回收）→ 空闲则挂起。
     * <p>
     * 由 repeating task 每 tick 调用（{@link #scheduleRepeating}），
     * {@code protected} 供测试子类手动调用模拟 tick 推进（不必依赖真实 Nukkit 调度）。
     */
    protected void drain() {
        currentTick++;                                    // 时钟推进（空闲挂起期间不走）
        long ct = currentTick;

        // 1. 收集活跃通道（pending > 0），刷新 AUTO 通道活跃时刻（供空闲回收计时，
        //    在释放前刷新：本 tick 即活跃 tick，清空后的空闲计时从下一 tick 起算）
        List<ChannelImpl> active = new ArrayList<>();
        for (ChannelImpl ch : channels.values()) {
            if (ch.pending.get() > 0) {
                if (ch.autoRecycle) {
                    ch.lastActiveTick = ct;
                }
                active.add(ch);
            }
        }
        if (!active.isEmpty()) {
            releaseBatch(active, ct);
        }
        // 2. 完成处理：批次 future、close 回调、AUTO 回收
        postRelease(ct);
        // 3. 全部空闲 → 挂起（锁内二次检查防「先空后入队」丢唤醒）
        maybeSuspend();
    }

    /**
     * 对活跃通道做一次配额计算 + 仲裁 + 释放执行（主线程）。
     * <p>
     * 配额计算为纯函数式：通道快照进 → 配额表出（rigid 标记刚性组），
     * 便于独立单测。
     */
    private void releaseBatch(List<ChannelImpl> active, long ct) {
        record Quota(ChannelImpl ch, int quota, boolean rigid) {
        }

        int budget = globalBudgetPerTick;
        List<Quota> quotas = new ArrayList<>(active.size());
        int rigidDemand = 0;
        int elasticTotal = 0;

        for (ChannelImpl ch : active) {
            int p = ch.pending.get();
            int quota;
            boolean rigid;
            switch (ch.policy.kind()) {
                case DEADLINE -> {
                    long remain = ch.deadlineTick - ct;
                    if (remain > 0) {
                        quota = (int) Math.min(Integer.MAX_VALUE, (p + remain - 1) / remain); // ceil
                        rigid = true;
                    } else {
                        // 超期退化：违约已成事实，不再有速率要求，与弹性组一起分食剩余带宽
                        quota = p;
                        rigid = false;
                    }
                }
                case RATE -> {
                    ch.rateAccumulator += ch.policy.ratePerTick();
                    quota = Math.min((int) ch.rateAccumulator, p);
                    rigid = true;
                }
                default -> { // BEST_EFFORT
                    quota = p;
                    rigid = false;
                }
            }
            quotas.add(new Quota(ch, quota, rigid));
            int demand = Math.min(quota, p);
            if (rigid) {
                rigidDemand += demand;
            } else {
                elasticTotal += p;
            }
        }

        Map<ChannelImpl, Integer> actual = new HashMap<>();
        if (rigidDemand <= budget) {
            // 刚性组按配额足额释放
            int remainBudget = budget;
            for (Quota q : quotas) {
                if (q.rigid()) {
                    int n = Math.min(q.quota(), q.ch().pending.get());
                    actual.put(q.ch(), n);
                    remainBudget -= n;
                }
            }
            // 弹性组按 pending 比例分食剩余预算
            if (elasticTotal > 0 && remainBudget > 0) {
                for (Quota q : quotas) {
                    if (!q.rigid()) {
                        int p = q.ch().pending.get();
                        int share = (int) Math.round((double) remainBudget * p / elasticTotal);
                        share = Math.min(share, p);
                        actual.put(q.ch(), share);
                    }
                }
            }
        } else {
            // 刚性需求超预算：按目标速率比例缩放，保底 1 防饿死（误差 <= 活跃通道数）
            for (Quota q : quotas) {
                if (q.rigid()) {
                    int scaled = (int) Math.round((double) q.quota() * budget / rigidDemand);
                    actual.put(q.ch(), Math.max(1, scaled));
                }
            }
        }

        // 按配额逐通道 FIFO 出队执行；单任务异常捕获，不影响同批后续
        for (Quota q : quotas) {
            int n = actual.getOrDefault(q.ch(), 0);
            for (int i = 0; i < n; i++) {
                Runnable task = q.ch().tasks.poll();
                if (task == null) {
                    break;
                }
                q.ch().pending.decrementAndGet();
                totalPending.decrementAndGet();
                if (q.ch().backlog != null) {
                    q.ch().backlog.release(); // 消费即归还背压名额（唤醒阻塞的提交者）
                }
                if (q.ch().policy.kind() == ReleasePolicy.Kind.RATE) {
                    q.ch().rateAccumulator -= 1; // 按实际释放扣减（被缩放的差额保留到下 tick）
                }
                try {
                    task.run();
                } catch (Exception e) {
                    handleTaskError(e);
                }
            }
        }
    }

    /**
     * 释放后的完成处理（主线程）：批次 future 完成、close 回调派发、AUTO 空闲回收。
     */
    private void postRelease(long ct) {
        for (ChannelImpl ch : channels.values()) {
            if (ch.pending.get() > 0) {
                continue;
            }
            // pending == 0：当前批次落地 → 完成 future（快照语义）
            CompletableFuture<Void> f = ch.batchFuture;
            if (f != null && !f.isDone()) {
                f.complete(null);
            }
            // close 优雅关闭：排干后触发回调并注销（恰好一次）
            if (ch.closedFlag && !ch.abortedFlag && !ch.callbackFired) {
                fireCallback(ch);
                channels.remove(ch.name);
                continue;
            }
            // AUTO 空闲回收：清空后空闲超阈值 → 触发回调（视为该波完成）并注销
            if (ch.autoRecycle && !ch.closedFlag && ct - ch.lastActiveTick >= idleRecycleTicks) {
                fireCallback(ch);
                channels.remove(ch.name);
            }
        }
    }

    /**
     * 触发通道完成回调（主线程，恰好一次标记）。
     */
    private void fireCallback(ChannelImpl ch) {
        ch.callbackFired = true;
        Runnable cb = ch.onCompleteCallback;
        if (cb != null) {
            try {
                cb.run();
            } catch (Exception e) {
                logError(e);
            }
        }
    }

    /**
     * 全部空闲时挂起调度（锁内二次检查防「先空后入队」丢唤醒）。
     */
    private void maybeSuspend() {
        if (totalPending.get() == 0) {
            synchronized (schedLock) {
                if (totalPending.get() == 0 && handler != null) {
                    cancelHandler(handler);
                    handler = null;
                }
            }
        }
    }

    // ==================== 提交侧（任意线程，由 ChannelImpl 回调） ====================

    /**
     * 确保调度器运行（幂等，提交线程调用）。
     */
    void ensureScheduled() {
        synchronized (schedLock) {
            if (handler == null) {
                requirePluginReady();
                handler = scheduleRepeating();
            }
        }
    }

    /**
     * 通道重新注册（AUTO 被回收后惰性复活路径；putIfAbsent 保持幂等）。
     */
    void reRegisterIfAbsent(ChannelImpl ch) {
        if (channels.putIfAbsent(ch.name, ch) == null) {
            // 复活：重置回调触发标记与活跃计时，开启新生命周期
            ch.callbackFired = false;
            ch.lastActiveTick = currentTick;
        }
    }

    /**
     * 调度时钟当前值（提交线程读作 deadline 起算基准）。
     */
    long currentTick() {
        return currentTick;
    }

    // ==================== Nukkit 桥接（protected 便于单测覆盖） ====================

    /**
     * 注册每 tick 重复任务执行 {@link #drain()}。
     *
     * @return 任务句柄
     */
    protected TaskHandler scheduleRepeating() {
        return Server.getInstance().getScheduler().scheduleRepeatingTask(plugin, this::drain, 1);
    }

    /**
     * 取消重复任务句柄。
     *
     * @param handler 任务句柄
     */
    protected void cancelHandler(TaskHandler handler) {
        handler.cancel();
    }

    /**
     * 记录任务 / 回调执行异常。
     *
     * @param e 异常
     */
    protected void logError(Throwable e) {
        Server server = Server.getInstance();
        if (server != null) {
            server.getLogger().error("ReleaseAPI 任务执行异常", e);
        } else {
            e.printStackTrace();
        }
    }

    /**
     * 校验插件已绑定（调度依赖）。
     *
     * @throws IllegalStateException 插件未绑定时抛出
     */
    protected void requirePluginReady() {
        if (plugin == null) {
            throw new IllegalStateException("ReleaseAPI 插件尚未绑定，请先导入 async 模块（JFrameMain 自动绑定）");
        }
    }

    /**
     * 任务执行异常统一处理：优先 {@link #exceptionHandler}，否则记录日志。
     */
    private void handleTaskError(Exception e) {
        Consumer<Exception> h = exceptionHandler;
        if (h != null) {
            h.accept(e);
        } else {
            logError(e);
        }
    }

    // ==================== 内部工具 ====================

    private String nextAutoName() {
        return "auto-" + Integer.toHexString(autoIndex.incrementAndGet());
    }

    private ReleaseChannel newChannel(String name, ReleasePolicy policy, int maxBacklog, boolean autoRecycle) {
        ChannelImpl ch = createChannel(name, policy, maxBacklog, autoRecycle);
        channels.put(name, ch);
        return ch;
    }

    private ChannelImpl createChannel(String name, ReleasePolicy policy, int maxBacklog, boolean autoRecycle) {
        ChannelImpl ch = new ChannelImpl(name, policy, maxBacklog, autoRecycle);
        ch.lastActiveTick = currentTick;
        return ch;
    }

    // ==================== 通道实现 ====================

    /**
     * 通道实现。线程模型见类级说明：提交侧只写并发容器 / volatile / 计数器，
     * 配额与回收状态（rateAccumulator / lastActiveTick / callbackFired）仅主线程访问。
     */
    final class ChannelImpl implements ReleaseChannel {

        final String name;
        final ReleasePolicy policy;
        final int maxBacklog;
        final boolean autoRecycle;

        /** 任务队列：提交线程 add，主线程 poll */
        final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();

        /** 本通道待执行计数（O(1) size；空 -> 非空的 CAS 点即批次边界） */
        final AtomicInteger pending = new AtomicInteger(0);

        /** 背压信号量（null = 无限） */
        final Semaphore backlog;

        /** 显式终结标记（close / abort） */
        volatile boolean closedFlag = false;

        /** abort 标记（区分取消，回调不触发） */
        volatile boolean abortedFlag = false;

        /** 当前批次 future（快照语义；pending 0->1 时若已完成则换新） */
        volatile CompletableFuture<Void> batchFuture = new CompletableFuture<>();

        /** 完成回调（终结且排干时主线程触发一次；多次注册以最后为准） */
        volatile Runnable onCompleteCallback;

        /** DEADLINE：当前活跃期截止 tick（提交线程在批次起点写入） */
        volatile long deadlineTick;

        // ---- 以下仅主线程（drain）访问 ----
        double rateAccumulator = 0;
        long lastActiveTick;
        boolean callbackFired = false;

        ChannelImpl(String name, ReleasePolicy policy, int maxBacklog, boolean autoRecycle) {
            this.name = name;
            this.policy = policy;
            this.maxBacklog = maxBacklog;
            this.autoRecycle = autoRecycle;
            this.backlog = maxBacklog > 0 ? new Semaphore(maxBacklog) : null;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ReleasePolicy policy() {
            return policy;
        }

        @Override
        public void submit(Runnable task) {
            if (backlog != null) {
                backlog.acquireUninterruptibly();
            }
            if (!doSubmit(task)) {
                // 竞态：等待背压期间通道被终结——归还名额并拒绝
                if (backlog != null) {
                    backlog.release();
                }
                throw new IllegalStateException("通道 '" + name + "' 已终结，拒绝新提交");
            }
        }

        @Override
        public boolean trySubmit(Runnable task) {
            if (backlog != null && !backlog.tryAcquire()) {
                return false;
            }
            boolean ok = doSubmit(task);
            if (!ok && backlog != null) {
                backlog.release();
            }
            return ok;
        }

        /**
         * 入队核心：终结检查 → 惰性复活 → 批次边界处理（新 future / deadline 起算）→
         * 入队计数 → 唤醒调度。
         *
         * @return {@code true} 成功；{@code false} 通道已终结
         */
        private boolean doSubmit(Runnable task) {
            if (closedFlag) {
                return false;
            }
            // AUTO 惰性复活：被回收后重新注册（新生命周期）
            reRegisterIfAbsent(this);
            int now = pending.incrementAndGet();
            if (now == 1) {
                // 批次边界（空 -> 非空）：换新 future + deadline 起算
                if (batchFuture.isDone()) {
                    batchFuture = new CompletableFuture<>();
                }
                if (policy.kind() == ReleasePolicy.Kind.DEADLINE) {
                    deadlineTick = currentTick() + policy.durationTicks();
                }
            }
            totalPending.incrementAndGet();
            tasks.add(task);
            ensureScheduled();
            return true;
        }

        @Override
        public int size() {
            return pending.get();
        }

        @Override
        public void clear() {
            discardAll();
            // 唤醒一次 drain：postRelease 中完成当前批次 future（视为排干）
            ensureScheduled();
        }

        @Override
        public void onComplete(Runnable callback) {
            this.onCompleteCallback = callback;
        }

        @Override
        public CompletableFuture<Void> future() {
            return batchFuture;
        }

        @Override
        public void close() {
            if (closedFlag) {
                return;
            }
            closedFlag = true;
            if (pending.get() == 0) {
                // 队列已空：唤醒一次 drain 触发回调（排干条件已满足）
                ensureScheduled();
            }
            // pending > 0 时 drain 自然活跃，存量释放完毕后触发回调
        }

        @Override
        public void abort() {
            closedFlag = true;
            abortedFlag = true;
            discardAll();
            CompletableFuture<Void> f = batchFuture;
            if (!f.isDone()) {
                f.completeExceptionally(new ChannelAbortedException(name));
            }
            channels.remove(name);
        }

        @Override
        public boolean isClosed() {
            return closedFlag;
        }

        /**
         * 丢弃全部未执行任务：修正两级计数、释放背压名额。
         * 与 drain 的 poll 并发安全（各自只减自己取到的数目）。
         */
        private void discardAll() {
            int discarded = 0;
            while (tasks.poll() != null) {
                discarded++;
            }
            if (discarded > 0) {
                pending.addAndGet(-discarded);
                totalPending.addAndGet(-discarded);
                if (backlog != null) {
                    backlog.release(discarded);
                }
            }
        }
    }
}
