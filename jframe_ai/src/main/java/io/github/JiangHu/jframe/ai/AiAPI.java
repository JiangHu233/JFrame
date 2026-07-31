package io.github.JiangHu.jframe.ai;

import cn.nukkit.entity.Entity;
import cn.nukkit.item.Item;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.ai.combat.CombatActions;
import io.github.JiangHu.jframe.ai.navigation.AnytimePathFinder;
import io.github.JiangHu.jframe.ai.navigation.ContinuousNavigator;
import io.github.JiangHu.jframe.ai.navigation.Navigator;
import io.github.JiangHu.jframe.ai.navigation.NavigatorManager;
import io.github.JiangHu.jframe.ai.navigation.WanderBehavior;
import io.github.JiangHu.jframe.ai.pathfinding.BlockNode;
import io.github.JiangHu.jframe.ai.pathfinding.GreedyOptions;
import io.github.JiangHu.jframe.ai.pathfinding.GreedyPathFinder;
import io.github.JiangHu.jframe.ai.pathfinding.PathFinder;
import io.github.JiangHu.jframe.ai.pathfinding.PathfinderOptions;
import io.github.JiangHu.jframe.ai.pathfinding.PathResult;
import io.github.JiangHu.jframe.ai.tactical.FormationType;
import io.github.JiangHu.jframe.ai.tactical.TacticalPosition;
import io.github.JiangHu.jframe.ai.tactical.TacticalScanner;
import io.github.JiangHu.jframe.ai.tactical.TeamTactics;
import io.github.JiangHu.jframe.ai.util.PathVisualizer;
import io.github.JiangHu.jframe.ai.util.VisionSensor;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * AI 服务（<b>面向用户的统一入口</b>）：暴露 AI 模块的全部公开 API。
 * <p>
 * 本类是一个轻量门面（facade），自身不持有业务逻辑，仅做<b>委托转发</b>，将四大子能力
 * 聚合为一站式 API：
 * <ul>
 *   <li><b>寻路</b> → {@link PathFinder}（A* 启发式方块寻路，支持外接评分器与边走边搜）</li>
 *   <li><b>导航</b> → {@link NavigatorManager}（驱动实体沿路径移动、调度）</li>
 *   <li><b>追逐</b> → {@link AnytimePathFinder}（边走边搜追逐移动目标，即使无解也能逼近）</li>
 *   <li><b>游荡</b> → {@link WanderBehavior}（半径内随机巡游状态机）</li>
 *   <li><b>单实体战术</b> → {@link TacticalScanner}（找掩体/远离/侧翼/寻找高地）</li>
 *   <li><b>团队战术</b> → {@link TeamTactics}（协同包抄/包围/集结/阵型）</li>
 *   <li><b>战斗</b> → {@link CombatActions}（近战攻击/射箭/投掷/使用物品）</li>
 * </ul>
 *
 * <h3>架构定位（单向 DAG）</h3>
 * <pre>
 *   底层（互相独立）: PathFinder / NavigatorManager / TacticalScanner / TeamTactics / CombatActions
 *   中层（依赖底层）: AnytimePathFinder / WanderBehavior   （依赖 PathFinder + NavigatorManager）
 *                              ↑
 *   AiAPI（构造器依赖以上全部组件）  ← 本类
 * </pre>
 * 全部组件均可独立使用，AiAPI 仅做聚合转发。Spring 按底层 → 中层 → AiAPI 顺序创建。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * AiAPI ai = JFrameMain.getAiAPI();
 *
 * // 1. 寻路 + 自动导航到目标
 * ai.navigateTo(zombie, player);
 *
 * // 2. 找掩体并前往
 * TacticalPosition cover = ai.findCover(zombie, player, 8);
 * if (cover.isPresent()) {
 *     ai.navigateTo(zombie, cover.toLevelPosition(zombie.getLevel()));
 * }
 *
 * // 3. 远程射箭 + 近战
 * if (distance > 8) {
 *     ai.shootArrow(skeleton, player);
 * } else {
 *     ai.meleeAttack(zombie, player, 4.0f);
 * }
 * }</pre>
 *
 * @see PathFinder
 * @see NavigatorManager
 * @see TacticalScanner
 * @see CombatActions
 */
public class AiAPI {

    /** 寻路器：A* 路径计算 */
    private final PathFinder pathFinder;
    /** 导航管理器：实体移动调度（实现 PluginAware） */
    private final NavigatorManager navigatorManager;
    /** 单实体战术扫描器：找掩体/远离/侧翼/高地 */
    private final TacticalScanner tacticalScanner;
    /** 战斗执行器：攻击/射箭/投掷/使用物品 */
    private final CombatActions combatActions;
    /** 边走边搜追逐导航器：追逐移动目标 */
    private final AnytimePathFinder anytimePathFinder;
    /** 游荡行为：半径内随机巡游 */
    private final WanderBehavior wanderBehavior;
    /** 团队战术：多实体协同包抄/包围/集结/阵型 */
    private final TeamTactics teamTactics;
    /** 路径可视化器：粒子显示寻路路径 */
    private final PathVisualizer pathVisualizer;
    /** 贪心寻路器：局部步进寻路（无状态，直接实例化） */
    private final GreedyPathFinder greedyPathFinder = new GreedyPathFinder();
    /** 贪心追逐器：基于贪心策略的边走边搜追逐（复用 navigatorManager 调度） */
    private final AnytimePathFinder greedyChaser;
    /** 连续导航器：走完一段后自动续算下一段，直到到达静态目标 */
    private final ContinuousNavigator continuousNavigator;

