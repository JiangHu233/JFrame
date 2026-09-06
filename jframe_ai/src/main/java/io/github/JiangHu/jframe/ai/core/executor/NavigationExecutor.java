package io.github.JiangHu.jframe.ai.core.executor;

import cn.nukkit.entity.Entity;
import cn.nukkit.math.Vector3;
import io.github.JiangHu.jframe.ai.core.behavior.ComputeCarrier;
import io.github.JiangHu.jframe.ai.core.behavior.ComputeCarriers;
import io.github.JiangHu.jframe.ai.core.gaze.Gaze;
import io.github.JiangHu.jframe.ai.core.navigation.Navigator;
import io.github.JiangHu.jframe.ai.core.navigation.NavigatorManager;
import io.github.JiangHu.jframe.ai.core.targeting.EntityTarget;
import io.github.JiangHu.jframe.ai.core.targeting.PointTarget;
import io.github.JiangHu.jframe.ai.core.targeting.Target;
import io.github.JiangHu.jframe.ai.pathfinding.PathResult;
import io.github.JiangHu.jframe.ai.pathfinding.PathfindingConfig;
import io.github.JiangHu.jframe.ai.pathfinding.PathfindingStrategy;

import java.util.Objects;

/**
 * 导航执行器(配置态):单次/连续导航的链式入口,执行器贯通链的起点。
 * <p>
 * 链分两个状态对象,{@link #compute()} 与 {@link #start()} 之间即线程切换点:
 * <pre>{@code
 * // ═══ 配置态:配置战术目标 / 行为模式 / 算法 ═══
 * PlannedPath plan = ai.walk(zombie)
 *         .to(new CoverTarget(zombie).threat(player).radius(8))  // 战术目标(或 to(Vector3) 重载)
 *         .strategy(greedy)                        // 可选:覆盖默认算法
 *         .config(opts)                            // 可选:算法参数
 *         .continuous()                            // 可选:行为模式——走完自动续算
 *         .reachRadius(1.5)                        // 可选:连续模式到达半径
 *         .maxSegments(100)                        // 可选:续算段数上限
 *         .speed(0.3)
 *         .compute();                              // ← 链式计算:任意线程可调
 *
 * // ═══ 已计算态:PlannedPath(不可变,可跨线程传递/缓存) ═══
 * PathResult r = plan.getResult();
 *
 * // ═══ 执行:回归主线程 ═══
 * Navigator nav = plan.start();                    // ← 链式执行:仅主线程
 *
 * // ═══ 同步组合便捷(主线程一次完成,适合小计算量) ═══
 * ai.walk(npc).to(pos).start();                    // = compute().start()
 * }</pre>
 *
 * @see PlannedPath
 */
public final class NavigationExecutor {

    /** 连续模式默认到达半径(方块,水平距离) */
    public static final double DEFAULT_REACH_RADIUS = 1.5;
    /** 连续模式默认续算段数上限(防止无限循环) */
    public static final int DEFAULT_MAX_SEGMENTS = 100;

    private final Entity self;
    private final PathfindingStrategy defaultStrategy;
    private final NavigatorManager navigators;

    private Target target;
    private PathfindingStrategy strategy;
    private PathfindingConfig config;
    private Double speed;
    private boolean continuous;
    private double reachRadius = DEFAULT_REACH_RADIUS;
    private int maxSegments = DEFAULT_MAX_SEGMENTS;
    /** 视角修正器(null=导航器默认 MovementGaze) */
    private Gaze gaze;
    /** 连续模式段重寻路载体(默认主线程同步) */
    private ComputeCarrier carrier = ComputeCarriers.sync();

    /**
     * 构造导航执行器(经 {@code ai.walk(entity)} 工厂创建)。
     *
     * @param self            行为主体
     * @param defaultStrategy 默认寻路策略(未 {@code strategy(...)} 覆盖时使用)
     * @param navigators      导航管理器(执行计划)
     */
    public NavigationExecutor(Entity self, PathfindingStrategy defaultStrategy, NavigatorManager navigators) {
        this.self = Objects.requireNonNull(self, "self");
        this.defaultStrategy = Objects.requireNonNull(defaultStrategy, "defaultStrategy");
        this.navigators = Objects.requireNonNull(navigators, "navigators");
    }

    /**
     * 导航目标(战术目标活引用或静态点)。
     *
     * @param target 目标引用
     * @return this
     */
    public NavigationExecutor to(Target target) {
        this.target = Objects.requireNonNull(target, "target");
        return this;
    }

    /**
     * 导航目标(静态点重载,等价 {@code to(new PointTarget(pos))})。
     *
     * @param pos 目标点
     * @return this
     */
    public NavigationExecutor to(Vector3 pos) {
        return to(new PointTarget(pos));
    }

