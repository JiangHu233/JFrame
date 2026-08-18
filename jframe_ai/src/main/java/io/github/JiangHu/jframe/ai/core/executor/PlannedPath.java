package io.github.JiangHu.jframe.ai.core.executor;

import cn.nukkit.entity.Entity;
import cn.nukkit.math.Vector3;
import io.github.JiangHu.jframe.ai.core.behavior.BehaviorOutcome;
import io.github.JiangHu.jframe.ai.core.navigation.Navigator;
import io.github.JiangHu.jframe.ai.core.navigation.NavigatorManager;
import io.github.JiangHu.jframe.ai.core.targeting.Target;
import io.github.JiangHu.jframe.ai.pathfinding.PathResult;
import io.github.JiangHu.jframe.ai.pathfinding.PathfindingConfig;
import io.github.JiangHu.jframe.ai.pathfinding.PathfindingStrategy;
import io.github.JiangHu.jframe.core.JFrameLog;

import java.util.function.Consumer;

/**
 * 已计算态路径:持有一次寻路结果与全部执行配置,是执行器贯通链的中点。
 * <p>
 * 由 {@link NavigationExecutor#compute()} 产出(任意线程),不可变、可跨线程传递与缓存;
 * {@link #start()} 回归主线程提交 {@link NavigatorManager} 执行。
 *
 * <h3>两种行为模式</h3>
 * <ul>
 *   <li><b>单次</b>(默认):走完本段即结束(ARRIVED);被同实体新导航取代时以 STOPPED 结束</li>
 *   <li><b>连续</b>({@code continuous()}):走完一段后从最新位置续算下一段,
 *       直至进入到达半径(ARRIVED)/目标失效(TARGET_LOST)/续算失败或段数超限(PATH_FAILED)</li>
 * </ul>
 *
 * <h3>完成回调</h3>
 * {@link #onComplete(Consumer)} 任意线程可注册,主线程触发(行为结束点位于 tick 调度内),
 * 回调内可安全启动新行为(状态转移)。
 */
public final class PlannedPath {

    // ═══ 不可变:计算结果 + 执行配置 ═══
    private final Entity self;
    private final Target target;
    private final PathfindingStrategy strategy;
    private final PathfindingConfig config;
    private final PathResult result;
    private final NavigatorManager navigators;
    private final double speed;
    private final boolean continuous;
    private final double reachRadius;
    private final int maxSegments;

    // ═══ 运行态 ═══
    private volatile Consumer<BehaviorOutcome> onComplete;
    private volatile boolean finished;
    /** 连续模式已走段数(主线程) */
    private int segments;

    PlannedPath(Entity self, Target target, PathfindingStrategy strategy, PathfindingConfig config,
                PathResult result, NavigatorManager navigators, double speed,
                boolean continuous, double reachRadius, int maxSegments) {
        this.self = self;
        this.target = target;
        this.strategy = strategy;
        this.config = config;
        this.result = result;
        this.navigators = navigators;
        this.speed = speed;
        this.continuous = continuous;
        this.reachRadius = reachRadius;
        this.maxSegments = maxSegments;
    }

    /**
     * 寻路结果(不可变,任意线程可读)。
     *
     * @return 结果(SUCCESS / PARTIAL / 失败状态)
     */
    public PathResult getResult() {
        return result;
    }

    /**
     * 是否有可走路径(SUCCESS 或 PARTIAL)。
     *
     * @return true 表示可执行
     */
    public boolean hasPath() {
        return result != null && result.hasPath();
    }

    /**
     * 注册完成回调(任意线程;主线程触发)。
     *
     * @param cb 回调,接收结束原因
     * @return this
     */
    public PlannedPath onComplete(Consumer<BehaviorOutcome> cb) {
        this.onComplete = cb;
        return this;
    }

    /**
     * 执行计划(仅主线程):提交 {@link NavigatorManager} 走计算出的路径。
     * <ul>
     *   <li>单次模式:直接走本段</li>
     *   <li>连续模式:走完自动续算,直至到达/失败/被取代</li>
     * </ul>
     * 被同实体新导航取代时,本计划以 {@link BehaviorOutcome#STOPPED} 结束。
     *
     * @return 首段导航器;计划不可执行或已结束时为 null
     */
    public Navigator start() {
        if (finished) {
            return null;
        }
        if (!hasPath()) {
            finish(BehaviorOutcome.PATH_FAILED);
            return null;
        }
        if (continuous) {
            segments = 1;
            return navigateSegment(result);
        }
        Navigator nav = navigators.navigateOrPartial(self, result, speed);
        if (nav == null) {
            finish(BehaviorOutcome.PATH_FAILED);
            return null;
        }
        if (nav.isFinished()) {
            // 空路径(已在目标):构造即完成,回调不会经 tick 触发
            finish(BehaviorOutcome.ARRIVED);
            return nav;
        }
        nav.onComplete(n -> {
            if (navigators.getNavigator(self) == n) {
                finish(BehaviorOutcome.ARRIVED);      // 自然走完本段
            } else {
                finish(BehaviorOutcome.STOPPED);      // 被同实体新导航取代
            }
        });
        return nav;
    }

    /**
     * 连续模式:提交一段并挂续算链(主线程)。
     */
    private Navigator navigateSegment(PathResult segment) {
        Navigator nav = navigators.navigateOrPartial(self, segment, speed);
        if (nav == null) {
            finish(BehaviorOutcome.PATH_FAILED);
            return null;
        }
        if (nav.isFinished()) {
            // 空路径:视作本段走完,进入到达检查
            onSegmentWalked(nav);
            return nav;
        }
        nav.onComplete(this::onSegmentWalked);
        return nav;
    }

    /**
     * 连续模式:一段走完后的续算决策(主线程)。
     */
    private void onSegmentWalked(Navigator nav) {
        if (finished) {
            return;
        }
        if (navigators.getNavigator(self) != nav) {
            finish(BehaviorOutcome.STOPPED);          // 被同实体新导航取代
            return;
        }
        // 实体失效
        if (self.closed || !self.isAlive()) {
            finish(BehaviorOutcome.STOPPED);
            return;
        }
        // 重解析目标(活引用:可中途换目标)
        Vector3 goal = target.get();
        if (goal == null) {
            finish(BehaviorOutcome.TARGET_LOST);
            return;
        }
        // 到达检查(水平距离)
        double dx = goal.x - self.x;
        double dz = goal.z - self.z;
        if (dx * dx + dz * dz <= reachRadius * reachRadius) {
            finish(BehaviorOutcome.ARRIVED);
            return;
        }
        // 段数上限
        if (segments >= maxSegments) {
            finish(BehaviorOutcome.PATH_FAILED);
            return;
        }
        // 续算下一段(从实体当前位置)
        segments++;
        PathResult next = strategy.findPath(self.getLevel(), self, goal, config);
        if (next == null || !next.hasPath()) {
            finish(BehaviorOutcome.PATH_FAILED);
            return;
        }
        navigateSegment(next);
    }

    /**
     * 结束计划(幂等;主线程):触发完成回调。
     */
    private void finish(BehaviorOutcome outcome) {
        if (finished) {
            return;
        }
        finished = true;
        Consumer<BehaviorOutcome> cb = onComplete;
        if (cb != null) {
            try {
                cb.accept(outcome);
            } catch (Exception e) {
                JFrameLog.error("PlannedPath", "AI 路径完成回调异常: " + self, e);
            }
        }
    }
}