    /**
     * 构造 AI 服务。
     * <p>
     * 通过 Spring 构造器注入。底层组件互相独立，中层组件依赖底层，AiAPI 最后创建。
     *
     * @param pathFinder       寻路器
     * @param navigatorManager 导航管理器
     * @param tacticalScanner  单实体战术扫描器
     * @param combatActions    战斗执行器
     * @param anytimePathFinder 边走边搜追逐导航器
     * @param wanderBehavior   游荡行为
     * @param teamTactics      团队战术
     */
    public AiAPI(PathFinder pathFinder, NavigatorManager navigatorManager,
                 TacticalScanner tacticalScanner, CombatActions combatActions,
                 AnytimePathFinder anytimePathFinder, WanderBehavior wanderBehavior,
                 TeamTactics teamTactics) {
        this.pathFinder = pathFinder;
        this.navigatorManager = navigatorManager;
        this.tacticalScanner = tacticalScanner;
        this.combatActions = combatActions;
        this.anytimePathFinder = anytimePathFinder;
        this.wanderBehavior = wanderBehavior;
        this.teamTactics = teamTactics;
        this.pathVisualizer = new PathVisualizer(navigatorManager);
        this.greedyChaser = new AnytimePathFinder(greedyPathFinder, navigatorManager);
        this.continuousNavigator = new ContinuousNavigator(greedyPathFinder, navigatorManager);
    }

    // ========== 寻路（→ PathFinder） ==========

    /**
     * 计算从实体当前位置到目标点的路径（默认参数）。
     *
     * @param entity 起点实体
     * @param target 目标点
     * @return 寻路结果（永不为 null）
     */
    public PathResult findPath(Entity entity, Vector3 target) {
        return pathFinder.findPath(entity.getLevel(), entity, target);
    }

    /**
     * 计算从实体当前位置到目标点的路径（自定义参数）。
     *
     * @param entity  起点实体
     * @param target  目标点
     * @param options 寻路参数
     * @return 寻路结果（永不为 null）
     */
    public PathResult findPath(Entity entity, Vector3 target, PathfinderOptions options) {
        return pathFinder.findPath(entity.getLevel(), entity, target, options);
    }

    // ========== 贪心寻路（→ GreedyPathFinder） ==========

    /**
     * <b>贪心寻路</b>（默认参数）：使用局部步进贪心算法计算路径。
     * <p>
     * 与 A* 不同，贪心寻路是<b>短视</b>的：每次只走有限步数，返回 PARTIAL 路径。
     * 适合大量实体的低开销寻路，或作为 A* 的补充。
     *
     * @param entity 起点实体
     * @param target 目标点
     * @return 寻路结果（通常为 PARTIAL，除非目标很近）
     * @see GreedyPathFinder
     */
    public PathResult findPathGreedy(Entity entity, Vector3 target) {
        return greedyPathFinder.findPath(entity.getLevel(), entity, target, new GreedyOptions());
    }

    /**
     * <b>贪心寻路</b>（自定义参数）。
     *
     * @param entity  起点实体
     * @param target  目标点
     * @param options 贪心寻路参数
     * @return 寻路结果
     * @see GreedyOptions
     */
    public PathResult findPathGreedy(Entity entity, Vector3 target, GreedyOptions options) {
        return greedyPathFinder.findPath(entity.getLevel(), entity, target, options);
    }

    /**
     * <b>贪心导航</b>（默认参数）：贪心寻路 + 导航一步到位。
     * <p>
     * 内部使用 {@link #findPathGreedy} 计算路径，然后通过 {@link #navigateOrPartial} 提交导航。
     * 由于贪心返回的通常是 PARTIAL 路径，实体只会走有限步数后停下。
     *
     * @param entity 被导航的实体
     * @param target 目标点
     * @return 导航器，或 null（完全无可行走路径）
     */
    public Navigator navigateGreedy(Entity entity, Vector3 target) {
        PathResult result = findPathGreedy(entity, target);
        return navigatorManager.navigateOrPartial(entity, result, Navigator.DEFAULT_SPEED);
    }

    /**
     * <b>贪心导航</b>（自定义参数与速度）。
     *
     * @param entity  被导航的实体
     * @param target  目标点
     * @param options 贪心寻路参数
     * @param speed   行进速度
     * @return 导航器，或 null
     */
    public Navigator navigateGreedy(Entity entity, Vector3 target, GreedyOptions options, double speed) {
        PathResult result = findPathGreedy(entity, target, options);
        return navigatorManager.navigateOrPartial(entity, result, speed);
    }

    /**
     * <b>贪心追逐</b>：以贪心策略持续追逐移动目标。
     * <p>
     * 与 {@link #chase} 不同，使用 {@link GreedyPathFinder} 进行局部步进寻路，
     * 每次重搜只走有限步数，由周期性重搜组合完成长距离追逐。
     *
     * @param entity         追逐者
     * @param targetSupplier 目标位置供应器
     * @see AnytimePathFinder#chaseGreedy
     */
    public void chaseGreedy(Entity entity, Supplier<Vector3> targetSupplier) {
        greedyChaser.chaseGreedy(entity, targetSupplier,
                Navigator.DEFAULT_SPEED, AnytimePathFinder.DEFAULT_REPLAN_INTERVAL);
    }

    /**
     * <b>贪心追逐</b>（完整参数）。
     *
     * @param entity         追逐者
     * @param targetSupplier 目标位置供应器
     * @param speed          行进速度
     * @param replanInterval 重搜间隔（tick）
     */
    public void chaseGreedy(Entity entity, Supplier<Vector3> targetSupplier, double speed, int replanInterval) {
        greedyChaser.chaseGreedy(entity, targetSupplier, speed, replanInterval);
    }

