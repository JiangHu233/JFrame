package io.github.JiangHu.jframe.ai.pathfinding;

import cn.nukkit.math.Vector3;
import io.github.JiangHu.jframe.ai.core.tactical.TacticalPosition;
import io.github.JiangHu.jframe.ai.core.tactical.TacticalScanner;
import io.github.JiangHu.jframe.ai.pathfinding.astar.AStarNode;
import io.github.JiangHu.jframe.ai.pathfinding.astar.AStarOptions;
import io.github.JiangHu.jframe.ai.pathfinding.astar.AStarPathFinder;
import io.github.JiangHu.jframe.ai.pathfinding.astar.HeuristicType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 寻路核心纯逻辑单元测试。
 * <p>
 * 覆盖不依赖运行中 Nukkit 服务器的组件：{@link AStarNode}、{@link HeuristicType}、
 * {@link AStarOptions}、{@link PathResult}、{@link TacticalPosition}。
 * <p>
 * 涉及 {@link cn.nukkit.level.Level} 的 {@link AStarPathFinder} / {@link TacticalScanner}
 * 需在真实服务器环境集成验证，此处不覆盖（Level 依赖已加载的世界区块）。
 *
 * @see AStarNode
 * @see HeuristicType
 * @see AStarOptions
 * @see PathResult
 * @see TacticalPosition
 */
@DisplayName("AI 寻路核心逻辑")
class PathFinderLogicTest {

    // ==================== AStarNode ====================

    @Nested
    @DisplayName("AStarNode 节点")
    class BlockNodeTest {

        @Test
        @DisplayName("fCost = gCost + hCost")
        void fCostIsSumOfGAndH() {
            AStarNode node = new AStarNode(1, 2, 3);
            node.gCost = 2.5;
            node.hCost = 4.0;
            assertEquals(6.5, node.fCost(), 1e-9);
        }

        @Test
        @DisplayName("相等性仅基于坐标，与评分无关")
        void equalsByCoordinatesOnly() {
            AStarNode a = new AStarNode(1, 2, 3);
            a.gCost = 10;
            a.hCost = 20;
            AStarNode b = new AStarNode(1, 2, 3);
            b.gCost = 99;
            b.hCost = 88;
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("不同坐标的节点不相等")
        void notEqualsWhenCoordinatesDiffer() {
            assertNotEquals(new AStarNode(1, 2, 3), new AStarNode(1, 2, 4));
            assertNotEquals(new AStarNode(1, 2, 3), new AStarNode(0, 2, 3));
        }

        @Test
        @DisplayName("可作为 HashSet 元素（按坐标去重）")
        void usableAsHashSetElement() {
            Set<AStarNode> set = new HashSet<>();
            set.add(new AStarNode(1, 2, 3));
            set.add(new AStarNode(1, 2, 3));
            set.add(new AStarNode(4, 5, 6));
            assertEquals(2, set.size());
            assertTrue(set.contains(new AStarNode(1, 2, 3)));
        }

        @Test
        @DisplayName("horizontalDistanceSquared 忽略 Y")
        void horizontalDistanceSquaredIgnoresY() {
            AStarNode a = new AStarNode(0, 0, 0);
            AStarNode b = new AStarNode(3, 99, 4);
            assertEquals(25.0, a.horizontalDistanceSquared(b), 1e-9);
        }

        @Test
        @DisplayName("distance3D 计算三维欧几里得距离")
        void distance3D() {
            AStarNode a = new AStarNode(0, 0, 0);
            AStarNode b = new AStarNode(3, 4, 0);
            assertEquals(5.0, a.distance3D(b), 1e-9);
        }

        @Test
        @DisplayName("fromVector 向下取整为方块坐标")
        void fromVectorFloors() {
            AStarNode node = AStarNode.fromVector(new Vector3(1.6, 64.2, -2.7));
            assertEquals(1, node.x);
            assertEquals(64, node.y);
            assertEquals(-3, node.z);
        }
    }

    // ==================== HeuristicType ====================

