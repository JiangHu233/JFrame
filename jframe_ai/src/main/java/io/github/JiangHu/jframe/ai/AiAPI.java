package io.github.JiangHu.jframe.ai;

import cn.nukkit.entity.Entity;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.ai.core.behavior.BehaviorHandle;
import io.github.JiangHu.jframe.ai.core.behavior.LoopBehavior;
import io.github.JiangHu.jframe.ai.core.combat.CombatActions;
import io.github.JiangHu.jframe.ai.core.executor.AttackExecutor;
import io.github.JiangHu.jframe.ai.core.executor.NavigationExecutor;
import io.github.JiangHu.jframe.ai.core.navigation.Navigator;
import io.github.JiangHu.jframe.ai.core.navigation.NavigatorManager;
import io.github.JiangHu.jframe.ai.core.tactical.FormationType;
import io.github.JiangHu.jframe.ai.core.tactical.TacticalPosition;
import io.github.JiangHu.jframe.ai.core.tactical.TacticalScanner;
import io.github.JiangHu.jframe.ai.core.tactical.TeamTactics;
import io.github.JiangHu.jframe.ai.core.targeting.Target;
import io.github.JiangHu.jframe.ai.core.util.PathVisualizer;
import io.github.JiangHu.jframe.ai.core.vision.SeeQuery;
import io.github.JiangHu.jframe.ai.pathfinding.PathfindingStrategy;
import io.github.JiangHu.jframe.ai.pathfinding.astar.AStarNode;
import io.github.JiangHu.jframe.ai.pathfinding.astar.AStarPathFinder;
import io.github.JiangHu.jframe.ai.pathfinding.greedy.GreedyPathFinder;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AI 服务(<b>面向用户的统一入口</b>):七个动词工厂 + 查询/停止 + 战术。
 * <p>
 * 本类是轻量门面,自身不持有行为逻辑。行为能力经工厂按需创建(无状态库件),
 * 容器只装配算法变体与调度器:
 * <ul>
 *   <li><b>{@code path}</b> → {@link NavigationExecutor}(纯寻路计算,{@code compute()} 得 {@link io.github.JiangHu.jframe.ai.core.executor.PlannedPath})</li>
 *   <li><b>{@code walk}</b> → {@link NavigationExecutor}(导航执行,{@code start()} 驱动实体)</li>
 *   <li><b>{@code chase}</b> → {@link LoopBehavior} 预配置(追逐活目标,目标丢失自动停)</li>
 *   <li><b>{@code wander}</b> → {@link LoopBehavior} 预配置(半径内随机巡游,走完一段再选下一段)</li>
 *   <li><b>{@code loop}</b> → {@link LoopBehavior} 全默认(通用"计算→执行→判断"循环)</li>
 *   <li><b>{@code attack}</b> → {@link AttackExecutor}(链式配置战斗动作,{@code fire()} 主线程执行)</li>
 *   <li><b>{@code see}</b> → {@link SeeQuery}(链式配置视野参数后查询)</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * AiAPI ai = JFrameMain.getAiAPI();
 *
 * // 1. 单次导航:走一步算一步
 * ai.walk(zombie).to(player).speed(0.3).start();
 *
 * // 2. 追逐(目标丢失自动停,完成回调里可启动新行为)
 * ai.chase(zombie, player).onComplete(outcome -> {
 *     if (outcome == BehaviorOutcome.TARGET_LOST) ai.wander(zombie, 10).start();
 * }).start();
 *
 * // 3. 游荡
 * ai.wander(zombie, 12).start();
 *
 * // 4. 战斗(主线程)
 * ai.attack(skeleton).arrow(player, 1.5, 0.3).fire();
 *
 * // 5. 视野感知(任意线程)
 * if (ai.see(zombie).range(24).fov(120).canSee(player)) { ... }
 * }</pre>
 *
 * @see NavigationExecutor
 * @see LoopBehavior
 * @see AttackExecutor
 * @see SeeQuery
 */
public class AiAPI {

    /** 游荡默认轮间隔(tick):走完一段后至多停这么多 tick 再选下一段 */
    static final int DEFAULT_WANDER_INTERVAL = 40;
    /** 追逐默认轮间隔(tick):重算周期,越小追踪越紧、开销越大 */
    static final int DEFAULT_CHASE_INTERVAL = 10;