    /**
     * <b>取消当前贪心寻路</b>。
     * <p>
     * 设置取消标志，正在执行的贪心寻路会在下一步检查时返回 CANCELLED（附带已走的部分路径）。
     * 注意：此方法仅取消 {@link #findPathGreedy} / {@link #navigateGreedy} 的同步调用，
     * 不影响已提交的 {@link Navigator} 导航。要停止贪心追逐请用 {@link #stopChaseGreedy}。
     */
    public void cancelGreedy() {
        greedyPathFinder.cancel();
    }

    /**
     * 停止指定实体的贪心追逐。
     *
     * @param entity 实体
     */
    public void stopChaseGreedy(Entity entity) {
        greedyChaser.stopChase(entity);
    }

    /**
     * 查询某实体是否正在贪心追逐。
     *
     * @param entity 实体
     * @return true 表示存在未完成的贪心追逐任务
     */
    public boolean isChasingGreedy(Entity entity) {
        return greedyChaser.isChasing(entity);
    }

    // ========== 贪心连续导航（→ ContinuousNavigator） ==========

    /**
     * <b>贪心连续导航</b>（默认参数）：走完一段后自动续算下一段，直到到达目标。
     * <p>
     * 与 {@link #navigateGreedy}（只走一段就停）不同，本方法通过 {@link Navigator#onComplete}
     * 回调驱动续算——实体走完当前段后立即从新位置重新寻路，串联多段完成长距离导航。
     * 适合目标较远（超过单次贪心 maxSteps 步数）的场景。
     *
     * @param entity 被导航的实体
     * @param target 目标点
     * @see ContinuousNavigator#navigateTo
     */
    public void navigateGreedyContinuous(Entity entity, Vector3 target) {
        continuousNavigator.navigateTo(entity, target);
    }

    /**
     * <b>贪心连续导航</b>（完整参数）。
     *
     * @param entity      被导航的实体
     * @param target      目标点
     * @param options     贪心寻路参数
     * @param speed       行进速度
     * @param maxSegments 最大续算段数（防止无限循环）
     * @see ContinuousNavigator#navigateTo
     */
    public void navigateGreedyContinuous(Entity entity, Vector3 target,
                                         GreedyOptions options, double speed, int maxSegments) {
        continuousNavigator.navigateTo(entity, target, options, speed, maxSegments);
    }

    /**
     * 停止指定实体的连续导航。
     *
     * @param entity 实体
     */
    public void stopContinuous(Entity entity) {
        continuousNavigator.stop(entity);
    }

    /**
     * 查询某实体是否正在连续导航。
     *
     * @param entity 实体
     * @return true 表示存在未完成的连续导航任务
     */
    public boolean isNavigatingContinuous(Entity entity) {
        return continuousNavigator.isNavigating(entity);
    }

    // ========== 导航（→ NavigatorManager） ==========

    /**
     * 提交导航任务（已有路径结果）。
     *
     * @param entity 被导航的实体
     * @param result 寻路结果
     * @return 导航器，或 null（路径不可用）
     */
    public Navigator navigate(Entity entity, PathResult result) {
        return navigatorManager.navigate(entity, result);
    }

    /**
     * 提交导航任务（指定速度）。
     *
     * @param entity 被导航的实体
     * @param result 寻路结果
     * @param speed  行进速度（方块/tick）
     * @return 导航器，或 null
     */
    public Navigator navigate(Entity entity, PathResult result, double speed) {
        return navigatorManager.navigate(entity, result, speed);
    }

    /**
     * 提交导航任务（边走边搜版）：接受完整路径或部分路径。
     * <p>
     * 与 {@link #navigate} 的区别：只要 {@link PathResult#hasPath()} 为真即创建导航器，
     * 因此即使寻路仅得到 {@link PathResult.Status#PARTIAL}（未到达目标的部分路径），
     * 实体也会沿该路径开始移动。
     *
     * @param entity 被导航的实体
     * @param result 寻路结果（成功或部分路径）
     * @param speed  行进速度（方块/tick）
     * @return 导航器，或 null（路径完全不可用）
     * @see NavigatorManager#navigateOrPartial
     */
    public Navigator navigateOrPartial(Entity entity, PathResult result, double speed) {
        return navigatorManager.navigateOrPartial(entity, result, speed);
    }

    /**
     * <b>便捷方法</b>：寻路 + 导航一步到位（默认速度）。
     * <p>
     * 内部使用 {@link #defaultNavOptions()}（开启边走边搜 + 放宽搜索预算），
     * 因此即使目标不可达或预算不足，只要存在朝目标方向的部分路径，实体也会开始移动，
     * 而非原地静止——这显著改善真实地形下的"寻路不动"问题。
     *
     * @param entity 被导航的实体
     * @param target 目标点
     * @return 导航器，或 null（完全无可行走路径）
     */
    public Navigator navigateTo(Entity entity, Vector3 target) {
        PathResult result = findPath(entity, target, defaultNavOptions());
        return navigatorManager.navigateOrPartial(entity, result, Navigator.DEFAULT_SPEED);
    }

    /**
     * 便捷导航默认参数：开启边走边搜（partialOnFailure）并放宽搜索预算。
     * <p>
     * 使 {@link #navigateTo} 在目标不可达或预算不足时仍能让实体朝目标移动一段。
     *
     * @return 默认导航参数
     */
    private static PathfinderOptions defaultNavOptions() {
        return new PathfinderOptions()
                .setMaxSearchNodes(4000)
                .setPartialOnFailure(true);
    }

