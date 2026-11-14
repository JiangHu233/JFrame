package io.github.JiangHu.jframe.ai.core.combat;

import cn.nukkit.math.Vector3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 战斗弹道纯逻辑单元测试。
 * <p>
 * 验证 {@link CombatActions#computeBallisticMotion} 的物理抛物线补偿：
 * <ul>
 *   <li>等高射击时弹道平直（vy 小），不再因过大的线性补偿"飞过玩家头顶"</li>
 *   <li>箭在到达目标水平距离时高度≈目标高度（命中而非越过）</li>
 *   <li>上下坡场景的垂直分量方向正确</li>
 *   <li>水平速度分量保持初速度大小</li>
 * </ul>
 * 注：{@code computeBallisticMotion} 为包级私有静态方法，本测试位于同包内可直接访问。
 *
 * @see CombatActions
 */
@DisplayName("战斗弹道物理补偿")
class CombatActionsBallisticTest {

    private static final double SPEED = 1.5;
    private static final double G = CombatActions.PROJECTILE_GRAVITY;

    @Nested
    @DisplayName("垂直分量（vy）")
    class VerticalComponent {

        @Test
        @DisplayName("等高近距离：vy 很小（平直弹道）")
        void flatShortRange() {
            // 水平距离 5，等高
            Vector3 m = CombatActions.computeBallisticMotion(0, 64, 0, 5, 64, 0, SPEED);
            // t=5/1.5≈3.33；vy=(0+0.5*0.05*3.33²)/3.33≈0.0833
            assertEquals(0.0833, m.y, 1e-3, "等高近距离 vy 应很小");
        }

        @Test
        @DisplayName("等高远距离：vy 适度（物理补偿，不过量）")
        void flatLongRange() {
            // 水平距离 20，等高
            Vector3 m = CombatActions.computeBallisticMotion(0, 64, 0, 20, 64, 0, SPEED);
            // t=20/1.5≈13.33；vy=(0+0.5*0.05*13.33²)/13.33≈0.333
            assertEquals(0.3333, m.y, 1e-3, "等高远距离 vy 应为物理补偿值");
            // 关键：远低于旧线性补偿 0.03*距离=0.6，证明不再"射过高"
            assertTrue(m.y < 0.6, "新弹道补偿应显著小于旧线性补偿(0.03×距离)，避免飞过头顶");
        }

        @Test
        @DisplayName("目标在上方：vy 增大（向上）")
        void targetAbove() {
            // 水平距离 10，目标高 5 格
            Vector3 m = CombatActions.computeBallisticMotion(0, 64, 0, 10, 69, 0, SPEED);
            // t=10/1.5≈6.67；vy=(5+0.5*0.05*6.67²)/6.67≈0.9167
            assertEquals(0.9167, m.y, 1e-3);
            assertTrue(m.y > 0, "目标在上方时 vy 应为正");
        }

        @Test
        @DisplayName("目标在下方：vy 为负（向下）")
        void targetBelow() {
            // 水平距离 10，目标低 5 格
            Vector3 m = CombatActions.computeBallisticMotion(0, 69, 0, 10, 64, 0, SPEED);
            // vy=(-5+0.5*0.05*6.67²)/6.67≈-0.5833
            assertEquals(-0.5833, m.y, 1e-3);
            assertTrue(m.y < 0, "目标在下方时 vy 应为负");
        }
    }

    @Nested
    @DisplayName("水平分量与方向")
    class HorizontalComponent {

        @Test
        @DisplayName("水平速度分量大小保持初速度")
        void horizontalSpeedPreserved() {
            Vector3 m = CombatActions.computeBallisticMotion(0, 64, 0, 10, 64, 0, SPEED);
            double horizSpeed = Math.sqrt(m.x * m.x + m.z * m.z);
            assertEquals(SPEED, horizSpeed, 1e-9, "水平速度分量应等于初速度");
        }

        @Test
        @DisplayName("方向正确：目标在 +X 时 vx>0，-X 时 vx<0")
        void directionSign() {
            Vector3 pos = CombatActions.computeBallisticMotion(0, 64, 0, 10, 64, 0, SPEED);
            assertTrue(pos.x > 0, "+X 方向 vx 应为正");
            Vector3 neg = CombatActions.computeBallisticMotion(10, 64, 0, 0, 64, 0, SPEED);
            assertTrue(neg.x < 0, "-X 方向 vx 应为负");
        }

        @Test
        @DisplayName("对角方向：vx 与 vz 均非零且同号")
        void diagonal() {
            Vector3 m = CombatActions.computeBallisticMotion(0, 64, 0, 10, 64, 10, SPEED);
            assertTrue(m.x > 0 && m.z > 0, "+X+Z 方向两分量均应为正");
            assertEquals(m.x, m.z, 1e-9, "45°方向 vx 应等于 vz");
        }

        @Test
        @DisplayName("近乎垂直：返回纯垂直速度")
        void nearVertical() {
            Vector3 m = CombatActions.computeBallisticMotion(0, 64, 0, 0, 70, 0, SPEED);
            assertEquals(0, m.x, 1e-9);
            assertEquals(0, m.z, 1e-9);
            assertEquals(SPEED, m.y, 1e-9, "近乎垂直向上时应以初速度向上");
        }
    }

    @Nested
    @DisplayName("弹道命中验证")
    class TrajectoryHit {

        @Test
        @DisplayName("等高弹道：到达目标水平距离时高度≈目标高度（不飞过头顶）")
        void landsAtTargetHeightFlat() {
            double sy = 64;
            Vector3 m = CombatActions.computeBallisticMotion(0, sy, 0, 12, sy, 0, SPEED);
            double[] landed = simulateUntilHorizontal(0, sy, 0, m, 12, 0);
            // 到达目标水平位置时，高度应接近目标高度（容差 1 方块）
            assertEquals(sy, landed[1], 1.0, "箭应命中目标高度而非飞过头顶");
        }

        @Test
        @DisplayName("上坡弹道：到达目标水平距离时高度≈目标高度")
        void landsAtTargetHeightUphill() {
            double sy = 64;
            Vector3 m = CombatActions.computeBallisticMotion(0, sy, 0, 12, sy + 4, 0, SPEED);
            double[] landed = simulateUntilHorizontal(0, sy, 0, m, 12, 0);
            assertEquals(sy + 4, landed[1], 1.0, "上坡时箭应命中抬高的目标高度");
        }

        /**
         * 按 Nukkit 抛射物物理（每 tick：位置 += 速度；vy -= 重力）模拟弹道，
         * 直至水平位置到达目标水平距离附近，返回 [x, y, z]。
         */
        private double[] simulateUntilHorizontal(double sx, double sy, double sz,
                                                Vector3 m, double tx, double tz) {
            double x = sx, y = sy, z = sz, vy = m.y;
            for (int i = 0; i < 5000; i++) {
                x += m.x;
                y += vy;
                z += m.z;
                vy -= G;
                if (Math.abs(x - tx) < 0.5 && Math.abs(z - tz) < 0.5) {
                    break;
                }
            }
            return new double[]{x, y, z};
        }
    }
}