    /** 默认寻路策略(A*) */
    private final AStarPathFinder aStarPathFinder;
    /** 贪心寻路策略(低开销局部步进) */
    private final GreedyPathFinder greedyPathFinder;
    /** 导航管理器:实体移动调度(实现 PluginAware) */
    private final NavigatorManager navigatorManager;
    /** 单实体战术扫描器 */
    private final TacticalScanner tacticalScanner;
    /** 战斗动作实现 */
    private final CombatActions combatActions;
    /** 团队战术 */
    private final TeamTactics teamTactics;
    /** 路径可视化器 */
    private final PathVisualizer pathVisualizer;

    /**
     * 构造 AI 服务(Spring 构造器注入)。
     *
     * @param aStarPathFinder  A* 寻路策略(默认策略)
     * @param greedyPathFinder 贪心寻路策略
     * @param navigatorManager 导航管理器
     * @param tacticalScanner  单实体战术扫描器
     * @param combatActions    战斗动作实现
     * @param teamTactics      团队战术
     */
    public AiAPI(AStarPathFinder aStarPathFinder, GreedyPathFinder greedyPathFinder,
                 NavigatorManager navigatorManager, TacticalScanner tacticalScanner,
                 CombatActions combatActions, TeamTactics teamTactics) {
        this.aStarPathFinder = aStarPathFinder;
        this.greedyPathFinder = greedyPathFinder;
        this.navigatorManager = navigatorManager;
        this.tacticalScanner = tacticalScanner;
        this.combatActions = combatActions;
        this.teamTactics = teamTactics;
        this.pathVisualizer = new PathVisualizer(navigatorManager);
    }

    // ========== 七工厂 ==========

    /**
     * <b>纯寻路工厂</b>:链式配置后 {@code compute()} 得 {@link io.github.JiangHu.jframe.ai.core.executor.PlannedPath}
     * (任意线程可调,不驱动实体)。
     * <pre>{@code
     * PlannedPath plan = ai.path(zombie).to(pos).compute();
     * if (plan.hasPath()) { ... plan.getResult().getNodes() ... }
     * }</pre>
     *
     * @param self 起点实体
     * @return 导航执行器(配置态)
     */
    public NavigationExecutor path(Entity self) {
        return new NavigationExecutor(self, aStarPathFinder, navigatorManager);
    }

    /**
     * <b>导航工厂</b>:与 {@link #path} 同构,语义侧重执行——
     * {@code start()} 一步到位,或 {@code compute()} 拆两段(异步计算后回主线程执行)。
     * <pre>{@code
     * ai.walk(zombie).to(player).speed(0.3).start();
     * // 连续模式:走完自动续算,直至到达
     * ai.walk(npc).to(farPos).continuous().start();
     * }</pre>
     *
     * @param self 被导航的实体
     * @return 导航执行器(配置态)
     */
    public NavigationExecutor walk(Entity self) {
        return new NavigationExecutor(self, aStarPathFinder, navigatorManager);
    }

    /**
     * <b>追逐工厂</b>(LoopBehavior 预配置):持续追踪活目标,每轮从目标最新位置重算。
     * <ul>
     *   <li>目标失效(死亡/移除)自动以 {@code TARGET_LOST} 结束</li>
     *   <li>默认轮间隔 {@value #DEFAULT_CHASE_INTERVAL} tick,可 {@code interval(...)} 覆盖</li>
     *   <li>算法/参数/速度/停止条件/完成回调/计算载体均可继续链式配置</li>
     * </ul>
     * <pre>{@code
     * ai.chase(zombie, player).speed(0.35).start();
     * }</pre>
     *
     * @param self   追逐者
     * @param target 追逐目标(活引用)
     * @return 循环行为(配置态,需 {@code start()} 启动)
     */
    public LoopBehavior chase(Entity self, Entity target) {
        return loop(self).target(target).interval(DEFAULT_CHASE_INTERVAL);
    }