    /**
     * <b>便捷方法</b>：寻路 + 导航（自定义参数与速度）。
     *
     * @param entity  被导航的实体
     * @param target  目标点
     * @param options 寻路参数
     * @param speed   行进速度
     * @return 导航器，或 null
     */
    public Navigator navigateTo(Entity entity, Vector3 target, PathfinderOptions options, double speed) {
        PathResult result = findPath(entity, target, options);
        return navigate(entity, result, speed);
    }

    /**
     * 停止指定实体的导航。
     *
     * @param entity 实体
     */
    public void stop(Entity entity) {
        navigatorManager.stop(entity);
    }

    /**
     * 停止所有导航。
     */
    public void stopAll() {
        navigatorManager.stopAll();
    }

    /**
     * 查询某实体是否正在导航。
     *
     * @param entity 实体
     * @return true 表示存在未完成的导航
     */
    public boolean isNavigating(Entity entity) {
        return navigatorManager.isNavigating(entity);
    }

    /**
     * 获取某实体的导航器。
     *
     * @param entity 实体
     * @return 导航器，或 null
     */
    public Navigator getNavigator(Entity entity) {
        return navigatorManager.getNavigator(entity);
    }

    /**
     * 当前活跃导航器数量。
     *
     * @return 数量
     */
    public int activeNavigatorCount() {
        return navigatorManager.activeCount();
    }

    /**
     * 获取实体当前导航的<b>完整路径</b>（只读视图）。
     * <p>
     * 返回从起点到终点的全部路径节点。若实体未在导航，返回空列表。
     * 返回的列表为不可变视图，外部修改不影响内部状态。
     *
     * @param entity 实体
     * @return 完整路径节点列表（只读），或空列表
     */
    public List<BlockNode> getCurrentPath(Entity entity) {
        Navigator nav = navigatorManager.getNavigator(entity);
        if (nav == null) {
            return Collections.emptyList();
        }
        return nav.getPath();
    }

    /**
     * 获取实体当前导航的<b>剩余路径</b>（从当前位置到终点）。
     * <p>
     * 返回从当前目标节点到终点的剩余路径节点。若实体未在导航或已到达终点，返回空列表。
     * 返回的列表为副本，可自由修改。
     *
     * @param entity 实体
     * @return 剩余路径节点列表（副本），或空列表
     */
    public List<BlockNode> getRemainingPath(Entity entity) {
        Navigator nav = navigatorManager.getNavigator(entity);
        if (nav == null) {
            return Collections.emptyList();
        }
        return nav.getRemainingPath();
    }

    /**
     * <b>显示寻路路径</b>（粒子可视化，持续 10 秒）。
     * <p>
     * 用火焰粒子沿路径节点显示实体当前导航的完整路径。适用于调试与演示。
     * 必须在 {@link #bindPlugin} 之后调用（持续显示依赖 Plugin 调度器）。
     *
     * @param entity 实体
     * @return true 表示成功启动显示；false 表示实体未导航或 Plugin 未绑定
     */
    public boolean showPath(Entity entity) {
        return pathVisualizer.showPath(entity);
    }

    /**
     * 显示寻路路径（指定持续秒数）。
     *
     * @param entity          实体
     * @param durationSeconds 持续秒数
     * @return true 表示成功启动显示
     */
    public boolean showPath(Entity entity, int durationSeconds) {
        return pathVisualizer.showPath(entity, durationSeconds);
    }

    /**
     * 一次性显示寻路路径（粒子约 1-2 秒后消失，不依赖 Plugin）。
     *
     * @param entity 实体
     * @return 显示的节点数；若未导航返回 0
     */
    public int showPathOnce(Entity entity) {
        return pathVisualizer.showPathOnce(entity);
    }

    /**
     * 停止显示寻路路径（取消定时刷新任务）。
     *
     * @param entity 实体
     */
    public void stopShowPath(Entity entity) {
        pathVisualizer.stopShowPath(entity);
    }

    // ========== 视野感知（→ VisionSensor） ==========

    /**
     * <b>视野判断</b>（默认参数：16 格 / 90° FOV）：判断观察者能否看到目标。
     * <p>
     * 综合三要素：距离 ≤ 16、目标在前方 90° 锥角内、眼部到眼部视线无遮挡。
     *
     * @param observer 观察者
     * @param target   被观察目标
     * @return true 表示目标在视野内
     * @see VisionSensor#canSee(Entity, Entity)
     */
    public boolean canSee(Entity observer, Entity target) {
        return VisionSensor.canSee(observer, target);
    }

    /**
     * <b>视野判断</b>（完整参数）：判断观察者能否看到目标。
     *
     * @param observer           观察者
     * @param target             被观察目标
     * @param maxDistance        最大视野距离（方块，水平）
     * @param fieldOfViewDegrees 全视野角度（度，≥360 表示全向）
     * @return true 表示目标在视野内
     * @see VisionSensor#canSee(Entity, Entity, double, double)
     */
    public boolean canSee(Entity observer, Entity target, double maxDistance, double fieldOfViewDegrees) {
        return VisionSensor.canSee(observer, target, maxDistance, fieldOfViewDegrees);
    }

    /**
     * <b>全向视野判断</b>（360° 感知）：仅检查距离与视线，不考虑朝向。
     *
     * @param observer    观察者
     * @param target      被观察目标
     * @param maxDistance 最大感知距离（方块，水平）
     * @return true 表示目标在感知范围内且视线畅通
     * @see VisionSensor#canSee360(Entity, Entity, double)
     */
    public boolean canSee360(Entity observer, Entity target, double maxDistance) {
        return VisionSensor.canSee360(observer, target, maxDistance);
    }

