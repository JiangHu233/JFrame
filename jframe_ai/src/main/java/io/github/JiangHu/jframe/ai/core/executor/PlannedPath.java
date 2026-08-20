package io.github.JiangHu.jframe.ai.core.executor;

import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.ai.core.behavior.BehaviorOutcome;
import io.github.JiangHu.jframe.ai.core.behavior.ComputeCarrier;
import io.github.JiangHu.jframe.ai.core.behavior.ComputeCarriers;
import io.github.JiangHu.jframe.ai.core.behavior.ComputeTask;
import io.github.JiangHu.jframe.ai.core.gaze.Gaze;
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
 * <h3>连续模式的段重寻路线程模型</h3>
 * 段重寻路经 {@link ComputeCarrier} 派发(默认 {@link ComputeCarriers#sync()} 主线程同步,
 * 与旧版一致;可传 {@code ComputeCarriers.of(executor)} 挪出主线程,消除每段一次的主线程
 * A* 阻塞)。载体线程只做纯寻路计算;完成经 {@code complete/fail} 交还,本类保证回主线程
 * 后才执行导航器注册等实体写操作。回主线程后<b>重校验</b>计划存活与导航器所有权,
 * 异步期间被取代/停止的"僵尸段"结果直接丢弃。
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
    /** 视角修正器(null=导航器默认) */
    private final Gaze gaze;
    /** 段重寻路载体 */
    private final ComputeCarrier carrier;

    // ═══ 运行态 ═══
    private volatile Consumer<BehaviorOutcome> onComplete;
    private volatile boolean finished;
    /** 连续模式已走段数(主线程) */
    private int segments;
    /** 异步段寻路结果载体线程写、主线程读(volatile 保证可见性) */
    private volatile PathResult pendingSegment;

    PlannedPath(Entity self, Target target, PathfindingStrategy strategy, PathfindingConfig config,
                PathResult result, NavigatorManager navigators, double speed,
                boolean continuous, double reachRadius, int maxSegments,
                Gaze gaze, ComputeCarrier carrier) {
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
        this.gaze = gaze;
        this.carrier = carrier != null ? carrier : ComputeCarriers.sync();
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
        if (gaze != null) {
            // 视角修正器随段延续:同一实例跨段复用,平滑状态(SmoothGaze)不丢
            nav.gaze(gaze);
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
     * <p>
     * 同步部分完成全部"廉价检查"(存活/所有权/目标/到达/段数上限),
     * 通过后把寻路计算派发给 {@link ComputeCarrier}(可能在载体线程执行),
     * 完成后经 {@code onSegmentComputed} 回主线程落地。
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
        // 续算下一段(从实体当前位置):经载体派发,消除主线程同步 A* 阻塞
        segments++;
        dispatchNextSegment(goal);
    }

    /**
     * 派发一次段重寻路计算(主线程调用;计算本身在载体决定线程)。
     * <p>
     * 载体线程只执行纯寻路;结果经 {@code pendingSegment} 交接,
     * {@code complete/fail} 中 {@code postToMain} 回主线程落地。
     */
    private void dispatchNextSegment(Vector3 goal) {
        carrier.dispatch(new ComputeTask() {
            @Override
            public io.github.JiangHu.jframe.ai.core.behavior.LoopContext context() {
                return null; // 段重寻路无循环上下文
            }

            @Override
            public PlannedPath run() {
                // 载体线程纯计算:从实体快照位置寻向下一段
                pendingSegment = strategy.findPath(self.getLevel(), self, goal, config);
                return null; // 结果经 pendingSegment 交接,不走 complete 参数
            }

            @Override
            public void complete(PlannedPath plan) {
                postToMain(PlannedPath.this::onSegmentComputed);
            }

            @Override
            public void fail(Throwable error) {
                JFrameLog.error("PlannedPath", "AI 段重寻路异常: " + self, error);
                postToMain(() -> finish(BehaviorOutcome.PATH_FAILED));
            }
        });
    }

    /**
     * 段寻路完成落地(主线程;由 postToMain 保证)。
     * <p>
     * 重校验计划存活与导航器所有权——异步期间计划可能已被停止/取代(僵尸段),
     * 此时直接丢弃结果,不做任何实体写。
     */
    private void onSegmentComputed() {
        if (finished) {
            pendingSegment = null;
            return;
        }
        if (self.closed || !self.isAlive()) {
            pendingSegment = null;
            finish(BehaviorOutcome.STOPPED);
            return;
        }
        PathResult next = pendingSegment;
        pendingSegment = null;
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
}
