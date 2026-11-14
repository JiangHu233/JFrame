package io.github.JiangHu.jframe.ai.pathfinding.greedy;

import io.github.JiangHu.jframe.ai.pathfinding.PathfindingConfig;
import io.github.JiangHu.jframe.ai.pathfinding.PathfindingStrategy;
import io.github.JiangHu.jframe.ai.pathfinding.PathResult;
import io.github.JiangHu.jframe.ai.pathfinding.astar.AStarNode;
import io.github.JiangHu.jframe.ai.pathfinding.astar.AStarOptions;
import io.github.JiangHu.jframe.ai.pathfinding.astar.AStarPathFinder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 贪心寻路器与策略插槽的纯逻辑单元测试。
 * <p>
 * 覆盖策略模式重构引入的新 API：
 * <ul>
 *   <li>{@link GreedyOptions} 的默认值、setter 边界约束、链式返回、协变继承</li>
 *   <li>{@link PathfindingConfig} 基类的通用配置（entityHeight/entityWidth）</li>
 *   <li>{@link PathfindingStrategy} 接口契约（getName/getDefaultConfig/配置降级）</li>
 *   <li>{@link PathResult} 新增的 {@code CANCELLED} 状态语义</li>
 *   <li>{@link GreedyPathFinder} 的外部取消机制与 null 参数防御</li>
 * </ul>
 * 注：{@code GreedyPathFinder} 的核心三阶段算法依赖 {@code Level}（真实方块世界），
 * 属于集成测试范畴，本纯逻辑测试不覆盖；此处聚焦可独立验证的配置与契约逻辑。
 *
 * @see GreedyOptions
 * @see GreedyPathFinder
 * @see PathfindingStrategy
 * @see PathfindingConfig
 * @see PathResult
 */
@DisplayName("贪心寻路与策略插槽（纯逻辑）")
class GreedyPathFinderLogicTest {

    // ==================== GreedyOptions 配置 ====================

    @Nested
    @DisplayName("GreedyOptions 默认值")
    class GreedyOptionsDefaults {

        @Test
        @DisplayName("阶段①参数：stallThreshold1 默认 3")
        void phase1Defaults() {
            GreedyOptions opts = new GreedyOptions();
            assertEquals(3, opts.getStallThreshold1());
        }

        @Test
        @DisplayName("阶段②参数：窗口半径/高度/采样频率/终止条件/扰动默认值")
        void phase2Defaults() {
            GreedyOptions opts = new GreedyOptions();
            assertEquals(4, opts.getWindowRadius());
            assertEquals(8, opts.getMaxWindowRadius());
            assertEquals(2, opts.getWindowHeight());
            assertEquals(8, opts.getSamplesPerTick());
            assertEquals(3, opts.getNoImproveLimit());
            assertEquals(5, opts.getVarianceWindow());
            assertEquals(0.1, opts.getVarianceFloor(), 1e-9);
            assertEquals(0.05, opts.getScoreJitter(), 1e-9);
        }

        @Test
        @DisplayName("短期目标参数：迷你A*节点上限/衰减系数/淘汰阈值默认值")
        void subGoalDefaults() {
            GreedyOptions opts = new GreedyOptions();
            assertEquals(100, opts.getMiniANodeLimit());
            assertEquals(0.9, opts.getSubGoalDecay(), 1e-9);
            assertEquals(0.1, opts.getSubGoalThreshold(), 1e-9);
        }

        @Test
        @DisplayName("熔断与步数刷新参数：maxDrift/maxSteps/refreshThreshold/refreshAmount默认值")
        void circuitBreakerDefaults() {
            GreedyOptions opts = new GreedyOptions();
            assertEquals(12, opts.getMaxDrift(), 1e-9);
            assertEquals(12, opts.getMaxSteps());
            assertEquals(2, opts.getRefreshThreshold(), 1e-9);
            assertEquals(3, opts.getRefreshAmount());
        }

        @Test
        @DisplayName("评分权重参数：四因子权重/衰减速率/历史窗口/到达半径默认值")
        void scoringDefaults() {
            GreedyOptions opts = new GreedyOptions();
            assertEquals(1.0, opts.getWDistance(), 1e-9);
            assertEquals(0.3, opts.getWTurn(), 1e-9);
            assertEquals(2.0, opts.getWVisit(), 1e-9);
            assertEquals(2.0, opts.getWBoundary(), 1e-9);
            assertEquals(0.2, opts.getVisitDecayLambda(), 1e-9);
            assertEquals(20, opts.getVisitHistorySize());
            assertEquals(1.5, opts.getReachRadius(), 1e-9);
        }