    /**
     * <b>坐标版全向视野</b>（默认距离）：判断观察者能否"转头看到"指定坐标点
     * （距离 + 视线，不限当前朝向）。
     * <p>
     * 与 {@link #canSee(Entity, Entity)}（受当前朝向 FOV 约束）不同，本方法回答
     * "AI 能否通过转头看到该点"——只要距离足够且视线畅通即返回 true。
     *
     * @param observer  观察者
     * @param targetPos 目标坐标点
     * @return true 表示转头即可看到该点
     * @see VisionSensor#canSee(Entity, Vector3)
     */
    public boolean canSee(Entity observer, Vector3 targetPos) {
        return VisionSensor.canSee(observer, targetPos);
    }

    /**
     * <b>坐标版全向视野</b>（指定最大距离）：判断观察者能否"转头看到"指定坐标点。
     *
     * @param observer    观察者
     * @param targetPos   目标坐标点
     * @param maxDistance 最大感知距离（方块，水平）
     * @return true 表示目标点在感知范围内且视线畅通
     * @see VisionSensor#canSee(Entity, Vector3, double)
     */
    public boolean canSee(Entity observer, Vector3 targetPos, double maxDistance) {
        return VisionSensor.canSee(observer, targetPos, maxDistance);
    }

    /**
     * 计算目标相对观察者朝向的水平夹角（度）。
     *
     * @param observer 观察者
     * @param target   目标
     * @return 夹角度数 [0, 180]；0=正前，90=正侧，180=正后
     * @see VisionSensor#angleTo(Entity, Entity)
     */
    public double angleTo(Entity observer, Entity target) {
        return VisionSensor.angleTo(observer, target);
    }

    // ========== 战术（→ TacticalScanner） ==========

    /**
     * 找掩体：寻找能遮挡威胁视线的位置。
     *
     * @param self   需要掩体的实体
     * @param threat 威胁来源
     * @param radius 搜索半径
     * @return 最佳掩体位置；若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findCover(Entity self, Entity threat, double radius) {
        return tacticalScanner.findCover(self, threat, radius);
    }

    /**
     * 找掩体（坐标版）：寻找能遮挡指定坐标视线的位置。
     *
     * @param self      需要掩体的实体
     * @param threatPos 威胁位置坐标
     * @param radius    搜索半径
     * @return 最佳掩体位置；若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findCover(Entity self, Vector3 threatPos, double radius) {
        return tacticalScanner.findCover(self, threatPos, radius);
    }

    /**
     * 远离：寻找离威胁最远的可达位置。
     *
     * @param self   逃跑实体
     * @param threat 威胁来源
     * @param radius 搜索半径
     * @return 最远位置；若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findFleePosition(Entity self, Entity threat, double radius) {
        return tacticalScanner.findFleePosition(self, threat, radius);
    }

    /**
     * 远离（坐标版）：寻找离指定坐标最远的可达位置。
     *
     * @param self      逃跑实体
     * @param threatPos 威胁位置坐标
     * @param radius    搜索半径
     * @return 最远位置；若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findFleePosition(Entity self, Vector3 threatPos, double radius) {
        return tacticalScanner.findFleePosition(self, threatPos, radius);
    }

    /**
     * <b>单实体</b>侧翼：寻找位于目标侧方的位置（仅考虑自身一个实体）。
     * <p>
     * <b>团队场景</b>（多个实体协同从不同方向钳形接近同一目标）请改用
     * {@link #flankTarget(List, Entity, double)}，它能为每个成员分配不同的侧翼角度，
     * 避免多个实体挤在同一方向。
     *
     * @param self   包抄发起实体
     * @param target 包抄目标
     * @param radius 搜索半径
     * @return 最佳侧翼位置；若无返回 {@link TacticalPosition#empty()}
     * @see #flankTarget(List, Entity, double)
     */
    public TacticalPosition findFlankPosition(Entity self, Entity target, double radius) {
        return tacticalScanner.findFlankPosition(self, target, radius);
    }

    /**
     * 单实体侧翼（坐标版）：寻找位于指定坐标侧方的位置。
     *
     * @param self      包抄发起实体
     * @param targetPos 包抄目标位置坐标
     * @param radius    搜索半径
     * @return 最佳侧翼位置；若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findFlankPosition(Entity self, Vector3 targetPos, double radius) {
        return tacticalScanner.findFlankPosition(self, targetPos, radius);
    }

    /**
     * 寻找高地：寻找比当前位置更高的可站立位置。
     *
     * @param self         实体
     * @param radius       搜索半径
     * @param minAdvantage 最小高度优势阈值
     * @return 最佳高地位置；若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findHighGround(Entity self, double radius, int minAdvantage) {
        return tacticalScanner.findHighGround(self, radius, minAdvantage);
    }

    /**
     * <b>占据视野点</b>：寻找一个能看到目标的可站立位置（找掩体的反向操作）。
     * <p>
     * 适用于弓箭手/哨兵需要保持视线锁定目标的场景。评分综合考虑：
     * 距目标接近理想距离（10 格）+ 距自身近（便于快速到达）。
     *
     * @param self   需要视野的实体
     * @param target 观察目标
     * @param radius 搜索半径
     * @return 最佳视野位置；若无返回 {@link TacticalPosition#empty()}
     * @see TacticalScanner#findSightPosition(Entity, Entity, double)
     */
    public TacticalPosition findSightPosition(Entity self, Entity target, double radius) {
        return tacticalScanner.findSightPosition(self, target, radius);
    }

