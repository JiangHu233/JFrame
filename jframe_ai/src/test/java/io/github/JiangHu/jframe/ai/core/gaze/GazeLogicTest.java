package io.github.JiangHu.jframe.ai.core.gaze;

import cn.nukkit.math.Vector3;
import io.github.JiangHu.jframe.ai.core.targeting.PointTarget;
import io.github.JiangHu.jframe.ai.core.targeting.Target;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 视角修正器纯逻辑单元测试。
 * <p>
 * 覆盖不依赖运行中服务器的部分:Nukkit 角度约定纯函数
 * ({@code yawTowards/pitchTowards/angleDiff})、{@code SmoothGaze.approachAngle}
 * 限速逼近、各修正器构造与配置防御。
 * 涉及真实实体的 {@code apply} 写字段流转需在真实服务器环境集成验证,此处不覆盖。
 *
 * @see GazeContext
 * @see SmoothGaze
 */
@DisplayName("视角修正器")
class GazeLogicTest {

    // ==================== yawTowards: Nukkit 角度约定 ====================

    @Nested
    @DisplayName("yawTowards(Nukkit 约定:0 朝 +Z,顺时针为正)")
    class YawTowards {

        @Test
        @DisplayName("(0,1) 朝 +Z → 0°")
        void plusZIsZero() {
            assertEquals(0, GazeContext.yawTowards(0, 1), 1.0e-9);
        }

        @Test
        @DisplayName("(1,0) 朝 +X → -90°(与 getDirectionVector x=-sin(yaw) 自洽)")
        void plusXIsMinusNinety() {
            assertEquals(-90, GazeContext.yawTowards(1, 0), 1.0e-9);
        }

        @Test
        @DisplayName("(-1,0) 朝 -X → +90°")
        void minusXIsPlusNinety() {
            assertEquals(90, GazeContext.yawTowards(-1, 0), 1.0e-9);
        }

        @Test
        @DisplayName("(0,-1) 朝 -Z → 180°")
        void minusZIsPlusHundredEighty() {
            assertEquals(180, Math.abs(GazeContext.yawTowards(0, -1)), 1.0e-9);
        }

        @Test
        @DisplayName("(1,1) 朝 +X+Z 对角 → -45°")
        void diagonalIsMinusFortyFive() {
            assertEquals(-45, GazeContext.yawTowards(1, 1), 1.0e-9);
        }
    }

    // ==================== pitchTowards: 上仰为负 ====================

    @Nested
    @DisplayName("pitchTowards(上仰为负)")
    class PitchTowards {

        @Test
        @DisplayName("水平方向 → 0°")
        void horizontalIsZero() {
            assertEquals(0, GazeContext.pitchTowards(3, 0, 4), 1.0e-9);
        }

        @Test
        @DisplayName("45° 上仰 → -45°")
        void upIsNegative() {
            assertEquals(-45, GazeContext.pitchTowards(0, 1, 1), 1.0e-9);
        }

        @Test
        @DisplayName("45° 下俯 → +45°")
        void downIsPositive() {
            assertEquals(45, GazeContext.pitchTowards(0, -1, 1), 1.0e-9);
        }

        @Test
        @DisplayName("正上方 → -90°")
        void straightUpIsMinusNinety() {
            assertEquals(-90, GazeContext.pitchTowards(0, 1, 0), 1.0e-9);
        }
    }

    // ==================== angleDiff: 最短路径有向差 ====================

    @Nested
    @DisplayName("angleDiff(归一至 [-180,180))")
    class AngleDiff {

        @Test
        @DisplayName("同角 → 0")
        void sameAngleIsZero() {
            assertEquals(0, GazeContext.angleDiff(90, 90), 1.0e-9);
        }

        @Test
        @DisplayName("(350,10) 跨 0 → +20(顺时针最短)")
        void acrossZeroClockwise() {
            assertEquals(20, GazeContext.angleDiff(350, 10), 1.0e-9);
        }

        @Test
        @DisplayName("(10,350) 跨 0 → -20(逆时针最短)")
        void acrossZeroCounterClockwise() {
            assertEquals(-20, GazeContext.angleDiff(10, 350), 1.0e-9);
        }

        @Test
        @DisplayName("(170,-170) 跨 180 → +20")
        void acrossHundredEighty() {
            assertEquals(20, GazeContext.angleDiff(170, -170), 1.0e-9);
        }

        @Test
        @DisplayName("(-170,170) 跨 180 → -20")
        void acrossHundredEightyReverse() {
            assertEquals(-20, GazeContext.angleDiff(-170, 170), 1.0e-9);
        }

        @Test
        @DisplayName("大角度输入归一:(720+30, 0) → -30")
        void largeInputNormalized() {
            assertEquals(-30, GazeContext.angleDiff(750, 0), 1.0e-9);
        }
    }