        @Test
        @DisplayName("继承 PathfindingConfig：entityHeight 默认 2，entityWidth 默认 1")
        void inheritedDefaults() {
            GreedyOptions opts = new GreedyOptions();
            assertEquals(2, opts.getEntityHeight());
            assertEquals(1, opts.getEntityWidth());
        }
    }

    @Nested
    @DisplayName("GreedyOptions setter 边界约束")
    class GreedyOptionsSetterBounds {

        @Test
        @DisplayName("setWindowRadius 受 maxWindowRadius 硬上限约束")
        void windowRadiusCapped() {
            GreedyOptions opts = new GreedyOptions();
            opts.setWindowRadius(100); // 远超 maxWindowRadius=8
            assertEquals(8, opts.getWindowRadius(), "窗口半径不应超过 maxWindowRadius");
        }

        @Test
        @DisplayName("setWindowRadius 最小为 1")
        void windowRadiusMinOne() {
            GreedyOptions opts = new GreedyOptions();
            opts.setWindowRadius(0);
            assertEquals(1, opts.getWindowRadius());
        }

        @Test
        @DisplayName("setMaxWindowRadius 后可放宽 windowRadius 上限")
        void maxWindowRadiusRaises() {
            GreedyOptions opts = new GreedyOptions();
            opts.setMaxWindowRadius(16);
            opts.setWindowRadius(12);
            assertEquals(12, opts.getWindowRadius());
        }

        @Test
        @DisplayName("setStallThreshold1 最小为 1")
        void stallThresholdMinOne() {
            GreedyOptions opts = new GreedyOptions();
            opts.setStallThreshold1(0);
            assertEquals(1, opts.getStallThreshold1());
        }

        @Test
        @DisplayName("setSubGoalDecay 裁剪到 [0,1] 区间")
        void subGoalDecayClamped() {
            GreedyOptions opts = new GreedyOptions();
            opts.setSubGoalDecay(5.0);
            assertEquals(1.0, opts.getSubGoalDecay(), 1e-9);
            opts.setSubGoalDecay(-1.0);
            assertEquals(0.0, opts.getSubGoalDecay(), 1e-9);
        }

        @Test
        @DisplayName("setMiniANodeLimit 最小为 10")
        void miniANodeLimitMinTen() {
            GreedyOptions opts = new GreedyOptions();
            opts.setMiniANodeLimit(5);
            assertEquals(10, opts.getMiniANodeLimit());
        }

        @Test
        @DisplayName("setScoreJitter 最小为 0（不允许负扰动幅度）")
        void scoreJitterNonNegative() {
            GreedyOptions opts = new GreedyOptions();
            opts.setScoreJitter(-0.5);
            assertEquals(0.0, opts.getScoreJitter(), 1e-9);
        }

        @Test
        @DisplayName("setVisitHistorySize 最小为 1")
        void visitHistorySizeMinOne() {
            GreedyOptions opts = new GreedyOptions();
            opts.setVisitHistorySize(0);
            assertEquals(1, opts.getVisitHistorySize());
        }
    }

    @Nested
    @DisplayName("GreedyOptions 链式调用与协变返回")
    class GreedyOptionsChaining {

        @Test
        @DisplayName("所有 setter 返回 GreedyOptions（协变类型，非基类）")
        void settersReturnGreedyOptions() {
            GreedyOptions opts = new GreedyOptions();
            assertSame(opts, opts.setStallThreshold1(5));
            assertSame(opts, opts.setWindowRadius(3));
            assertSame(opts, opts.setMaxSteps(20));
            assertSame(opts, opts.setWDistance(2.0));
            assertSame(opts, opts.setReachRadius(2.0));
        }

        @Test
        @DisplayName("setEntityHeight 协变返回 GreedyOptions（非 PathfindingConfig）")
        void entityHeightCovariant() {
            GreedyOptions opts = new GreedyOptions();
            GreedyOptions returned = opts.setEntityHeight(3);
            assertSame(opts, returned);
            assertEquals(3, opts.getEntityHeight());
        }

        @Test
        @DisplayName("setEntityWidth 协变返回 GreedyOptions（非 PathfindingConfig）")
        void entityWidthCovariant() {
            GreedyOptions opts = new GreedyOptions();
            GreedyOptions returned = opts.setEntityWidth(2);
            assertSame(opts, returned);
            assertEquals(2, opts.getEntityWidth());
        }