    /**
     * 占据视野点（坐标版，默认理想距离 10 格）。
     *
     * @param self      需要视野的实体
     * @param targetPos 观察目标位置坐标
     * @param radius    搜索半径
     * @return 最佳视野位置；若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findSightPosition(Entity self, Vector3 targetPos, double radius) {
        return tacticalScanner.findSightPosition(self, targetPos, radius);
    }

    /**
     * 占据视野点（指定理想观察距离）。
     *
     * @param self           需要视野的实体
     * @param target         观察目标
     * @param radius         搜索半径
     * @param idealDistance  理想观察距离（方块）
     * @return 最佳视野位置；若无返回 {@link TacticalPosition#empty()}
     * @see TacticalScanner#findSightPosition(Entity, Entity, double, double)
     */
    public TacticalPosition findSightPosition(Entity self, Entity target, double radius, double idealDistance) {
        return tacticalScanner.findSightPosition(self, target, radius, idealDistance);
    }

    /**
     * 占据视野点（坐标版，指定理想观察距离）。
     *
     * @param self           需要视野的实体
     * @param targetPos      观察目标位置坐标
     * @param radius         搜索半径
     * @param idealDistance  理想观察距离（方块）
     * @return 最佳视野位置；若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findSightPosition(Entity self, Vector3 targetPos, double radius, double idealDistance) {
        return tacticalScanner.findSightPosition(self, targetPos, radius, idealDistance);
    }

    /**
     * <b>便捷方法</b>：寻找视野点并导航前往（默认理想距离 10 格）。
     * <p>
     * 内部调用 {@link #findSightPosition} 获取最佳视野位置，再通过 {@link #navigateTo} 驱动实体前往。
     *
     * @param self   需要视野的实体
     * @param target 观察目标
     * @param radius 搜索半径
     * @return 导航器，或 null（未找到合适视野点或路径不可用）
     */
    public Navigator navigateToSightPosition(Entity self, Entity target, double radius) {
        TacticalPosition pos = findSightPosition(self, target, radius);
        if (!pos.isPresent()) {
            return null;
        }
        return navigateTo(self, pos.toLevelPosition(self.getLevel()));
    }

    /**
     * <b>便捷方法</b>：寻找视野点（坐标版）并导航前往。
     * <p>
     * 内部调用 {@link #findSightPosition(Entity, Vector3, double)} 获取能看到目标坐标的位置，
     * 再通过 {@link #navigateTo} 驱动实体前往。适用于目标不是实体（仅知坐标 / 最后已知位置 /
     * 投射落点）时，让 AI 移动到"能看到该坐标"的地方。
     *
     * @param self      需要视野的实体
     * @param targetPos 观察目标位置坐标
     * @param radius    搜索半径
     * @return 导航器，或 null（未找到合适视野点或路径不可用）
     */
    public Navigator navigateToSightPosition(Entity self, Vector3 targetPos, double radius) {
        TacticalPosition pos = findSightPosition(self, targetPos, radius);
        if (!pos.isPresent()) {
            return null;
        }
        return navigateTo(self, pos.toLevelPosition(self.getLevel()));
    }

    /**
     * <b>模糊位置选取</b>（默认不确定半径 5 格）：当目标位置不精确时，
     * 在不确定区域内选取距自身最近的可站立搜索点。
     * <p>
     * 适用于玩家未完全暴露位置的场景（仅知最后已知位置 / 听到声响），
     * AI 只需到达目标大致所在区域即可开始搜查。
     *
     * @param self   搜索实体
     * @param center 目标大致位置（不确定区域圆心）
     * @return 距自身最近的可站立搜索点；若无返回 {@link TacticalPosition#empty()}
     * @see TacticalScanner#findApproximatePosition(Entity, Vector3, double)
     */
    public TacticalPosition findApproximatePosition(Entity self, Vector3 center) {
        return findApproximatePosition(self, center, TacticalScanner.DEFAULT_UNCERTAINTY_RADIUS);
    }

    /**
     * <b>模糊位置选取</b>（指定不确定半径）。
     *
     * @param self              搜索实体
     * @param center            目标大致位置（不确定区域圆心）
     * @param uncertaintyRadius 不确定半径（方块）
     * @return 距自身最近的可站立搜索点；若无返回 {@link TacticalPosition#empty()}
     * @see TacticalScanner#findApproximatePosition(Entity, Vector3, double)
     */
    public TacticalPosition findApproximatePosition(Entity self, Vector3 center, double uncertaintyRadius) {
        return tacticalScanner.findApproximatePosition(self, center, uncertaintyRadius);
    }

    /**
     * 判断实体是否已进入模糊目标区域（到达大概位置）。
     * <p>
     * 当实体到 {@code center} 的水平距离 ≤ {@code uncertaintyRadius} 时返回 true。
     *
     * @param self              实体
     * @param center            目标大致位置（不确定区域圆心）
     * @param uncertaintyRadius 不确定半径（方块）
     * @return true 表示已到达目标大致区域
     * @see TacticalScanner#hasReachedApproximate(Entity, Vector3, double)
     */
    public boolean hasReachedApproximate(Entity self, Vector3 center, double uncertaintyRadius) {
        return tacticalScanner.hasReachedApproximate(self, center, uncertaintyRadius);
    }

