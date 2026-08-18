package io.github.JiangHu.jframe.ai.core.behavior;

import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.scheduler.TaskHandler;
import io.github.JiangHu.jframe.ai.core.executor.NavigationExecutor;
import io.github.JiangHu.jframe.ai.core.executor.PlannedPath;
import io.github.JiangHu.jframe.ai.core.navigation.Navigator;
import io.github.JiangHu.jframe.ai.core.navigation.NavigatorManager;
import io.github.JiangHu.jframe.ai.core.targeting.EntityTarget;
import io.github.JiangHu.jframe.ai.core.targeting.PointTarget;
import io.github.JiangHu.jframe.ai.core.targeting.Target;
import io.github.JiangHu.jframe.ai.pathfinding.PathResult;
import io.github.JiangHu.jframe.ai.pathfinding.PathfindingConfig;
import io.github.JiangHu.jframe.ai.pathfinding.PathfindingStrategy;
import io.github.JiangHu.jframe.core.JFrameLog;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 循环行为骨架:周期"计算 → 执行 → 判断"直至停止。
 * <p>
 * 与单次导航({@code ai.walk(e).to(t).compute().start()})同构配置——
 * {@code to}/{@code strategy}/{@code config}/{@code speed} 同一套参数;
 * 区别只在 walk 由调用方显式拆分 {@code compute()} 与 {@code start()} 两次调用,
 * 循环行为每轮"计算+执行"无法显式拆开,因此以<b>计算载体</b>
 * ({@link ComputeCarrier})把"计算在哪执行"的自由交回调用方。
 *
 * <h3>每轮循环(骨架调度)</h3>
 * <pre>
 * 主线程 tick 到点:
 *   上一轮计算未完成? → 跳过本轮等待
 *   上一轮计算完成   → 执行本轮路径(默认 plan.start())
 *                     → until(ctx) 判断 → true? 结束(ARRIVED)
 *                     → 派发下一轮计算:carrier.dispatch(task)
 * 载体线程(由载体决定,默认即主线程):解析 Target(失效→TARGET_LOST)
 *                     → 寻路计算(失败且无 partial→PATH_FAILED)
 *                     → task.complete(plan) → 骨架回主线程推进
 * </pre>
 *
 * <h3>线程约定</h3>
 * <ul>
 *   <li>{@code start()} 仅主线程;配置方法链式调用建议主线程(跨线程改参数由调用方同步)</li>
 *   <li>{@code computeFn} 在载体线程执行,必须纯计算(默认实现即 walk 的 compute,天然满足)</li>
 *   <li>{@code executeFn} 永远主线程(骨架强制);完成回调主线程触发,回调内可安全启动新行为</li>
 * </ul>
 *
 * <h3>行为取代</h3>
 * 同一实体启动新循环行为时,旧行为以 {@link BehaviorOutcome#STOPPED} 结束并收回移动权
 * (与 {@link NavigatorManager} 的导航替换语义一致)。
 */
public final class LoopBehavior {

    /** 实体 id → 运行中的循环行为(同实体唯一,新行为取代旧的) */
    private static final Map<Long, LoopBehavior> ACTIVE = new ConcurrentHashMap<>();

    /** 默认轮间隔(tick) */
    static final int DEFAULT_INTERVAL = 10;

    private final Entity self;
    private final PathfindingStrategy defaultStrategy;
    private final NavigatorManager navigators;

    // ═══ 配置(volatile:参数可中途改,跨线程修改由调用方同步) ═══
    private volatile Target target;
    private volatile PathfindingStrategy strategy;
    private volatile PathfindingConfig config;
    private volatile Double speed;
    private volatile int interval = DEFAULT_INTERVAL;
    private volatile Predicate<LoopContext> until;
    private volatile Consumer<BehaviorOutcome> onComplete;
    private volatile ComputeCarrier carrier = ComputeCarriers.sync();
    private volatile Function<LoopContext, PlannedPath> computeFn;
    private volatile BiConsumer<LoopContext, PlannedPath> executeFn;

    // ═══ 运行态(主线程读写;计数 volatile 供异步 computeFn 经 context 读取) ═══
    private volatile boolean running;
    private volatile boolean computing;
    private volatile long ticks;
    private volatile int rounds;
    private long lastRoundTick;
    private PlannedPath pendingPlan;
    private volatile PlannedPath lastPlan;
    private volatile PathResult lastResult;
    private TaskHandler taskHandler;

    /** 只读上下文视图(单例复用,读当前值) */
    private final LoopContext context = new ContextView();
    /** 句柄视图(单例复用) */
    private final BehaviorHandle handle = new HandleView();

    /**
     * 构造循环行为(经 {@code ai.loop(entity)} 工厂创建)。
     *
     * @param self            行为主体
     * @param defaultStrategy 默认寻路策略(未 {@code strategy(...)} 覆盖时使用)
     * @param navigators      导航管理器(执行计划与移动权收回)
     */
    public LoopBehavior(Entity self, PathfindingStrategy defaultStrategy, NavigatorManager navigators) {
        this.self = Objects.requireNonNull(self, "self");
        this.defaultStrategy = Objects.requireNonNull(defaultStrategy, "defaultStrategy");
        this.navigators = Objects.requireNonNull(navigators, "navigators");
    }

    // ═══════════ 与 walk 同构的配置(主形态) ═══════════

    /**
     * 每轮目标(活引用,可中途换目标——每轮计算时重新解析)。
     *
     * @param target 目标引用
     * @return this
     */
    public LoopBehavior to(Target target) {
        this.target = Objects.requireNonNull(target, "target");
        return this;
    }

    /**
     * 每轮目标(静态点重载,等价 {@code to(new PointTarget(pos))})。
     *
     * @param pos 目标点
     * @return this
     */
    public LoopBehavior to(Vector3 pos) {
        return to(new PointTarget(pos));
    }

    /**
     * 每轮目标(实体重载,等价 {@code to(new EntityTarget(entity))})。
     *
     * @param entity 目标实体
     * @return this
     */
    public LoopBehavior target(Entity entity) {
        return to(new EntityTarget(entity));
    }

    /**
     * 覆盖默认寻路策略。
     *
     * @param strategy 策略
     * @return this
     */
    public LoopBehavior strategy(PathfindingStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        return this;
    }

    /**
     * 算法参数。
     *
     * @param config 参数
     * @return this
     */
    public LoopBehavior config(PathfindingConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        return this;
    }

    /**
     * 行进速度(方块/tick)。
     *
     * @param speed 速度
     * @return this
     */
    public LoopBehavior speed(double speed) {
        this.speed = speed;
        return this;
    }

    // ═══════════ 循环专属 ═══════════

    /**
     * 轮间隔(tick)。默认 {@value #DEFAULT_INTERVAL}。
     *
     * @param ticks 间隔,至少 1
     * @return this
     */
    public LoopBehavior interval(int ticks) {
        if (ticks < 1) {
            throw new IllegalArgumentException("interval 必须至少 1 tick: " + ticks);
        }
        this.interval = ticks;
        return this;
    }

    /**
     * 停止判断:每轮执行后调用,返回 true 则行为以 {@link BehaviorOutcome#ARRIVED} 结束。
     *
     * @param stopFn 停止判断;null 表示永不主动停止(只能 stop()/目标失效/寻路失败结束)
     * @return this
     */
    public LoopBehavior until(Predicate<LoopContext> stopFn) {
        this.until = stopFn;
        return this;
    }

    /**
     * 完成回调(主线程触发,状态转移入口)。
     *
     * @param cb 回调
     * @return this
     */
    public LoopBehavior onComplete(Consumer<BehaviorOutcome> cb) {
        this.onComplete = Objects.requireNonNull(cb, "cb");
        return this;
    }

    // ═══════════ 计算载体:循环版的"compute() 在哪个线程调" ═══════════

    /**
     * 指定计算载体。默认 {@link ComputeCarriers#sync()}(主线程同步,行为与旧版一致)。
     *
     * @param carrier 载体
     * @return this
     */
    public LoopBehavior carrier(ComputeCarrier carrier) {
        this.carrier = Objects.requireNonNull(carrier, "carrier");
        return this;
    }

    /**
     * 线程池载体便捷糖,等价 {@code carrier(ComputeCarriers.of(executor))}。
     *
     * @param executor 外部线程池
     * @return this
     */
    public LoopBehavior onExecutor(Executor executor) {
        return carrier(ComputeCarriers.of(executor));
    }

    // ═══════════ 高级覆盖(可选;默认即 walk 的寻路与执行) ═══════════

    /**
     * 覆盖每轮计算函数(载体线程执行,必须纯计算)。
     * <p>
     * 默认实现 = walk 的 compute:解析 {@code ctx.target()} + 寻路。
     * 返回 {@code null} 约定为目标失效(TARGET_LOST)。
     *
     * @param computeFn 计算函数
     * @return this
     */
    public LoopBehavior compute(Function<LoopContext, PlannedPath> computeFn) {
        this.computeFn = Objects.requireNonNull(computeFn, "computeFn");
        return this;
    }

    /**
     * 覆盖每轮执行函数(主线程执行,由骨架强制)。
     * <p>
     * 默认实现 = walk 的 start:{@code plan.start()}。
     *
     * @param executeFn 执行函数
     * @return this
     */
    public LoopBehavior execute(BiConsumer<LoopContext, PlannedPath> executeFn) {
        this.executeFn = Objects.requireNonNull(executeFn, "executeFn");
        return this;
    }

    // ═══════════ 启动与驱动 ═══════════

    /**
     * 启动行为(仅主线程):注册调度、取代同实体旧行为、派发首轮计算。
     *
     * @return 行为句柄
     * @throws IllegalStateException 未配置目标且未覆盖 compute 函数,或行为已启动
     */
    public BehaviorHandle start() {
        if (running) {
            throw new IllegalStateException("行为已启动");
        }
        if (target == null && computeFn == null) {
            throw new IllegalStateException("未配置目标:请先 to(...)/target(...) 或覆盖 compute(...)");
        }
        running = true;
        computing = false;
        ticks = 0;
        rounds = 0;
        // 循环错峰:首轮执行延迟随机 0~interval-1 tick,
        // 大量实体同时启动游荡/循环时重算不同 tick 集中,负载摊平
        lastRoundTick = -ThreadLocalRandom.current().nextInt(interval);
        pendingPlan = null;
        lastPlan = null;
        lastResult = null;

        LoopBehavior old = ACTIVE.put(self.getId(), this);
        if (old != null) {
            old.finish(BehaviorOutcome.STOPPED);
        }
        ensureScheduled();
        dispatchRound();
        return handle;
    }

    /**
     * 单 tick 推进(调度器每 tick 调用;测试可手动驱动)。
     */
    void tick() {
        if (!running) {
            return;
        }
        ticks++;
        if (computing || pendingPlan == null) {
            return;
        }
        if (ticks - lastRoundTick < interval) {
            return;
        }
        // 到点:执行本轮计划
        PlannedPath plan = pendingPlan;
        pendingPlan = null;
        lastPlan = plan;
        lastResult = plan.getResult();
        lastRoundTick = ticks;
        rounds++;
        executeCurrent(plan);
        if (!running) {
            return;
        }
        Predicate<LoopContext> stop = until;
        if (stop != null) {
            try {
                if (stop.test(context)) {
                    finish(BehaviorOutcome.ARRIVED);
                    return;
                }
            } catch (Exception e) {
                JFrameLog.error("LoopBehavior", "AI until 判断异常: " + self, e);
                finish(BehaviorOutcome.PATH_FAILED);
                return;
            }
        }
        dispatchRound();
    }

    /**
     * 执行本轮计划(主线程)。
     */
    private void executeCurrent(PlannedPath plan) {
        BiConsumer<LoopContext, PlannedPath> fn = executeFn;
        try {
            if (fn != null) {
                fn.accept(context, plan);
            } else {
                plan.start();
            }
        } catch (Exception e) {
            JFrameLog.error("LoopBehavior", "AI 循环执行异常: " + self, e);
            finish(BehaviorOutcome.PATH_FAILED);
        }
    }

    /**
     * 派发下一轮计算。
     */
    private void dispatchRound() {
        if (!running) {
            return;
        }
        computing = true;
        carrier.dispatch(new TaskView());
    }

    /**
     * 计算完成推进(主线程;由 {@code postToMain} 保证)。
     */
    private void onComputed(PlannedPath plan) {
        if (!running) {
            return;
        }
        computing = false;
        if (plan == null) {
            finish(BehaviorOutcome.TARGET_LOST);
            return;
        }
        if (!plan.hasPath()) {
            finish(BehaviorOutcome.PATH_FAILED);
            return;
        }
        pendingPlan = plan;
    }

    /**
     * 默认计算 = walk 的 compute:解析目标 + 寻路(载体线程,纯计算)。
     */
    private PlannedPath defaultCompute() {
        NavigationExecutor executor = new NavigationExecutor(self, defaultStrategy, navigators);
        if (strategy != null) {
            executor.strategy(strategy);
        }
        if (config != null) {
            executor.config(config);
        }
        if (speed != null) {
            executor.speed(speed);
        }
        return executor.to(target).compute();
    }

    /**
     * 结束行为(主线程):取消调度、收回移动权、触发完成回调。
     */
    private void finish(BehaviorOutcome outcome) {
        if (!running) {
            return;
        }
        running = false;
        ACTIVE.remove(self.getId(), this);
        if (taskHandler != null) {
            taskHandler.cancel();
            taskHandler = null;
        }
        navigators.stop(self);
        Consumer<BehaviorOutcome> cb = onComplete;
        if (cb != null) {
            try {
                cb.accept(outcome);
            } catch (Exception e) {
                JFrameLog.error("LoopBehavior", "AI 行为完成回调异常: " + self, e);
            }
        }
    }

    /**
     * 回主线程执行:已在主线程直接跑;否则经 ServerScheduler 调度;
     * 无调度环境(测试)兜底当前线程直接跑。
     */
    private void postToMain(Runnable action) {
        Server server = Server.getInstance();
        if (server != null && !server.isPrimaryThread()) {
            Plugin plugin = navigators.getPlugin();
            if (plugin != null) {
                server.getScheduler().scheduleTask(plugin, action);
                return;
            }
        }
        action.run();
    }

    /**
     * 注册每 tick 调度(plugin 未绑定或无 Server 时跳过——测试可手动 tick)。
     */
    private void ensureScheduled() {
        Plugin plugin = navigators.getPlugin();
        Server server = Server.getInstance();
        if (plugin == null || server == null) {
            return;
        }
        taskHandler = server.getScheduler().scheduleRepeatingTask(plugin, () -> {
            try {
                tick();
            } catch (Exception e) {
                JFrameLog.error("LoopBehavior", "AI 循环 tick 异常: " + self, e);
            }
        }, 1);
    }

    // ═══════════ 静态查询(AiAPI 便捷入口) ═══════════

    /**
     * 实体是否有运行中的循环行为。
     *
     * @param entity 实体
     * @return true 表示存在
     */
    public static boolean isRunning(Entity entity) {
        return entity != null && ACTIVE.containsKey(entity.getId());
    }

    /**
     * 获取实体运行中的循环行为句柄。
     *
     * @param entity 实体
     * @return 句柄;无运行为时为 null
     */
    public static BehaviorHandle activeBehavior(Entity entity) {
        LoopBehavior behavior = entity == null ? null : ACTIVE.get(entity.getId());
        return behavior == null ? null : behavior.handle;
    }

    /**
     * 停止所有运行中的循环行为(各行为以 {@link BehaviorOutcome#STOPPED} 终态结束)。
     * 遍历快照避免并发修改;单个停止动作经 postToMain 回主线程执行。
     */
    public static void stopAll() {
        for (LoopBehavior behavior : ACTIVE.values().toArray(new LoopBehavior[0])) {
            behavior.handle.stop();
        }
    }

    // ═══════════ 视图 ═══════════

    /** 只读上下文视图(读当前值) */
    private final class ContextView implements LoopContext {
        @Override
        public Entity self() {
            return self;
        }

        @Override
        public long ticks() {
            return ticks;
        }

        @Override
        public int rounds() {
            return rounds;
        }

        @Override
        public PathResult lastResult() {
            return lastResult;
        }

        @Override
        public PlannedPath lastPlan() {
            return lastPlan;
        }

        @Override
        public Navigator navigator() {
            return navigators.getNavigator(self);
        }

        @Override
        public Target target() {
            return LoopBehavior.this.target;
        }
    }

    /** 行为句柄视图 */
    private final class HandleView implements BehaviorHandle {
        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void stop() {
            postToMain(() -> {
                if (running) {
                    finish(BehaviorOutcome.STOPPED);
                }
            });
        }
    }

    /** 载体任务视图:连接载体回调与骨架推进 */
    private final class TaskView implements ComputeTask {
        @Override
        public LoopContext context() {
            return context;
        }

        @Override
        public PlannedPath run() throws Exception {
            Function<LoopContext, PlannedPath> fn = computeFn;
            return fn != null ? fn.apply(context) : defaultCompute();
        }

        @Override
        public void complete(PlannedPath plan) {
            postToMain(() -> onComputed(plan));
        }

        @Override
        public void fail(Throwable error) {
            JFrameLog.error("LoopBehavior", "AI 循环计算异常: " + self, error);
            postToMain(() -> finish(BehaviorOutcome.PATH_FAILED));
        }
    }
}
