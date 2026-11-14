package io.github.JiangHu.jframe.ai.core.tactical;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TacticalScanner#hasReachedApproximate(double, double, double, double, double)}
 * 的纯逻辑单元测试。
 * <p>
 * 该包级可见方法不依赖实体或世界，仅做水平距离与不确定半径的比较，
 * 是 {@link TacticalScanner#hasReachedApproximate} 的核心计算内核。
 */
@DisplayName("模糊位置选取 - hasReachedApproximate 纯逻辑测试")
class ApproximatePositionLogicTest {

    /** 调用包级可见的纯坐标版判断。 */
    private static boolean reached(double selfX, double selfZ,
                                   double centerX, double centerZ,
                                   double radius) {
        return TacticalScanner.hasReachedApproximate(selfX, selfZ, centerX, centerZ, radius);
    }

    @Nested
    @DisplayName("圆心与边界")
    class CenterAndBoundary {

        @Test
        @DisplayName("正好在圆心 → 已到达")
        void atCenter() {
            assertTrue(reached(0, 0, 0, 0, 5));
        }

        @Test
        @DisplayName("圆内（距离 < 半径）→ 已到达")
        void insideCircle() {
            assertTrue(reached(3, 4, 0, 0, 6));   // 距离 = 5 < 6
        }

        @Test
        @DisplayName("圆边缘（距离 = 半径）→ 已到达（闭区间）")
        void onBoundary() {
            assertTrue(reached(3, 4, 0, 0, 5));   // 距离 = 5 = 5
        }

        @Test
        @DisplayName("圆外（距离 > 半径）→ 未到达")
        void outsideCircle() {
            assertFalse(reached(3, 4, 0, 0, 4));  // 距离 = 5 > 4
        }
    }

    @Nested
    @DisplayName("各象限对称性")
    class QuadrantSymmetry {

        @Test
        @DisplayName("四个正方向等距 → 结果一致")
        void fourCardinalDirections() {
            double r = 10;
            boolean east = reached(8, 0, 0, 0, r);   // 距离 8
            boolean west = reached(-8, 0, 0, 0, r);
            boolean north = reached(0, 8, 0, 0, r);
            boolean south = reached(0, -8, 0, 0, r);
            assertTrue(east);
            assertTrue(west);
            assertTrue(north);
            assertTrue(south);
        }

        @Test
        @DisplayName("对角线方向（3-4-5 三角形）→ 距离 5")
        void diagonal() {
            assertTrue(reached(3, 4, 0, 0, 5));    // √(9+16) = 5
            assertFalse(reached(3, 4, 0, 0, 4.9));
        }

        @Test
        @DisplayName("圆心偏移到非原点")
        void offsetCenter() {
            // 圆心 (10, 10)，实体 (13, 14)，距离 = 5
            assertTrue(reached(13, 14, 10, 10, 5));
            assertFalse(reached(13, 14, 10, 10, 4));
        }
    }

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @Test
        @DisplayName("半径 0 → 仅圆心到达")
        void zeroRadius() {
            assertTrue(reached(5, 5, 5, 5, 0));    // 正好在圆心
            assertFalse(reached(5.1, 5, 5, 5, 0)); // 略微偏离
        }

        @Test
        @DisplayName("负半径被钳制为 0 → 仅圆心到达")
        void negativeRadius() {
            assertTrue(reached(0, 0, 0, 0, -5));
            assertFalse(reached(1, 0, 0, 0, -5));
        }

        @Test
        @DisplayName("大半径 → 远距离也到达")
        void largeRadius() {
            assertTrue(reached(100, 0, 0, 0, 100));  // 距离 100 = 100
            assertTrue(reached(100, 0, 0, 0, 101));  // 距离 100 < 101
            assertFalse(reached(100, 0, 0, 0, 99));  // 距离 100 > 99
        }

        @Test
        @DisplayName("浮点精度：距离恰好等于半径")
        void floatingPointBoundary() {
            // √(0.5² + 0.5²) ≈ 0.707
            assertTrue(reached(0.5, 0.5, 0, 0, 0.708));
            assertFalse(reached(0.5, 0.5, 0, 0, 0.707));
        }
    }
}