    /**
     * <b>便捷方法</b>：模糊位置选取 + 导航前往。
     * <p>
     * 内部调用 {@link #findApproximatePosition} 在不确定区域内选取搜索点，再通过 {@link #navigateTo} 驱动前往。
     *
     * @param self              搜索实体
     * @param center            目标大致位置（不确定区域圆心）
     * @param uncertaintyRadius 不确定半径（方块）
     * @return 导航器，或 null（未找到合适搜索点或路径不可用）
     */
    public Navigator navigateToApproximate(Entity self, Vector3 center, double uncertaintyRadius) {
        TacticalPosition pos = findApproximatePosition(self, center, uncertaintyRadius);
        if (!pos.isPresent()) {
            return null;
        }
        return navigateTo(self, pos.toLevelPosition(self.getLevel()));
    }

    // ========== 追逐（→ AnytimePathFinder：边走边搜） ==========

    /**
     * <b>追逐移动目标</b>（默认参数）：周期性重搜，即使目标移动也能持续追踪。
     * <p>
     * 内部开启边走边搜（partialOnFailure），即使因地形阻隔无法到达，实体也会朝目标方向
     * 移动到"离目标最近的已探索节点"，而非原地不动。
     *
     * @param entity         追逐者
     * @param targetSupplier 目标位置供应器（每次重搜调用，返回 null 则停止）
     * @see AnytimePathFinder#chase(Entity, Supplier)
     */
    public void chase(Entity entity, Supplier<Vector3> targetSupplier) {
        anytimePathFinder.chase(entity, targetSupplier);
    }

    /**
     * 追逐移动目标（完整参数）。
     *
     * @param entity              追逐者
     * @param targetSupplier      目标位置供应器
     * @param options             寻路参数（将强制开启部分路径回退）
     * @param speed               行进速度
     * @param replanIntervalTicks 重搜间隔（tick）
     * @see AnytimePathFinder#chase(Entity, Supplier, PathfinderOptions, double, int)
     */
    public void chase(Entity entity, Supplier<Vector3> targetSupplier,
                      PathfinderOptions options, double speed, int replanIntervalTicks) {
        anytimePathFinder.chase(entity, targetSupplier, options, speed, replanIntervalTicks);
    }

    /**
     * 查询某实体是否正在追逐。
     */
    public boolean isChasing(Entity entity) {
        return anytimePathFinder.isChasing(entity);
    }

    /**
     * 停止指定实体的追逐。
     */
    public void stopChase(Entity entity) {
        anytimePathFinder.stopChase(entity);
    }

    // ========== 游荡（→ WanderBehavior：随机巡游） ==========

    /**
     * <b>游荡</b>（默认速度与停留时间）：让实体在以自身为中心的半径内随机巡游。
     *
     * @param entity 实体
     * @param radius 游荡半径（方块）
     * @see WanderBehavior#wander(Entity, double)
     */
    public void wander(Entity entity, double radius) {
        wanderBehavior.wander(entity, radius);
    }

    /**
     * 游荡（完整参数）。
     *
     * @param entity    实体
     * @param radius    游荡半径
     * @param speed     行进速度
     * @param idleTicks 每次到达后的停留 tick 数
     * @see WanderBehavior#wander(Entity, double, double, int)
     */
    public void wander(Entity entity, double radius, double speed, int idleTicks) {
        wanderBehavior.wander(entity, radius, speed, idleTicks);
    }

    /**
     * 查询某实体是否正在游荡。
     */
    public boolean isWandering(Entity entity) {
        return wanderBehavior.isWandering(entity);
    }

    /**
     * 停止指定实体的游荡。
     */
    public void stopWander(Entity entity) {
        wanderBehavior.stopWander(entity);
    }

    // ========== 团队战术（→ TeamTactics：多实体协同） ==========

    /**
     * <b>协同包抄</b>：为一组实体分配不同侧翼方向，从多个角度钳形接近同一目标。
     * <p>
     * 与 {@link #findFlankPosition}（单实体）不同，本方法为每个成员计算独立的侧翼角度，
     * 使群体呈现两翼展开的钳形攻势。
     *
     * @param members 包抄成员列表
     * @param target   包抄目标
     * @param radius   包抄点距目标的距离
     * @return 各成员的包抄位置（与 members 一一对应）
     * @see TeamTactics#flankTarget(List, Entity, double)
     */
    public List<TacticalPosition> flankTarget(List<Entity> members, Entity target, double radius) {
        return teamTactics.flankTarget(members, target, radius);
    }

    /**
     * <b>协同包抄</b>（指定弧线跨度）：在 {@link #flankTarget(List, Entity, double)} 基础上
     * 允许自定义成员在目标远侧的展开角度。
     * <p>
     * {@code arcSpanDegrees} 越小成员越集中在目标正后方，越大越接近环形包围。
     * 默认值见 {@link io.github.JiangHu.jframe.ai.tactical.TeamTactics#DEFAULT_FLANK_ARC_DEGREES}（180°）。
     *
     * @param members        包抄成员列表
     * @param target         包抄目标
     * @param radius         包抄点距目标的距离
     * @param arcSpanDegrees 包抄弧线跨度（度，钳制到 [10, 360]）
     * @return 各成员的包抄位置（与 members 一一对应）
     * @see TeamTactics#flankTarget(List, Entity, double, double)
     */
    public List<TacticalPosition> flankTarget(List<Entity> members, Entity target,
                                              double radius, double arcSpanDegrees) {
        return teamTactics.flankTarget(members, target, radius, arcSpanDegrees);
    }

    /**
     * <b>包围</b>：将实体均匀分布在目标周围 360°，形成环形包围。
     *
     * @param members 包围成员列表
     * @param target   被包围目标
     * @param radius   包围半径
     * @return 各成员的包围位置（与 members 一一对应）
     * @see TeamTactics#surroundTarget(List, Entity, double)
     */
    public List<TacticalPosition> surroundTarget(List<Entity> members, Entity target, double radius) {
        return teamTactics.surroundTarget(members, target, radius);
    }