    /**
     * <b>游荡工厂</b>(LoopBehavior 预配置):半径内随机巡游,走完一段再选下一段。
     * <ul>
     *   <li>每轮在以实体当前位置为圆心、{@code radius} 为半径的圆内随机选点</li>
     *   <li>当前段未走完时跳过本轮(不打断),走完后至多停 {@code interval} tick 选下一段</li>
     *   <li>默认轮间隔 {@value #DEFAULT_WANDER_INTERVAL} tick(即最大停顿时长)</li>
     * </ul>
     * <pre>{@code
     * ai.wander(zombie, 12).speed(0.25).start();
     * }</pre>
     *
     * @param self   游荡实体
     * @param radius 游荡半径(方块)
     * @return 循环行为(配置态,需 {@code start()} 启动)
     */
    public LoopBehavior wander(Entity self, double radius) {
        return loop(self)
                .to(new WanderTarget(self, radius))
                .interval(DEFAULT_WANDER_INTERVAL)
                .execute((ctx, plan) -> {
                    // 当前段未走完:跳过(让实体走完),本轮算作空转
                    if (navigatorManager.isNavigating(self)) {
                        return;
                    }
                    plan.start();
                });
    }

    /**
     * <b>通用循环工厂</b>:"计算 → 执行 → 判断"周期循环,全部参数自配。
     * <p>
     * chase/wander 即本工厂的预配置;自定义行为(如巡逻点列表、战术组合)直接用它。
     * <pre>{@code
     * ai.loop(guard).to(new CoverTarget(guard).threat(enemy).radius(8))
     *   .interval(20)
     *   .until(ctx -> ctx.rounds() >= 5)
     *   .onComplete(outcome -> { ... })
     *   .start();
     * }</pre>
     *
     * @param self 行为主体
     * @return 循环行为(配置态,需 {@code start()} 启动)
     */
    public LoopBehavior loop(Entity self) {
        return new LoopBehavior(self, aStarPathFinder, navigatorManager);
    }

    /**
     * <b>战斗工厂</b>:链式配置一次战斗动作,{@code fire()} 主线程执行。
     * <pre>{@code
     * ai.attack(zombie).melee(player, 4.0f).fire();
     * ai.attack(skeleton).arrow(player, 1.5, 0.3).fire();
     * }</pre>
     *
     * @param self 动作执行者
     * @return 战斗执行器(配置态)
     */
    public AttackExecutor attack(Entity self) {
        return new AttackExecutor(self, combatActions);
    }

    /**
     * <b>视野工厂</b>:链式配置观察参数后查询(任意线程)。
     * <pre>{@code
     * if (ai.see(zombie).range(24).fov(120).canSee(player)) { ... }
     * }</pre>
     *
     * @param self 观察者
     * @return 视野查询器(配置态,可复用)
     */
    public SeeQuery see(Entity self) {
        return new SeeQuery(self);
    }

    // ========== 停止与查询 ==========

    /**
     * 停止指定实体的一切 AI 驱动:循环行为(若有)与当前导航。
     *
     * @param entity 实体
     */
    public void stop(Entity entity) {
        BehaviorHandle behavior = LoopBehavior.activeBehavior(entity);
        if (behavior != null) {
            behavior.stop();
        }
        navigatorManager.stop(entity);
    }