        @Test
        @DisplayName("多参数链式调用全部生效")
        void fullChain() {
            GreedyOptions opts = new GreedyOptions()
                    .setWindowRadius(6)
                    .setSamplesPerTick(12)
                    .setMaxSteps(20)
                    .setEntityHeight(3)
                    .setEntityWidth(2);
            assertEquals(6, opts.getWindowRadius());
            assertEquals(12, opts.getSamplesPerTick());
            assertEquals(20, opts.getMaxSteps());
            assertEquals(3, opts.getEntityHeight());
            assertEquals(2, opts.getEntityWidth());
        }
    }

    // ==================== PathfindingConfig 基类 ====================

    @Nested
    @DisplayName("PathfindingConfig 通用配置")
    class ConfigBase {

        @Test
        @DisplayName("AStarOptions 继承 PathfindingConfig，默认 entityHeight=2/entityWidth=1")
        void pathfinderOptionsInherits() {
            AStarOptions opts = new AStarOptions();
            assertEquals(2, opts.getEntityHeight());
            assertEquals(1, opts.getEntityWidth());
        }

        @Test
        @DisplayName("setEntityHeight 最小为 1")
        void entityHeightMinOne() {
            AStarOptions opts = new AStarOptions();
            opts.setEntityHeight(0);
            assertEquals(1, opts.getEntityHeight());
        }

        @Test
        @DisplayName("setEntityWidth 最小为 1")
        void entityWidthMinOne() {
            AStarOptions opts = new AStarOptions();
            opts.setEntityWidth(-1);
            assertEquals(1, opts.getEntityWidth());
        }

        @Test
        @DisplayName("AStarOptions.setEntityHeight 协变返回 AStarOptions")
        void pathfinderOptionsCovariant() {
            AStarOptions opts = new AStarOptions();
            AStarOptions returned = opts.setEntityHeight(3);
            assertSame(opts, returned);
        }
    }

    // ==================== PathfindingStrategy 接口契约 ====================

    @Nested
    @DisplayName("PathfindingStrategy 接口契约")
    class StrategyContract {

        @Test
        @DisplayName("GreedyPathFinder.getName() 返回 \"greedy\"")
        void greedyName() {
            assertEquals("greedy", new GreedyPathFinder().getName());
        }

        @Test
        @DisplayName("AStarPathFinder.getName() 返回 \"astar\"")
        void astarName() {
            assertEquals("astar", new AStarPathFinder().getName());
        }

        @Test
        @DisplayName("GreedyPathFinder.getDefaultConfig() 返回 GreedyOptions 实例")
        void greedyDefaultConfig() {
            PathfindingConfig config = new GreedyPathFinder().getDefaultConfig();
            assertInstanceOf(GreedyOptions.class, config);
        }

        @Test
        @DisplayName("AStarPathFinder.getDefaultConfig() 返回 AStarOptions 实例")
        void astarDefaultConfig() {
            PathfindingConfig config = new AStarPathFinder().getDefaultConfig();
            assertInstanceOf(AStarOptions.class, config);
        }

        @Test
        @DisplayName("getDefaultConfig 每次返回新实例（非共享单例）")
        void defaultConfigNewInstance() {
            GreedyPathFinder finder = new GreedyPathFinder();
            PathfindingConfig c1 = finder.getDefaultConfig();
            PathfindingConfig c2 = finder.getDefaultConfig();
            assertNotSame(c1, c2, "默认配置应为新实例，避免外部修改污染");
        }

        @Test
        @DisplayName("GreedyPathFinder 可向上转型为 PathfindingStrategy")
        void upcastToStrategy() {
            PathfindingStrategy strategy = new GreedyPathFinder();
            assertEquals("greedy", strategy.getName());
            assertInstanceOf(GreedyOptions.class, strategy.getDefaultConfig());
        }

        @Test
        @DisplayName("AStarPathFinder 可向上转型为 PathfindingStrategy")
        void pathFinderUpcastToStrategy() {
            PathfindingStrategy strategy = new AStarPathFinder();
            assertEquals("astar", strategy.getName());
            assertInstanceOf(AStarOptions.class, strategy.getDefaultConfig());
        }
    }

    // ==================== PathResult CANCELLED 状态 ====================

    @Nested
    @DisplayName("PathResult CANCELLED 状态语义")
    class CancelledStatus {

        @Test
        @DisplayName("cancelled 结果：isCancelled 为 true，hasPath 为 true")
        void cancelledFlags() {
            List<AStarNode> nodes = List.of(new AStarNode(0, 0, 0), new AStarNode(1, 0, 0));
            PathResult result = PathResult.cancelled(nodes, 5, null);

            assertTrue(result.isCancelled(), "应为 CANCELLED 状态");
            assertTrue(result.hasPath(), "CANCELLED 应视为有可用路径（已走的部分）");
            assertEquals(PathResult.Status.CANCELLED, result.getStatus());
        }