    /**
     * 导航目标(实体重载,等价 {@code to(new EntityTarget(entity))})。
     *
     * @param entity 目标实体
     * @return this
     */
    public NavigationExecutor target(Entity entity) {
        return to(new EntityTarget(entity));
    }

    /**
     * 覆盖默认寻路策略。
     *
     * @param strategy 策略
     * @return this
     */
    public NavigationExecutor strategy(PathfindingStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        return this;
    }

    /**
     * 算法参数(未设置时使用策略默认配置)。
     *
     * @param config 参数
     * @return this
     */
    public NavigationExecutor config(PathfindingConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        return this;
    }

    /**
     * 行进速度(方块/tick)。默认 {@link Navigator#DEFAULT_SPEED}。
     *
     * @param speed 速度
     * @return this
     */
    public NavigationExecutor speed(double speed) {
        this.speed = speed;
        return this;
    }

    /**
     * 开启连续模式:走完一段后自动续算下一段,直至到达目标。
     * <p>
     * 适合贪心等短视策略串联长距离导航,也适合目标缓慢漂移的场景。
     *
     * @return this
     */
    public NavigationExecutor continuous() {
        this.continuous = true;
        return this;
    }

    /**
     * 连续模式到达半径(水平距离,默认 {@value #DEFAULT_REACH_RADIUS})。
     *
     * @param radius 半径(方块)
     * @return this
     */
    public NavigationExecutor reachRadius(double radius) {
        if (radius <= 0) {
            throw new IllegalArgumentException("reachRadius 必须为正: " + radius);
        }
        this.reachRadius = radius;
        return this;
    }

    /**
     * 连续模式续算段数上限(默认 {@value #DEFAULT_MAX_SEGMENTS},防止无限循环)。
     *
     * @param segments 段数上限
     * @return this
     */
    public NavigationExecutor maxSegments(int segments) {
        if (segments < 1) {
            throw new IllegalArgumentException("maxSegments 必须至少 1: " + segments);
        }
        this.maxSegments = segments;
        return this;
    }

    /**
     * 视角修正器:决定行进中实体头/身的朝向表现。
     * <p>
     * 默认不设置(导航器使用 {@code MovementGaze}:头身同向朝移动方向,
     * 同步写 headYaw 修复侧头问题)。可选 {@code TargetGaze}(头看目标)、
     * {@code FixedGaze}(固定朝向),或 {@code SmoothGaze} 装饰任何修正器
     * 获得限速转身与反应延迟的拟人效果。
     * <p>
     * 同一修正器实例随导航器独享,连续模式跨段复用(平滑状态延续)。
     *
     * @param gaze 修正器
     * @return this
     */
    public NavigationExecutor gaze(Gaze gaze) {
        this.gaze = Objects.requireNonNull(gaze, "gaze");
        return this;
    }

    /**
     * 连续模式段重寻路载体:决定"走完一段后续算下一段"的寻路计算在哪个线程执行。
     * <p>
     * 默认 {@link ComputeCarriers#sync()}(主线程同步,与旧版一致);
     * 传入 {@code ComputeCarriers.of(executor)} 可把段重寻路挪出主线程,
     * 消除每段一次的主线程 A* 阻塞。框架不持有线程资源,载体由外部提供。
     *
     * @param carrier 计算载体
     * @return this
     */
    public NavigationExecutor carrier(ComputeCarrier carrier) {
        this.carrier = Objects.requireNonNull(carrier, "carrier");
        return this;
    }

    /**
     * 计算路径(任意线程可调,纯计算:解析目标 + 寻路,不碰实体写/调度)。
     *
     * @return 已计算态路径;目标失效({@code Target.get()} 返回 null)时返回 null
     * @throws IllegalStateException 未配置目标
     */
    public PlannedPath compute() {
        if (target == null) {
            throw new IllegalStateException("未配置目标:请先 to(...)/target(...)");
        }
        Vector3 goal = target.get();
        if (goal == null) {
            return null;
        }
        PathfindingStrategy actual = strategy != null ? strategy : defaultStrategy;
        PathfindingConfig actualConfig = config != null ? config : actual.getDefaultConfig();
        PathResult result = actual.findPath(self.getLevel(), self, goal, actualConfig);
        return new PlannedPath(self, target, actual, actualConfig, result, navigators,
                speed != null ? speed : Navigator.DEFAULT_SPEED,
                continuous, reachRadius, maxSegments, gaze, carrier);
    }

    /**
     * 同步组合便捷(仅主线程):等价 {@code compute().start()}。
     *
     * @return 首段导航器;目标失效或路径不可执行时为 null
     */
    public Navigator start() {
        PlannedPath plan = compute();
        return plan == null ? null : plan.start();
    }
}