    /**
     * 停止所有导航与循环行为。
     */
    public void stopAll() {
        // 先停循环行为(STOPPED 终态,收回移动权),再停导航兜底
        LoopBehavior.stopAll();
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
     * 查询某实体是否有运行中的循环行为(chase/wander/loop)。
     *
     * @param entity 实体
     * @return true 表示存在
     */
    public boolean isLooping(Entity entity) {
        return LoopBehavior.isRunning(entity);
    }

    /**
     * 停止指定实体的循环行为(不影响独立导航)。
     *
     * @param entity 实体
     */
    public void stopLoop(Entity entity) {
        BehaviorHandle behavior = LoopBehavior.activeBehavior(entity);
        if (behavior != null) {
            behavior.stop();
        }
    }

    /**
     * 获取某实体的导航器。
     *
     * @param entity 实体
     * @return 导航器,或 null
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
     * 获取实体当前导航的<b>完整路径</b>(只读视图)。
     *
     * @param entity 实体
     * @return 完整路径节点列表(只读),或空列表
     */
    public List<AStarNode> getCurrentPath(Entity entity) {
        Navigator nav = navigatorManager.getNavigator(entity);
        if (nav == null) {
            return Collections.emptyList();
        }
        return nav.getPath();
    }

    /**
     * 获取实体当前导航的<b>剩余路径</b>(从当前位置到终点,副本)。
     *
     * @param entity 实体
     * @return 剩余路径节点列表(副本),或空列表
     */
    public List<AStarNode> getRemainingPath(Entity entity) {
        Navigator nav = navigatorManager.getNavigator(entity);
        if (nav == null) {
            return Collections.emptyList();
        }
        return nav.getRemainingPath();
    }

    // ========== 路径可视化(→ PathVisualizer) ==========

    /**
     * 显示寻路路径(粒子可视化,持续 10 秒)。
     * 必须在 {@link #bindPlugin} 之后调用。
     *
     * @param entity 实体
     * @return true 表示成功启动显示
     */
    public boolean showPath(Entity entity) {
        return pathVisualizer.showPath(entity);
    }

    /**
     * 显示寻路路径(指定持续秒数)。
     *
     * @param entity          实体
     * @param durationSeconds 持续秒数
     * @return true 表示成功启动显示
     */
    public boolean showPath(Entity entity, int durationSeconds) {
        return pathVisualizer.showPath(entity, durationSeconds);
    }

    /**
     * 一次性显示寻路路径(粒子约 1-2 秒后消失,不依赖 Plugin)。
     *
     * @param entity 实体
     * @return 显示的节点数;若未导航返回 0
     */
    public int showPathOnce(Entity entity) {
        return pathVisualizer.showPathOnce(entity);
    }

    /**
     * 停止显示寻路路径(取消定时刷新任务)。
     *
     * @param entity 实体
     */
    public void stopShowPath(Entity entity) {
        pathVisualizer.stopShowPath(entity);
    }

    // ========== 战术(→ TacticalScanner) ==========

    /**
     * 找掩体:寻找能遮挡威胁视线的位置。
     *
     * @param self   需要掩体的实体
     * @param threat 威胁来源
     * @param radius 搜索半径
     * @return 最佳掩体位置;若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findCover(Entity self, Entity threat, double radius) {
        return tacticalScanner.findCover(self, threat, radius);
    }

    /**
     * 找掩体(坐标版):寻找能遮挡指定坐标视线的位置。
     *
     * @param self      需要掩体的实体
     * @param threatPos 威胁位置坐标
     * @param radius    搜索半径
     * @return 最佳掩体位置;若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findCover(Entity self, Vector3 threatPos, double radius) {
        return tacticalScanner.findCover(self, threatPos, radius);
    }

    /**
     * 远离:寻找离威胁最远的可达位置。
     *
     * @param self   逃跑实体
     * @param threat 威胁来源
     * @param radius 搜索半径
     * @return 最远位置;若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findFleePosition(Entity self, Entity threat, double radius) {
        return tacticalScanner.findFleePosition(self, threat, radius);
    }

    /**
     * 远离(坐标版):寻找离指定坐标最远的可达位置。
     *
     * @param self      逃跑实体
     * @param threatPos 威胁位置坐标
     * @param radius    搜索半径
     * @return 最远位置;若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findFleePosition(Entity self, Vector3 threatPos, double radius) {
        return tacticalScanner.findFleePosition(self, threatPos, radius);
    }

    /**
     * <b>单实体</b>侧翼:寻找位于目标侧方的位置。
     * <p>
     * <b>团队场景</b>(多实体协同钳形接近)请用 {@link #flankTarget(List, Entity, double)}。
     *
     * @param self   包抄发起实体
     * @param target 包抄目标
     * @param radius 搜索半径
     * @return 最佳侧翼位置;若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findFlankPosition(Entity self, Entity target, double radius) {
        return tacticalScanner.findFlankPosition(self, target, radius);
    }

    /**
     * 单实体侧翼(坐标版):寻找位于指定坐标侧方的位置。
     *
     * @param self      包抄发起实体
     * @param targetPos 包抄目标位置坐标
     * @param radius    搜索半径
     * @return 最佳侧翼位置;若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findFlankPosition(Entity self, Vector3 targetPos, double radius) {
        return tacticalScanner.findFlankPosition(self, targetPos, radius);
    }

    /**
     * 寻找高地:寻找比当前位置更高的可站立位置。
     *
     * @param self         实体
     * @param radius       搜索半径
     * @param minAdvantage 最小高度优势阈值
     * @return 最佳高地位置;若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findHighGround(Entity self, double radius, int minAdvantage) {
        return tacticalScanner.findHighGround(self, radius, minAdvantage);
    }

    /**
     * <b>占据视野点</b>:寻找一个能看到目标的可站立位置(找掩体的反向操作)。
     *
     * @param self   需要视野的实体
     * @param target 观察目标
     * @param radius 搜索半径
     * @return 最佳视野位置;若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findSightPosition(Entity self, Entity target, double radius) {
        return tacticalScanner.findSightPosition(self, target, radius);
    }

    /**
     * 占据视野点(坐标版,默认理想距离 10 格)。
     *
     * @param self      需要视野的实体
     * @param targetPos 观察目标位置坐标
     * @param radius    搜索半径
     * @return 最佳视野位置;若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findSightPosition(Entity self, Vector3 targetPos, double radius) {
        return tacticalScanner.findSightPosition(self, targetPos, radius);
    }

    /**
     * 占据视野点(指定理想观察距离)。
     *
     * @param self          需要视野的实体
     * @param target        观察目标
     * @param radius        搜索半径
     * @param idealDistance 理想观察距离(方块)
     * @return 最佳视野位置;若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findSightPosition(Entity self, Entity target, double radius, double idealDistance) {
        return tacticalScanner.findSightPosition(self, target, radius, idealDistance);
    }

    /**
     * 占据视野点(坐标版,指定理想观察距离)。
     *
     * @param self          需要视野的实体
     * @param targetPos     观察目标位置坐标
     * @param radius        搜索半径
     * @param idealDistance 理想观察距离(方块)
     * @return 最佳视野位置;若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findSightPosition(Entity self, Vector3 targetPos, double radius, double idealDistance) {
        return tacticalScanner.findSightPosition(self, targetPos, radius, idealDistance);
    }

    /**
     * <b>模糊位置选取</b>(默认不确定半径 5 格):目标位置不精确时,
     * 在不确定区域内选取距自身最近的可站立搜索点。
     *
     * @param self   搜索实体
     * @param center 目标大致位置(不确定区域圆心)
     * @return 距自身最近的可站立搜索点;若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findApproximatePosition(Entity self, Vector3 center) {
        return findApproximatePosition(self, center, TacticalScanner.DEFAULT_UNCERTAINTY_RADIUS);
    }

    /**
     * <b>模糊位置选取</b>(指定不确定半径)。
     *
     * @param self              搜索实体
     * @param center            目标大致位置(不确定区域圆心)
     * @param uncertaintyRadius 不确定半径(方块)
     * @return 距自身最近的可站立搜索点;若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findApproximatePosition(Entity self, Vector3 center, double uncertaintyRadius) {
        return tacticalScanner.findApproximatePosition(self, center, uncertaintyRadius);
    }

    /**
     * 判断实体是否已进入模糊目标区域(到达大概位置)。
     *
     * @param self              实体
     * @param center            目标大致位置(不确定区域圆心)
     * @param uncertaintyRadius 不确定半径(方块)
     * @return true 表示已到达目标大致区域
     */
    public boolean hasReachedApproximate(Entity self, Vector3 center, double uncertaintyRadius) {
        return tacticalScanner.hasReachedApproximate(self, center, uncertaintyRadius);
    }

    // ========== 团队战术(→ TeamTactics) ==========

    /**
     * <b>协同包抄</b>:为一组实体分配不同侧翼方向,从多个角度钳形接近同一目标。
     *
     * @param members 包抄成员列表
     * @param target   包抄目标
     * @param radius   包抄点距目标的距离
     * @return 各成员的包抄位置(与 members 一一对应)
     */
    public List<TacticalPosition> flankTarget(List<Entity> members, Entity target, double radius) {
        return teamTactics.flankTarget(members, target, radius);
    }

    /**
     * <b>协同包抄</b>(指定弧线跨度):{@code arcSpanDegrees} 越小成员越集中在目标正后方,
     * 越大越接近环形包围。默认 180°。
     *
     * @param members        包抄成员列表
     * @param target         包抄目标
     * @param radius         包抄点距目标的距离
     * @param arcSpanDegrees 包抄弧线跨度(度,钳制到 [10, 360])
     * @return 各成员的包抄位置(与 members 一一对应)
     */
    public List<TacticalPosition> flankTarget(List<Entity> members, Entity target,
                                              double radius, double arcSpanDegrees) {
        return teamTactics.flankTarget(members, target, radius, arcSpanDegrees);
    }

    /**
     * <b>包围</b>:将实体均匀分布在目标周围 360°,形成环形包围。
     *
     * @param members 包围成员列表
     * @param target   被包围目标
     * @param radius   包围半径
     * @return 各成员的包围位置(与 members 一一对应)
     */
    public List<TacticalPosition> surroundTarget(List<Entity> members, Entity target, double radius) {
        return teamTactics.surroundTarget(members, target, radius);
    }

    /**
     * <b>集结</b>:让实体汇聚到集结点附近,按指定阵型排列。
     *
     * @param members    成员列表
     * @param rallyPoint 集结中心点
     * @param formation  阵型类型
     * @param spacing    成员间距
     * @return 各成员的集结位置(与 members 一一对应)
     */
    public List<TacticalPosition> rally(List<Entity> members, Vector3 rallyPoint,
                                        FormationType formation, double spacing) {
        return teamTactics.rally(members, rallyPoint, formation, spacing);
    }

    /**
     * <b>阵型偏移</b>(纯几何):给定阵型、人数、间距,返回每个成员相对中心的水平偏移。
     *
     * @param formation   阵型类型
     * @param memberCount 成员数量
     * @param spacing     成员间距
     * @return 偏移列表
     */
    public List<Vector3> formationOffsets(FormationType formation, int memberCount, double spacing) {
        return teamTactics.formationOffsets(formation, memberCount, spacing);
    }

    // ========== 插件绑定(转发给 NavigatorManager) ==========

    /**
     * 绑定关联插件实例,启动导航与循环行为调度。
     * <p>
     * 当通过 {@code JFrameMain} 使用时,框架会自动扫描 {@code PluginAware} Bean
     * (即 {@link NavigatorManager})并调用其 {@code bindPlugin},无需手动调用。
     *
     * @param plugin 插件实例
     */
    public void bindPlugin(Plugin plugin) {
        navigatorManager.bindPlugin(plugin);
    }

    /**
     * 获取关联的插件实例。
     *
     * @return 插件实例,或 null(尚未绑定)
     */
    public Plugin getPlugin() {
        return navigatorManager.getPlugin();
    }

    // ========== 组件 getter(高级直接访问) ==========

    /**
     * 获取默认寻路策略(A*)。
     *
     * @return 默认策略
     */
    public PathfindingStrategy getPathfindingStrategy() {
        return aStarPathFinder;
    }

    /**
     * 获取 A* 寻路器(直接调用高级寻路 API)。
     *
     * @return A* 寻路器
     */
    public AStarPathFinder getAStarPathFinder() {
        return aStarPathFinder;
    }

    /**
     * 获取贪心寻路器(低开销局部步进,适合大量实体)。
     *
     * @return 贪心寻路器
     */
    public GreedyPathFinder getGreedyPathFinder() {
        return greedyPathFinder;
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
     * 获取底层战斗动作实现。
     *
     * @return 战斗动作实现
     */
    public CombatActions getCombatActions() {
        return combatActions;
    }

    /**
     * 获取底层团队战术。
     *
     * @return 团队战术
     */
    public TeamTactics getTeamTactics() {
        return teamTactics;
    }

    // ========== 内部:游荡随机目标 ==========

    /**
     * 游荡目标:每次解析(每轮计算)返回圆内一个新随机点。
     * <p>
     * 圆心跟随实体当前位置(活引用),半径构造时固定;
     * Y 取实体当前高度,交由寻路处理爬升/下降。
     */
    private static final class WanderTarget implements Target {

        private final Entity self;
        private final double radius;

        WanderTarget(Entity self, double radius) {
            this.self = self;
            this.radius = radius;
        }

        @Override
        public Vector3 get() {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            double angle = r.nextDouble() * Math.PI * 2;
            double dist = radius * Math.sqrt(r.nextDouble());
            return new Vector3(
                    self.x + Math.cos(angle) * dist,
                    self.y,
                    self.z + Math.sin(angle) * dist);
        }
    }
}
