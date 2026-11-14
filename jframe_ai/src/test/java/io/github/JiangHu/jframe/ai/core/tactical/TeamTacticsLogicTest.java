package io.github.JiangHu.jframe.ai.core.tactical;

import cn.nukkit.math.Vector3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 团队战术纯逻辑单元测试。
 * <p>
 * 聚焦 {@link TeamTactics#formationOffsets} 的<b>纯几何</b>正确性——该方法不依赖
 * {@link cn.nukkit.level.Level}，可直接验证。涉及世界方块判定的
 * {@code flankTarget/surroundTarget/rally} 需在真实服务器环境集成验证，此处不覆盖。
 *
 * @see TeamTactics
 * @see FormationType
 */
@DisplayName("团队战术阵型几何")
class TeamTacticsLogicTest {

    private final TeamTactics tactics = new TeamTactics();

    // ==================== LINE 横排 ====================

    @Nested
    @DisplayName("LINE 横排阵型")
    class LineFormation {

        @Test
        @DisplayName("奇数成员沿 X 轴对称分布，Z 恒为 0")
        void oddCountSymmetricOnX() {
            List<Vector3> offsets = tactics.formationOffsets(FormationType.LINE, 3, 2);
            assertEquals(3, offsets.size());
            // x = [-2, 0, 2], z = 0
            assertOffset(offsets.get(0), -2, 0, 0);
            assertOffset(offsets.get(1), 0, 0, 0);
            assertOffset(offsets.get(2), 2, 0, 0);
        }

        @Test
        @DisplayName("偶数成员以中心两侧对称")
        void evenCountSymmetric() {
            List<Vector3> offsets = tactics.formationOffsets(FormationType.LINE, 4, 1);
            assertEquals(4, offsets.size());
            // x = (i - 1.5) * 1 = [-1.5, -0.5, 0.5, 1.5]
            assertOffset(offsets.get(0), -1.5, 0, 0);
            assertOffset(offsets.get(3), 1.5, 0, 0);
        }
    }

    // ==================== COLUMN 纵队 ====================

    @Nested
    @DisplayName("COLUMN 纵队阵型")
    class ColumnFormation {

        @Test
        @DisplayName("沿 Z 轴排列，X 恒为 0")
        void alongZAxis() {
            List<Vector3> offsets = tactics.formationOffsets(FormationType.COLUMN, 3, 2);
            assertEquals(3, offsets.size());
            assertOffset(offsets.get(0), 0, 0, -2);
            assertOffset(offsets.get(1), 0, 0, 0);
            assertOffset(offsets.get(2), 0, 0, 2);
        }
    }

    // ==================== WEDGE 楔形 ====================

    @Nested
    @DisplayName("WEDGE 楔形阵型")
    class WedgeFormation {

        @Test
        @DisplayName("尖端在前（中间 Z=0），两翼向后展开且 |X| 与 Z 同步增大")
        void apexFrontWingsBack() {
            List<Vector3> offsets = tactics.formationOffsets(FormationType.WEDGE, 3, 2);
            assertEquals(3, offsets.size());
            // i=0: fromCenter=-1, x=-2, z=|−1|*2*0.7=1.4
            assertOffset(offsets.get(0), -2, 0, 1.4);
            // i=1: fromCenter=0, x=0, z=0（尖端）
            assertOffset(offsets.get(1), 0, 0, 0);
            // i=2: fromCenter=1, x=2, z=1.4
            assertOffset(offsets.get(2), 2, 0, 1.4);
        }
    }

    // ==================== CIRCLE 圆形 ====================

    @Nested
    @DisplayName("CIRCLE 圆形阵型")
    class CircleFormation {

        @Test
        @DisplayName("四成员均匀分布在半径=spacing 的圆周上（0/90/180/270°）")
        void fourMembersOnCircle() {
            List<Vector3> offsets = tactics.formationOffsets(FormationType.CIRCLE, 4, 2);
            assertEquals(4, offsets.size());
            // radius = spacing = 2
            // i=0: angle=0    → (2, 0)
            // i=1: angle=π/2  → (0, 2)
            // i=2: angle=π    → (-2, 0)
            // i=3: angle=3π/2 → (0, -2)
            assertOffset(offsets.get(0), 2, 0, 0);
            assertOffset(offsets.get(1), 0, 0, 2);
            assertOffset(offsets.get(2), -2, 0, 0);
            assertOffset(offsets.get(3), 0, 0, -2);
        }

        @Test
        @DisplayName("所有成员到原点距离等于 spacing")
        void allOnRadius() {
            double spacing = 3;
            List<Vector3> offsets = tactics.formationOffsets(FormationType.CIRCLE, 6, spacing);
            for (Vector3 o : offsets) {
                assertEquals(spacing, Math.hypot(o.x, o.z), 1e-9, "成员应位于半径圆周上");
            }
        }
    }

    // ==================== SQUARE 方阵 ====================

    @Nested
    @DisplayName("SQUARE 方阵阵型")
    class SquareFormation {

        @Test
        @DisplayName("四成员构成 2×2 网格，以中心对称")
        void twoByTwoGrid() {
            List<Vector3> offsets = tactics.formationOffsets(FormationType.SQUARE, 4, 2);
            assertEquals(4, offsets.size());
            // cols=2, rows=2; x=(col-0.5)*2, z=(row-0.5)*2
            // i=0: row=0,col=0 → (-1,-1)
            // i=1: row=0,col=1 → (1,-1)
            // i=2: row=1,col=0 → (-1,1)
            // i=3: row=1,col=1 → (1,1)
            assertOffset(offsets.get(0), -1, 0, -1);
            assertOffset(offsets.get(1), 1, 0, -1);
            assertOffset(offsets.get(2), -1, 0, 1);
            assertOffset(offsets.get(3), 1, 0, 1);
        }
    }

    // ==================== 边界情况 ====================

    @Nested
    @DisplayName("边界与容错")
    class EdgeCases {

        @Test
        @DisplayName("零成员返回空列表")
        void zeroMembersEmpty() {
            assertTrue(tactics.formationOffsets(FormationType.LINE, 0, 2).isEmpty());
        }

        @Test
        @DisplayName("负成员数视为 0")
        void negativeMembersEmpty() {
            assertTrue(tactics.formationOffsets(FormationType.LINE, -3, 2).isEmpty());
        }

        @Test
        @DisplayName("null 阵型返回空列表")
        void nullFormationEmpty() {
            assertTrue(tactics.formationOffsets(null, 5, 2).isEmpty());
        }

        @Test
        @DisplayName("零间距被钳制为 1（避免所有成员重叠）")
        void zeroSpacingClampedToOne() {
            List<Vector3> offsets = tactics.formationOffsets(FormationType.LINE, 3, 0);
            // spacing 被钳为 1：x = (i - 1) * 1 = [-1, 0, 1]
            assertOffset(offsets.get(0), -1, 0, 0);
            assertOffset(offsets.get(2), 1, 0, 0);
        }

        @Test
        @DisplayName("负间距被钳制为 1")
        void negativeSpacingClampedToOne() {
            List<Vector3> offsets = tactics.formationOffsets(FormationType.LINE, 2, -5);
            // spacing 被钳为 1：x = (i - 0.5) * 1 = [-0.5, 0.5]
            assertOffset(offsets.get(0), -0.5, 0, 0);
            assertOffset(offsets.get(1), 0.5, 0, 0);
        }

        @Test
        @DisplayName("单成员位于中心（偏移 0,0,0）")
        void singleMemberAtCenter() {
            List<Vector3> offsets = tactics.formationOffsets(FormationType.WEDGE, 1, 3);
            assertEquals(1, offsets.size());
            assertOffset(offsets.get(0), 0, 0, 0);
        }
    }

    // ==================== 协同包抄角度分布（flankAngles 纯几何） ====================

    @Nested
    @DisplayName("flankAngles 协同包抄角度分布")
    class FlankAnglesDistribution {

        @Test
        @DisplayName("零成员返回空数组")
        void zeroMembersEmpty() {
            assertEquals(0, TeamTactics.flankAngles(0, 0, Math.PI).length);
        }

        @Test
        @DisplayName("负成员数视为 0")
        void negativeMembersEmpty() {
            assertEquals(0, TeamTactics.flankAngles(-3, 0, Math.PI).length);
        }

        @Test
        @DisplayName("单成员位于弧线中心（目标远侧 / 正后方）")
        void singleMemberAtCenter() {
            // rearAngle=0 → 中心 = 0 + π = π（目标正后方）
            double[] a = TeamTactics.flankAngles(1, 0, Math.PI);
            assertEquals(1, a.length);
            assertEquals(Math.PI, a[0], 1e-9);
        }

        @Test
        @DisplayName("两成员落在两翼（相差 180°），形成钳形")
        void twoMembersOnFlanks() {
            // rearAngle=0, span=π → 中心=π，两端 π±π/2 = π/2 与 3π/2（左右两翼）
            double[] a = TeamTactics.flankAngles(2, 0, Math.PI);
            assertEquals(2, a.length);
            assertEquals(Math.PI / 2, a[0], 1e-9);
            assertEquals(3 * Math.PI / 2, a[1], 1e-9);
            // 两翼相差 180°（钳形展开）
            assertEquals(Math.PI, Math.abs(a[1] - a[0]), 1e-9);
        }

        @Test
        @DisplayName("多成员角度均匀间隔（无聚堆）")
        void evenlySpaced() {
            int n = 5;
            double span = Math.PI;
            double[] a = TeamTactics.flankAngles(n, 0.0, span);
            assertEquals(n, a.length);
            double expectedStep = span / (n - 1);
            for (int i = 1; i < n; i++) {
                assertEquals(expectedStep, a[i] - a[i - 1], 1e-9,
                        "相邻成员角度间隔应恒定（无聚堆）");
            }
        }

        @Test
        @DisplayName("所有成员角度互不相同（不聚堆）")
        void allDistinct() {
            double[] a = TeamTactics.flankAngles(6, 1.2, Math.PI * 1.5);
            assertEquals(6, a.length);
            for (int i = 0; i < a.length; i++) {
                for (int j = i + 1; j < a.length; j++) {
                    assertNotEquals(a[i], a[j], 1e-9, "成员 " + i + " 与 " + j + " 角度不应相同");
                }
            }
        }

        @Test
        @DisplayName("弧线随来袭方向整体旋转（rearAngle 偏移使全体角度平移）")
        void centerRotatesWithRearAngle() {
            double[] a0 = TeamTactics.flankAngles(3, 0, Math.PI);
            double[] a1 = TeamTactics.flankAngles(3, 1.0, Math.PI);
            // rearAngle 增加 1.0，全体成员角度应同步增加 1.0
            assertEquals(1.0, a1[0] - a0[0], 1e-9);
            assertEquals(1.0, a1[1] - a0[1], 1e-9);
            assertEquals(1.0, a1[2] - a0[2], 1e-9);
        }
    }

    // ==================== 辅助断言 ====================

    /**
     * 断言偏移向量三轴精确匹配（y 恒为 0）。
     */
    private static void assertOffset(Vector3 actual, double x, double y, double z) {
        assertEquals(x, actual.x, 1e-9, "X 轴偏移不符");
        assertEquals(y, actual.y, 1e-9, "Y 轴偏移不符（应恒为 0）");
        assertEquals(z, actual.z, 1e-9, "Z 轴偏移不符");
    }
}
