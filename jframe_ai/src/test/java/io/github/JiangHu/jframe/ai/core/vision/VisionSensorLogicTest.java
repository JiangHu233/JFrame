package io.github.JiangHu.jframe.ai.core.vision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 视野感知（{@link VisionSensor}）纯逻辑单元测试。
 * <p>
 * 聚焦 FOV 视野角度计算的<b>纯数学正确性</b>——通过包级可见的
 * {@link VisionSensor#forwardVector(double)} 与 {@link VisionSensor#angleBetween} 直接验证，
 * 无需依赖 {@link cn.nukkit.level.Level} 或真实 {@link cn.nukkit.entity.Entity}。
 * <p>
 * 涉及视线遮挡（{@code canSee}）的完整三要素判断需在真实服务器环境集成验证，此处不覆盖。
 *
 * @see VisionSensor
 */
@DisplayName("视野感知 FOV 计算")
class VisionSensorLogicTest {

    private static final double EPS = 1e-9;

    // ==================== forwardVector 朝向向量 ====================

    @Nested
    @DisplayName("forwardVector 朝向向量")
    class ForwardVectorTest {

        @Test
        @DisplayName("yaw=0 朝 +Z：forward = (0, 1)")
        void yawZeroFacesPlusZ() {
            double[] f = VisionSensor.forwardVector(0.0);
            assertEquals(0.0, f[0], EPS, "X 分量应为 0");
            assertEquals(1.0, f[1], EPS, "Z 分量应为 1（朝 +Z）");
        }

        @Test
        @DisplayName("yaw=90 朝 -X：forward = (-1, 0)")
        void yaw90FacesMinusX() {
            double[] f = VisionSensor.forwardVector(90.0);
            assertEquals(-1.0, f[0], EPS, "X 分量应为 -1（朝 -X）");
            assertEquals(0.0, f[1], EPS, "Z 分量应为 0");
        }

        @Test
        @DisplayName("yaw=180 朝 -Z：forward = (0, -1)")
        void yaw180FacesMinusZ() {
            double[] f = VisionSensor.forwardVector(180.0);
            assertEquals(0.0, f[0], EPS);
            assertEquals(-1.0, f[1], EPS, "Z 分量应为 -1（朝 -Z）");
        }

        @Test
        @DisplayName("yaw=-90(270) 朝 +X：forward = (1, 0)")
        void yawMinus90FacesPlusX() {
            double[] f = VisionSensor.forwardVector(-90.0);
            assertEquals(1.0, f[0], EPS, "X 分量应为 1（朝 +X）");
            assertEquals(0.0, f[1], EPS);
        }

        @Test
        @DisplayName("yaw=45 朝东南：forward = (-√2/2, √2/2)")
        void yaw45Diagonal() {
            double[] f = VisionSensor.forwardVector(45.0);
            assertEquals(-Math.sqrt(2) / 2, f[0], EPS);
            assertEquals(Math.sqrt(2) / 2, f[1], EPS);
        }

        @Test
        @DisplayName("任意 yaw 的 forward 始终为单位向量")
        void alwaysUnitVector() {
            for (int deg = 0; deg < 360; deg += 15) {
                double[] f = VisionSensor.forwardVector(deg);
                double len = Math.sqrt(f[0] * f[0] + f[1] * f[1]);
                assertEquals(1.0, len, 1e-6, "yaw=" + deg + "° 时 forward 应为单位向量");
            }
        }

        @Test
        @DisplayName("null Entity 返回默认朝向 (0, 1)")
        void nullEntityReturnsDefault() {
            double[] f = VisionSensor.forwardVector((cn.nukkit.entity.Entity) null);
            assertEquals(0.0, f[0], EPS);
            assertEquals(1.0, f[1], EPS);
        }
    }

    // ==================== angleBetween 夹角计算 ====================

    @Nested
    @DisplayName("angleBetween 朝向夹角")
    class AngleBetweenTest {

        @Test
        @DisplayName("目标在正前方：夹角 = 0°")
        void targetDirectlyAhead() {
            // yaw=0 朝 +Z，目标在 (0,0)→(0,10) 正前方
            double angle = VisionSensor.angleBetween(0.0, 0, 0, 0, 10);
            assertEquals(0.0, angle, EPS);
        }

        @Test
        @DisplayName("目标在正后方：夹角 = 180°")
        void targetDirectlyBehind() {
            // yaw=0 朝 +Z，目标在 (0,0)→(0,-10) 正后方
            double angle = VisionSensor.angleBetween(0.0, 0, 0, 0, -10);
            assertEquals(180.0, angle, EPS);
        }

        @Test
        @DisplayName("目标在正侧方：夹角 = 90°")
        void targetAtSide() {
            // yaw=0 朝 +Z，目标在右侧 (10,0) 和左侧 (-10,0) 均为 90°
            assertEquals(90.0, VisionSensor.angleBetween(0.0, 0, 0, 10, 0), EPS);
            assertEquals(90.0, VisionSensor.angleBetween(0.0, 0, 0, -10, 0), EPS);
        }

        @Test
        @DisplayName("目标在 45° 斜方向：夹角 = 45°")
        void targetAt45Degrees() {
            // yaw=0 朝 +Z，目标在 (10,10) 即右前 45°
            double angle = VisionSensor.angleBetween(0.0, 0, 0, 10, 10);
            assertEquals(45.0, angle, 1e-6);
        }

        @Test
        @DisplayName("yaw=90（朝 -X）时目标在正前方：夹角 = 0°")
        void rotatedYawAhead() {
            // yaw=90 朝 -X，目标在 (0,0)→(-10,0) 正前方
            double angle = VisionSensor.angleBetween(90.0, 0, 0, -10, 0);
            assertEquals(0.0, angle, EPS);
        }

        @Test
        @DisplayName("yaw=90（朝 -X）时目标在正后方：夹角 = 180°")
        void rotatedYawBehind() {
            // yaw=90 朝 -X，目标在 (0,0)→(10,0) 正后方
            double angle = VisionSensor.angleBetween(90.0, 0, 0, 10, 0);
            assertEquals(180.0, angle, EPS);
        }

        @Test
        @DisplayName("重叠点（距离≈0）：夹角 = 0°")
        void overlappingPoints() {
            assertEquals(0.0, VisionSensor.angleBetween(0.0, 5, 5, 5, 5), EPS);
        }

        @Test
        @DisplayName("夹角与距离无关（仅取决于方向）")
        void angleIndependentOfDistance() {
            // 同一方向、不同距离，夹角应相同
            double near = VisionSensor.angleBetween(0.0, 0, 0, 0, 1);
            double far = VisionSensor.angleBetween(0.0, 0, 0, 0, 100);
            assertEquals(near, far, EPS);
        }

        @Test
        @DisplayName("偏移起点不影响夹角（平移不变性）")
        void angleInvariantUnderTranslation() {
            // 从 (0,0) 看 (1,1) 与从 (100,200) 看 (101,201) 应有相同夹角
            double a = VisionSensor.angleBetween(0.0, 0, 0, 1, 1);
            double b = VisionSensor.angleBetween(0.0, 100, 200, 101, 201);
            assertEquals(a, b, EPS);
        }
    }

    // ==================== FOV 视野锥判定（基于 angleBetween 推导） ====================

    @Nested
    @DisplayName("FOV 视野锥边界")
    class FovConeBoundary {

        @Test
        @DisplayName("90° FOV：正前方 ±45° 内可见，超出不可见")
        void fov90Boundary() {
            double halfFov = 45.0;
            // 正前方 0° → 可见
            assertTrue(VisionSensor.angleBetween(0, 0, 0, 0, 10) <= halfFov);
            // 40° → 可见
            assertTrue(VisionSensor.angleBetween(0, 0, 0, 8.39, 10) <= halfFov); // atan(8.39/10)≈40°
            // 正侧方 90° → 不可见
            assertFalse(VisionSensor.angleBetween(0, 0, 0, 10, 0) <= halfFov);
        }

        @Test
        @DisplayName("360° FOV：任意方向均可见（全向感知）")
        void fov360AllVisible() {
            // 360° FOV 的 halfFov = 180°，任何角度都 ≤ 180°
            double halfFov = 180.0;
            assertTrue(VisionSensor.angleBetween(0, 0, 0, 0, 10) <= halfFov);    // 前
            assertTrue(VisionSensor.angleBetween(0, 0, 0, 0, -10) <= halfFov);   // 后
            assertTrue(VisionSensor.angleBetween(0, 0, 0, 10, 0) <= halfFov);    // 侧
        }
    }

    // ==================== 坐标版 canSee（"转头可见"）空安全 ====================

    @Nested
    @DisplayName("坐标版 canSee 空安全")
    class CoordinateCanSeeNullSafety {

        @Test
        @DisplayName("null 观察者返回 false（不抛异常）")
        void nullObserverReturnsFalse() {
            assertFalse(VisionSensor.canSee((cn.nukkit.entity.Entity) null,
                    new cn.nukkit.math.Vector3(0, 0, 0)));
        }

        @Test
        @DisplayName("null 观察者 + null 目标返回 false（不抛异常）")
        void nullObserverAndTargetReturnsFalse() {
            assertFalse(VisionSensor.canSee((cn.nukkit.entity.Entity) null, null));
            assertFalse(VisionSensor.canSee((cn.nukkit.entity.Entity) null, null, 16.0));
        }
    }
}