    @Nested
    @DisplayName("HeuristicType 启发函数")
    class HeuristicTest {

        @Test
        @DisplayName("MANHATTAN 为各轴绝对差之和")
        void manhattan() {
            assertEquals(7.0, HeuristicType.MANHATTAN.estimate(3, 0, 4), 1e-9);
            assertEquals(6.0, HeuristicType.MANHATTAN.estimate(1, 2, 3), 1e-9);
        }

        @Test
        @DisplayName("EUCLIDEAN 为三维直线距离")
        void euclidean() {
            assertEquals(5.0, HeuristicType.EUCLIDEAN.estimate(3, 0, 4), 1e-9);
            assertEquals(Math.sqrt(3), HeuristicType.EUCLIDEAN.estimate(1, 1, 1), 1e-9);
        }

        @Test
        @DisplayName("CHEBYSHEV 为各轴绝对差的最大值")
        void chebyshev() {
            assertEquals(4.0, HeuristicType.CHEBYSHEV.estimate(3, 0, 4), 1e-9);
            assertEquals(5.0, HeuristicType.CHEBYSHEV.estimate(1, 5, 2), 1e-9);
        }

        @Test
        @DisplayName("OCTILE 为水平八分距离 + 垂直曼哈顿")
        void octile() {
            // 纯对角：√2·3
            assertEquals(3 * Math.sqrt(2), HeuristicType.OCTILE.estimate(3, 0, 3), 1e-9);
            // 直线 + 对角：(4-1) + √2·1
            assertEquals(3 + Math.sqrt(2), HeuristicType.OCTILE.estimate(4, 0, 1), 1e-9);
            // 垂直分量按曼哈顿叠加：(4-1) + √2·1 + 2
            assertEquals(5 + Math.sqrt(2), HeuristicType.OCTILE.estimate(4, 2, 1), 1e-9);
        }

        @Test
        @DisplayName("零距离时三种启发式均为 0")
        void zeroDistance() {
            for (HeuristicType h : HeuristicType.values()) {
                assertEquals(0.0, h.estimate(0, 0, 0), 1e-9);
            }
        }
    }

    // ==================== AStarOptions ====================

    @Nested
    @DisplayName("AStarOptions 参数配置")
    class OptionsTest {

        @Test
        @DisplayName("默认值符合预期")
        void defaults() {
            AStarOptions opts = new AStarOptions();
            assertEquals(1500, opts.getMaxSearchNodes());
            assertTrue(opts.isAllowDiagonal());
            assertTrue(opts.isAllowJump());
            assertEquals(3, opts.getMaxDropHeight());
            assertEquals(2, opts.getEntityHeight());
            assertEquals(HeuristicType.OCTILE, opts.getHeuristic());
            assertEquals(1.41421356, opts.getDiagonalCost(), 1e-9);
            assertEquals(1.0, opts.getGoalReachRadius(), 1e-9);
        }

        @Test
        @DisplayName("链式 setter 返回自身并生效")
        void fluentSetters() {
            AStarOptions opts = new AStarOptions()
                    .setMaxSearchNodes(2000)
                    .setAllowDiagonal(false)
                    .setAllowJump(false)
                    .setMaxDropHeight(5)
                    .setEntityHeight(3)
                    .setHeuristic(HeuristicType.EUCLIDEAN)
                    .setDiagonalCost(1.5)
                    .setGoalReachRadius(2.0);
            assertEquals(2000, opts.getMaxSearchNodes());
            assertFalse(opts.isAllowDiagonal());
            assertFalse(opts.isAllowJump());
            assertEquals(5, opts.getMaxDropHeight());
            assertEquals(3, opts.getEntityHeight());
            assertEquals(HeuristicType.EUCLIDEAN, opts.getHeuristic());
            assertEquals(1.5, opts.getDiagonalCost(), 1e-9);
            assertEquals(2.0, opts.getGoalReachRadius(), 1e-9);
        }