    // ==================== approachAngle: 限速逼近 ====================

    @Nested
    @DisplayName("SmoothGaze.approachAngle(限速逼近)")
    class ApproachAngle {

        @Test
        @DisplayName("顺时针逼近:差 90° 限步 10° → 前进 10°")
        void clockwiseStep() {
            assertEquals(10, SmoothGaze.approachAngle(0, 90, 10), 1.0e-9);
        }

        @Test
        @DisplayName("逆时针逼近:差 -90° 限步 10° → 后退 10°")
        void counterClockwiseStep() {
            assertEquals(-10, SmoothGaze.approachAngle(0, -90, 10), 1.0e-9);
        }

        @Test
        @DisplayName("差值小于限步 → 直接贴合目标")
        void snapWhenClose() {
            assertEquals(8, SmoothGaze.approachAngle(5, 8, 10), 1.0e-9);
        }

        @Test
        @DisplayName("跨 180 边界走最短路径:(175 → -175) 限步 10° → 185 归一为 -175")
        void acrossBoundaryShortestPath() {
            assertEquals(-175, SmoothGaze.approachAngle(175, -175, 10), 1.0e-9);
        }

        @Test
        @DisplayName("跨 0 边界走最短路径:(350 → 10) 限步 5° → 355 归一为 -5(同角度)")
        void acrossZeroShortestPath() {
            assertEquals(-5, SmoothGaze.approachAngle(350, 10, 5), 1.0e-9);
        }

        @Test
        @DisplayName("结果归一至 [-180,180):避免长时间运行角度漂移")
        void resultNormalized() {
            double next = SmoothGaze.approachAngle(179, -179, 5);
            assertTrue(next >= -180 && next < 180, "结果应在 [-180,180): " + next);
        }
    }

    // ==================== SmoothGaze 配置 ====================

    @Nested
    @DisplayName("SmoothGaze 配置防御")
    class SmoothGazeConfig {

        @Test
        @DisplayName("包装 null 修正器抛 NullPointerException")
        void nullDelegateRejected() {
            assertThrows(NullPointerException.class, () -> new SmoothGaze(null));
        }

        @Test
        @DisplayName("maxHeadTurn 非正抛 IllegalArgumentException")
        void nonPositiveHeadTurnRejected() {
            SmoothGaze gaze = new SmoothGaze(ctx -> {
            });
            assertThrows(IllegalArgumentException.class, () -> gaze.maxHeadTurn(0));
            assertThrows(IllegalArgumentException.class, () -> gaze.maxHeadTurn(-1));
        }

        @Test
        @DisplayName("maxBodyTurn 非正抛 IllegalArgumentException")
        void nonPositiveBodyTurnRejected() {
            SmoothGaze gaze = new SmoothGaze(ctx -> {
            });
            assertThrows(IllegalArgumentException.class, () -> gaze.maxBodyTurn(0));
        }

        @Test
        @DisplayName("reactionDelay 负数抛 IllegalArgumentException")
        void negativeDelayRejected() {
            SmoothGaze gaze = new SmoothGaze(ctx -> {
            });
            assertThrows(IllegalArgumentException.class, () -> gaze.reactionDelay(-1));
        }

        @Test
        @DisplayName("maxTurnSpeed 同时设置头身并支持链式调用")
        void maxTurnSpeedChains() {
            SmoothGaze gaze = new SmoothGaze(ctx -> {
            });
            assertSame(gaze, gaze.maxTurnSpeed(7), "链式方法应返回 this");
        }
    }

    // ==================== TargetGaze 配置 ====================

    @Nested
    @DisplayName("TargetGaze 配置防御")
    class TargetGazeConfig {

        @Test
        @DisplayName("null 目标引用抛 NullPointerException")
        void nullTargetRejected() {
            assertThrows(NullPointerException.class, () -> new TargetGaze((Target) null));
        }

        @Test
        @DisplayName("aimHeight 负数抛 IllegalArgumentException")
        void negativeAimHeightRejected() {
            TargetGaze gaze = new TargetGaze(new PointTarget(new Vector3(0, 0, 0)));
            assertThrows(IllegalArgumentException.class, () -> gaze.aimHeight(-0.5));
        }

        @Test
        @DisplayName("bodyFollow 链式返回 this")
        void bodyFollowChains() {
            TargetGaze gaze = new TargetGaze(new PointTarget(new Vector3(0, 0, 0)));
            assertSame(gaze, gaze.bodyFollow());
        }
    }

    // ==================== Gaze 函数式接口 ====================

    @Nested
    @DisplayName("Gaze 函数式接口")
    class FunctionalInterfaceCheck {

        @Test
        @DisplayName("可用 lambda 实现(自定义修正器零门槛)")
        void lambdaUsable() {
            Gaze custom = ctx -> ctx.entity().yaw = 42;
            assertNotNull(custom);
        }
    }
}