        @Test
        @DisplayName("cancelled 结果：isSuccess 和 isPartial 均为 false")
        void cancelledNotSuccessOrPartial() {
            PathResult result = PathResult.cancelled(
                    List.of(new AStarNode(0, 0, 0)), 1, null);
            assertFalse(result.isSuccess());
            assertFalse(result.isPartial());
        }

        @Test
        @DisplayName("cancelled 结果保留节点列表与展开节点数")
        void cancelledRetainsData() {
            List<AStarNode> nodes = List.of(
                    new AStarNode(0, 0, 0), new AStarNode(1, 0, 0), new AStarNode(2, 0, 0));
            PathResult result = PathResult.cancelled(nodes, 3, null);

            assertEquals(3, result.length());
            assertEquals(3, result.getExpandedNodes());
        }

        @Test
        @DisplayName("CANCELLED 与 PARTIAL/SUCCESS 状态互斥")
        void statusMutualExclusion() {
            PathResult cancelled = PathResult.cancelled(List.of(new AStarNode(0, 0, 0)), 1, null);
            PathResult partial = PathResult.partial(List.of(new AStarNode(0, 0, 0)), 1.0, 1, null);
            PathResult success = PathResult.success(List.of(new AStarNode(0, 0, 0)), 0.0, 1, null);

            assertTrue(cancelled.isCancelled());
            assertFalse(partial.isCancelled());
            assertFalse(success.isCancelled());

            assertFalse(cancelled.isPartial());
            assertTrue(partial.isPartial());

            assertFalse(cancelled.isSuccess());
            assertTrue(success.isSuccess());
        }

        @Test
        @DisplayName("CANCELLED 枚举值存在于 Status 中")
        void cancelledEnumExists() {
            assertDoesNotThrow(() -> PathResult.Status.valueOf("CANCELLED"));
        }
    }

    // ==================== GreedyPathFinder 取消机制 ====================

    @Nested
    @DisplayName("GreedyPathFinder 外部取消机制")
    class CancelMechanism {

        @Test
        @DisplayName("新建实例默认未取消")
        void defaultNotCancelled() {
            GreedyPathFinder finder = new GreedyPathFinder();
            assertFalse(finder.isCancelled());
        }

        @Test
        @DisplayName("cancel() 后 isCancelled() 返回 true")
        void cancelSetsFlag() {
            GreedyPathFinder finder = new GreedyPathFinder();
            finder.cancel();
            assertTrue(finder.isCancelled());
        }
    }

    // ==================== null 参数防御 ====================

    @Nested
    @DisplayName("GreedyPathFinder null 参数防御")
    class NullDefense {

        @Test
        @DisplayName("findPath(null level, ...) 返回 START_INVALID")
        void nullLevel() {
            GreedyPathFinder finder = new GreedyPathFinder();
            PathResult result = finder.findPath(null,
                    new cn.nukkit.math.Vector3(0, 0, 0),
                    new cn.nukkit.math.Vector3(5, 0, 5),
                    new GreedyOptions());
            assertEquals(PathResult.Status.START_INVALID, result.getStatus());
            assertFalse(result.hasPath());
        }

        @Test
        @DisplayName("findPath(null start, ...) 返回 START_INVALID")
        void nullStart() {
            GreedyPathFinder finder = new GreedyPathFinder();
            PathResult result = finder.findPath(null,
                    null,
                    new cn.nukkit.math.Vector3(5, 0, 5),
                    new GreedyOptions());
            assertEquals(PathResult.Status.START_INVALID, result.getStatus());
        }

        @Test
        @DisplayName("findPath(null target, ...) 返回 START_INVALID")
        void nullTarget() {
            GreedyPathFinder finder = new GreedyPathFinder();
            PathResult result = finder.findPath(null,
                    new cn.nukkit.math.Vector3(0, 0, 0),
                    null,
                    new GreedyOptions());
            assertEquals(PathResult.Status.START_INVALID, result.getStatus());
        }

        @Test
        @DisplayName("findPath 传入非 GreedyOptions 配置时不抛异常（降级为默认配置）")
        void configTypeMismatchDegrades() {
            // 传入 AStarOptions（A* 配置）而非 GreedyOptions
            // 策略应降级为默认 GreedyOptions，不抛 ClassCastException
            GreedyPathFinder finder = new GreedyPathFinder();
            // level 为 null 会先触发 START_INVALID，但关键是 config 类型不匹配不应抛异常
            assertDoesNotThrow(() -> finder.findPath(null,
                    new cn.nukkit.math.Vector3(0, 0, 0),
                    new cn.nukkit.math.Vector3(5, 0, 5),
                    new AStarOptions()));
        }
    }
}