        @Test
        @DisplayName("maxSearchNodes 下限保护为 16")
        void maxSearchNodesLowerBound() {
            assertEquals(16, new AStarOptions().setMaxSearchNodes(1).getMaxSearchNodes());
        }

        @Test
        @DisplayName("maxDropHeight 下限保护为 1")
        void maxDropHeightLowerBound() {
            assertEquals(1, new AStarOptions().setMaxDropHeight(0).getMaxDropHeight());
        }

        @Test
        @DisplayName("entityHeight 下限保护为 1")
        void entityHeightLowerBound() {
            assertEquals(1, new AStarOptions().setEntityHeight(-5).getEntityHeight());
        }

        @Test
        @DisplayName("setHeuristic(null) 回退为 OCTILE")
        void heuristicNullFallback() {
            assertEquals(HeuristicType.OCTILE, new AStarOptions().setHeuristic(null).getHeuristic());
        }
    }

    // ==================== PathResult ====================

    @Nested
    @DisplayName("PathResult 寻路结果")
    class PathResultTest {

        @Test
        @DisplayName("failed 结果不可用且节点为空")
        void failedResult() {
            PathResult result = PathResult.failed(PathResult.Status.NO_PATH, 42, null);
            assertFalse(result.isSuccess());
            assertEquals(PathResult.Status.NO_PATH, result.getStatus());
            assertTrue(result.getNodes().isEmpty());
            assertEquals(0, result.length());
            assertEquals(Double.POSITIVE_INFINITY, result.getTotalCost());
            assertEquals(42, result.getExpandedNodes());
            assertNull(result.getDestination());
        }

        @Test
        @DisplayName("success 结果可用且包含节点")
        void successResult() {
            List<AStarNode> nodes = List.of(new AStarNode(0, 0, 0), new AStarNode(1, 0, 0));
            PathResult result = PathResult.success(nodes, 1.0, 2, null);
            assertTrue(result.isSuccess());
            assertEquals(PathResult.Status.SUCCESS, result.getStatus());
            assertEquals(2, result.length());
            assertEquals(1.0, result.getTotalCost(), 1e-9);
        }

        @Test
        @DisplayName("ALREADY_AT_GOAL 视为成功")
        void alreadyAtGoalIsSuccess() {
            PathResult result = PathResult.failed(PathResult.Status.ALREADY_AT_GOAL, 0, null);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("START_INVALID 不视为成功")
        void startInvalidIsFailure() {
            assertFalse(PathResult.failed(PathResult.Status.START_INVALID, 0, null).isSuccess());
        }
    }

    // ==================== TacticalPosition ====================

    @Nested
    @DisplayName("TacticalPosition 战术位置")
    class TacticalPositionTest {

        @Test
        @DisplayName("empty 表示无可用位置")
        void emptyIsNotPresent() {
            TacticalPosition pos = TacticalPosition.empty();
            assertFalse(pos.isPresent());
            assertNull(pos.position());
            assertEquals(-1.0, pos.score(), 1e-9);
        }

        @Test
        @DisplayName("有效位置 isPresent 为 true")
        void validPositionIsPresent() {
            TacticalPosition pos = new TacticalPosition(new Vector3(10, 64, 20), 0.8, 15.0);
            assertTrue(pos.isPresent());
            assertEquals(0.8, pos.score(), 1e-9);
            assertEquals(15.0, pos.distanceToThreat(), 1e-9);
        }

        @Test
        @DisplayName("empty 的 toBlockNode 返回 null")
        void emptyToBlockNodeIsNull() {
            assertNull(TacticalPosition.empty().toBlockNode());
        }

        @Test
        @DisplayName("toBlockNode 向下取整为方块坐标")
        void toBlockNodeFloors() {
            TacticalPosition pos = new TacticalPosition(new Vector3(10.9, 64.1, 20.5), 1.0, 0);
            AStarNode node = pos.toBlockNode();
            assertNotNull(node);
            assertEquals(10, node.x);
            assertEquals(64, node.y);
            assertEquals(20, node.z);
        }
    }
}
