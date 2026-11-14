package io.github.JiangHu.jframe.ai.pathfinding;

import io.github.JiangHu.jframe.ai.pathfinding.astar.AStarNode;
import io.github.JiangHu.jframe.ai.pathfinding.astar.AStarOptions;
import io.github.JiangHu.jframe.ai.pathfinding.astar.StepCostFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 寻路扩展能力纯逻辑单元测试。
 * <p>
 * 覆盖边走边搜与外接评分器引入的新 API：
 * <ul>
 *   <li>{@link PathResult} 的 {@code PARTIAL} 状态与 {@code hasPath()} 判定</li>
 *   <li>{@link AStarOptions} 的 {@code partialOnFailure} / {@code stepCostFunction} 链式配置</li>
 *   <li>{@link StepCostFunction} 作为函数式接口的可调用性</li>
 * </ul>
 * 注：{@code PathResult.partial/success/failed} 为公共工厂方法，供各算法实现子包构造结果。
 *
 * @see PathResult
 * @see AStarOptions
 * @see StepCostFunction
 */
@DisplayName("寻路扩展能力（边走边搜 / 外接评分器）")
class ExtendedPathfindingLogicTest {

    // ==================== PathResult 状态判定 ====================

    @Nested
    @DisplayName("PathResult 部分路径状态")
    class PartialPathResult {

        @Test
        @DisplayName("partial 结果：isPartial 为 true，hasPath 为 true，isSuccess 为 false")
        void partialFlags() {
            List<AStarNode> nodes = List.of(new AStarNode(0, 0, 0), new AStarNode(1, 0, 0));
            PathResult result = PathResult.partial(nodes, 1.5, 42, null);

            assertTrue(result.isPartial());
            assertTrue(result.hasPath(), "部分路径应视为有可用路径");
            assertFalse(result.isSuccess(), "部分路径不应视为成功");
            assertEquals(PathResult.Status.PARTIAL, result.getStatus());
        }

        @Test
        @DisplayName("partial 结果保留传入的节点列表与代价")
        void partialRetainsNodesAndCost() {
            List<AStarNode> nodes = List.of(new AStarNode(0, 0, 0), new AStarNode(2, 0, 0));
            PathResult result = PathResult.partial(nodes, 3.3, 10, null);

            assertSame(nodes, result.getNodes());
            assertEquals(2, result.length());
            assertEquals(3.3, result.getTotalCost(), 1e-9);
            assertEquals(10, result.getExpandedNodes());
        }
    }

    @Nested
    @DisplayName("PathResult 失败与成功状态对比")
    class OtherStatus {

        @Test
        @DisplayName("failed 结果：hasPath 为 false，节点为空")
        void failedHasNoPath() {
            PathResult result = PathResult.failed(PathResult.Status.NO_PATH, 100, null);

            assertFalse(result.hasPath());
            assertFalse(result.isSuccess());
            assertFalse(result.isPartial());
            assertTrue(result.getNodes().isEmpty());
            assertEquals(0, result.length());
        }

        @Test
        @DisplayName("success 结果：isSuccess 与 hasPath 均为 true")
        void successHasPath() {
            List<AStarNode> nodes = List.of(new AStarNode(0, 0, 0), new AStarNode(5, 0, 0));
            PathResult result = PathResult.success(nodes, 5.0, 8, null);

            assertTrue(result.isSuccess());
            assertTrue(result.hasPath());
            assertFalse(result.isPartial());
        }
    }

    // ==================== AStarOptions 扩展配置 ====================

    @Nested
    @DisplayName("AStarOptions 边走边搜与评分器配置")
    class OptionsExtensions {

        @Test
        @DisplayName("默认 partialOnFailure 为 false，stepCostFunction 为 null")
        void defaults() {
            AStarOptions opts = new AStarOptions();
            assertFalse(opts.isPartialOnFailure());
            assertNull(opts.getStepCostFunction());
        }

        @Test
        @DisplayName("setPartialOnFailure 链式返回 this 并生效")
        void setPartialOnFailureChained() {
            AStarOptions opts = new AStarOptions();
            AStarOptions returned = opts.setPartialOnFailure(true);

            assertSame(opts, returned, "链式 setter 应返回 this");
            assertTrue(opts.isPartialOnFailure());
        }

        @Test
        @DisplayName("setStepCostFunction 链式返回 this 并保存评分器")
        void setStepCostFunctionChained() {
            StepCostFunction fn = (level, from, to, diagonal, options) -> 0.0;
            AStarOptions opts = new AStarOptions();
            AStarOptions returned = opts.setStepCostFunction(fn);

            assertSame(opts, returned, "链式 setter 应返回 this");
            assertSame(fn, opts.getStepCostFunction());
        }

        @Test
        @DisplayName("setStepCostFunction(null) 可清除评分器")
        void clearStepCostFunction() {
            AStarOptions opts = new AStarOptions()
                    .setStepCostFunction((l, f, t, d, o) -> 1.0)
                    .setStepCostFunction(null);

            assertNull(opts.getStepCostFunction());
        }
    }

    // ==================== StepCostFunction 函数式接口 ====================

    @Nested
    @DisplayName("StepCostFunction 评分器")
    class CostFunction {

        @Test
        @DisplayName("lambda 可正常调用并返回常量代价")
        void constantCost() {
            StepCostFunction addTen = (level, from, to, diagonal, options) -> 10.0;
            AStarNode from = new AStarNode(0, 0, 0);
            AStarNode to = new AStarNode(1, 0, 0);

            assertEquals(10.0, addTen.extraCost(null, from, to, false, new AStarOptions()), 1e-9);
        }

        @Test
        @DisplayName("可基于对角线标志返回不同代价")
        void diagonalAware() {
            StepCostFunction fn = (level, from, to, diagonal, options) -> diagonal ? 5.0 : 1.0;
            AStarNode from = new AStarNode(0, 0, 0);

            assertEquals(1.0, fn.extraCost(null, from, new AStarNode(1, 0, 0), false, new AStarOptions()));
            assertEquals(5.0, fn.extraCost(null, from, new AStarNode(1, 0, 1), true, new AStarOptions()));
        }

        @Test
        @DisplayName("可返回 POSITIVE_INFINITY 表示禁止通行")
        void infiniteCostBlocksStep() {
            StepCostFunction blocker = (level, from, to, diagonal, options) -> Double.POSITIVE_INFINITY;

            assertEquals(Double.POSITIVE_INFINITY,
                    blocker.extraCost(null, new AStarNode(0, 0, 0), new AStarNode(1, 0, 0), false, new AStarOptions()));
        }

        @Test
        @DisplayName("可读取目标节点坐标做条件判定")
        void coordinateBased() {
            // 经过 x 坐标为偶数的方块时增加代价
            StepCostFunction avoidEven = (level, from, to, diagonal, options) ->
                    (to.x % 2 == 0) ? 7.0 : 0.0;

            assertEquals(7.0, avoidEven.extraCost(null, new AStarNode(0, 0, 0), new AStarNode(2, 0, 0), false, new AStarOptions()));
            assertEquals(0.0, avoidEven.extraCost(null, new AStarNode(0, 0, 0), new AStarNode(3, 0, 0), false, new AStarOptions()));
        }
    }
}
