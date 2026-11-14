package io.github.JiangHu.jframe.ai.core.targeting;

import cn.nukkit.math.Vector3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 目标插槽纯逻辑单元测试。
 * <p>
 * 覆盖 {@link Target} 接口契约与两个内置实现的无服务器分支:
 * {@link PointTarget} 全量、{@link EntityTarget} 的 null 防御。
 * EntityTarget 绑定真实实体后的位置解析需在真实服务器环境集成验证,此处不覆盖。
 *
 * @see Target
 * @see PointTarget
 * @see EntityTarget
 */
@DisplayName("目标插槽（Target）")
class TargetLogicTest {

    // ==================== Target 接口契约 ====================

    @Nested
    @DisplayName("Target 接口")
    class InterfaceContract {

        @Test
        @DisplayName("lambda 可直接作为 Target（继承 Supplier）")
        void lambdaAsTarget() {
            Target home = () -> new Vector3(100, 64, 100);
            Vector3 pos = home.get();
            assertEquals(100, pos.x, 1e-9);
            assertEquals(64, pos.y, 1e-9);
            assertEquals(100, pos.z, 1e-9);
        }

        @Test
        @DisplayName("Target 可作为 Supplier<Vector3> 使用")
        void usableAsSupplier() {
            Target target = new PointTarget(new Vector3(1, 2, 3));
            Supplier<Vector3> supplier = target;
            assertEquals(new Vector3(1, 2, 3), supplier.get());
        }

        @Test
        @DisplayName("get() 返回 null 表示无有效目标（接口约定）")
        void nullMeansNoTarget() {
            Target none = new PointTarget(null);
            assertNull(none.get());
        }
    }

    // ==================== PointTarget ====================

    @Nested
    @DisplayName("PointTarget 静态点")
    class PointTargetTest {

        @Test
        @DisplayName("解析返回构造点的等值快照")
        void resolvesToConstructionPoint() {
            PointTarget target = new PointTarget(new Vector3(10, 65, -20));
            Vector3 pos = target.get();
            assertEquals(10, pos.x, 1e-9);
            assertEquals(65, pos.y, 1e-9);
            assertEquals(-20, pos.z, 1e-9);
        }

        @Test
        @DisplayName("每次解析返回新快照，不暴露内部引用")
        void returnsSnapshotNotInternalReference() {
            Vector3 original = new Vector3(1, 2, 3);
            PointTarget target = new PointTarget(original);

            Vector3 first = target.get();
            Vector3 second = target.get();

            assertNotSame(original, first, "不应返回构造传入的引用");
            assertNotSame(first, second, "两次解析应各自新建快照");
            assertEquals(first, second, "快照值应相等");
        }

        @Test
        @DisplayName("point() 链式改点，下次解析生效")
        void pointChangesNextResolution() {
            PointTarget target = new PointTarget(new Vector3(0, 64, 0));
            PointTarget returned = target.point(new Vector3(50, 70, 50));

            assertSame(target, returned, "链式 setter 应返回 this");
            assertEquals(new Vector3(50, 70, 50), target.get());
        }

        @Test
        @DisplayName("point(null) 表示目标失效，get() 返回 null")
        void nullPointMeansInvalid() {
            PointTarget target = new PointTarget(new Vector3(0, 64, 0));
            target.point(null);
            assertNull(target.get());
        }

        @Test
        @DisplayName("equals/hashCode 基于内部点")
        void equalsAndHashCode() {
            PointTarget a = new PointTarget(new Vector3(7, 64, 7));
            PointTarget b = new PointTarget(new Vector3(7, 64, 7));
            PointTarget c = new PointTarget(new Vector3(8, 64, 7));

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, c);
            assertNotEquals(a, null);
            assertNotEquals(a, new Object());
        }

        @Test
        @DisplayName("toString 包含点坐标")
        void toStringContainsPoint() {
            PointTarget target = new PointTarget(new Vector3(1, 2, 3));
            assertTrue(target.toString().contains("1"));
            assertTrue(target.toString().contains("PointTarget"));
        }
    }

    // ==================== EntityTarget（无服务器分支） ====================

    @Nested
    @DisplayName("EntityTarget 实体目标")
    class EntityTargetTest {

        @Test
        @DisplayName("未绑定实体时 get() 返回 null（无有效目标）")
        void nullEntityMeansInvalid() {
            assertNull(new EntityTarget(null).get());
        }

        @Test
        @DisplayName("entity() 链式返回 this")
        void entitySetterChained() {
            EntityTarget target = new EntityTarget(null);
            assertSame(target, target.entity(null));
        }

        @Test
        @DisplayName("两个未绑定实体的 EntityTarget 相等")
        void bothNullAreEqual() {
            assertEquals(new EntityTarget(null), new EntityTarget(null));
        }

        @Test
        @DisplayName("与 PointTarget 及其他类型不相等")
        void notEqualToOtherTypes() {
            assertNotEquals(new EntityTarget(null), new PointTarget(null));
            assertNotEquals(new EntityTarget(null), new Object());
        }

        @Test
        @DisplayName("toString 不抛异常")
        void toStringSafe() {
            assertDoesNotThrow(() -> new EntityTarget(null).toString());
        }
    }
}