    /**
     * <b>集结</b>：让实体汇聚到集结点附近，按指定阵型排列。
     *
     * @param members    成员列表
     * @param rallyPoint 集结中心点
     * @param formation  阵型类型
     * @param spacing    成员间距
     * @return 各成员的集结位置（与 members 一一对应）
     * @see TeamTactics#rally(List, Vector3, FormationType, double)
     */
    public List<TacticalPosition> rally(List<Entity> members, Vector3 rallyPoint,
                                        FormationType formation, double spacing) {
        return teamTactics.rally(members, rallyPoint, formation, spacing);
    }

    /**
     * <b>阵型偏移</b>（纯几何）：给定阵型、人数、间距，返回每个成员相对中心的水平偏移。
     *
     * @param formation   阵型类型
     * @param memberCount 成员数量
     * @param spacing     成员间距
     * @return 偏移列表
     * @see TeamTactics#formationOffsets(FormationType, int, double)
     */
    public List<Vector3> formationOffsets(FormationType formation, int memberCount, double spacing) {
        return teamTactics.formationOffsets(formation, memberCount, spacing);
    }

    // ========== 战斗（→ CombatActions） ==========

    /**
     * 近战攻击。
     *
     * @param attacker 攻击者
     * @param target   目标
     * @param damage   伤害值
     * @return true 表示伤害成功施加
     */
    public boolean meleeAttack(Entity attacker, Entity target, float damage) {
        return combatActions.meleeAttack(attacker, target, damage);
    }

    /**
     * 射箭（默认速度与散布）。
     *
     * @param shooter 射手
     * @param target  目标
     * @return 生成的箭实体；失败返回 null
     */
    public Entity shootArrow(Entity shooter, Entity target) {
        return combatActions.shootArrow(shooter, target);
    }

    /**
     * 射箭（指定速度与散布）。
     *
     * @param shooter    射手
     * @param target     目标
     * @param speed      初速度
     * @param inaccuracy 散布
     * @return 生成的箭实体；失败返回 null
     */
    public Entity shootArrow(Entity shooter, Entity target, double speed, double inaccuracy) {
        return combatActions.shootArrow(shooter, target, speed, inaccuracy);
    }

    /**
     * 投掷抛射物（通用）。
     *
     * @param shooter        投掷者
     * @param projectileType 抛射物注册名
     * @param target         目标
     * @param speed          初速度
     * @param inaccuracy     散布
     * @return 生成的抛射物实体；失败返回 null
     */
    public Entity throwProjectile(Entity shooter, String projectileType, Entity target,
                                  double speed, double inaccuracy) {
        return combatActions.throwProjectile(shooter, projectileType, target, speed, inaccuracy);
    }

    /**
     * 对目标实体使用物品。
     *
     * @param user   使用者
     * @param target 目标实体
     * @param item   物品
     * @return true 表示使用成功
     */
    public boolean useItemOn(Entity user, Entity target, Item item) {
        return combatActions.useItemOn(user, target, item);
    }

    /**
     * 对自身使用物品。
     *
     * @param user 使用者
     * @param item 物品
     * @return true 表示使用成功
     */
    public boolean useItemOnSelf(Entity user, Item item) {
        return combatActions.useItemOnSelf(user, item);
    }

    // ========== 插件绑定（转发给 NavigatorManager） ==========

    /**
     * 绑定关联插件实例，启动导航调度。
     * <p>
     * 转发给 {@link NavigatorManager#bindPlugin(Plugin)}。
     * 当通过 {@code JFrameMain} 使用时，框架会自动扫描 {@code PluginAware} Bean
     * （即 {@link NavigatorManager}）并调用其 {@code bindPlugin}，无需手动调用。
     *
     * @param plugin 插件实例
     */
    public void bindPlugin(Plugin plugin) {
        navigatorManager.bindPlugin(plugin);
    }

    /**
     * 获取关联的插件实例。
     *
     * @return 插件实例，或 null（尚未绑定）
     */
    public Plugin getPlugin() {
        return navigatorManager.getPlugin();
    }

    // ========== 组件 getter（高级直接访问） ==========

    /**
     * 获取底层寻路器（用于直接调用高级寻路 API）。
     *
     * @return 寻路器
     */
    public PathFinder getPathFinder() {
        return pathFinder;
    }

    /**
     * 获取底层导航管理器。
     *
     * @return 导航管理器
     */
    public NavigatorManager getNavigatorManager() {
        return navigatorManager;
    }

    /**
     * 获取底层战术扫描器。
     *
     * @return 战术扫描器
     */
    public TacticalScanner getTacticalScanner() {
        return tacticalScanner;
    }

    /**
     * 获取底层战斗执行器。
     *
     * @return 战斗执行器
     */
    public CombatActions getCombatActions() {
        return combatActions;
    }

    /**
     * 获取底层边走边搜追逐导航器。
     *
     * @return 追逐导航器
     */
    public AnytimePathFinder getAnytimePathFinder() {
        return anytimePathFinder;
    }

    /**
     * 获取底层游荡行为。
     *
     * @return 游荡行为
     */
    public WanderBehavior getWanderBehavior() {
        return wanderBehavior;
    }

    /**
     * 获取底层团队战术。
     *
     * @return 团队战术
     */
    public TeamTactics getTeamTactics() {
        return teamTactics;
    }

    /**
     * 获取底层走完续算导航器（静态目标长距离导航）。
     *
     * @return 续算导航器
     */
    public ContinuousNavigator getContinuousNavigator() {
        return continuousNavigator;
    }
}
