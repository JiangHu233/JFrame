package io.github.JiangHu.jframe.engine.ecs.entity;

import io.github.JiangHu.jframe.engine.ecs.registry.EcsTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一代理绑定单测 — Object 万能引用、无参自动泛型识别强转 getter、虚拟形态、
 * 任意对象（Level/Server/POJO 同理）代理与幂等 enterBound。
 */
class UnifiedBindingTest {

    /** 引擎夹具 */
    private EcsTestHarness harness;

    /** 伪产物类型（任意对象代理验证） */
    static class ArenaRef {
        final String name;

        ArenaRef(String name) {
            this.name = name;
        }
    }

    @BeforeEach
    void setUp() {
        harness = new EcsTestHarness();
    }

    @Test
    @DisplayName("任意对象绑定：无参泛型 getter 按赋值目标强转，同引用返回")
    void arbitraryObjectBinding() {
        ArenaRef arena = new ArenaRef("wall");
        EcsEntity entity = harness.engine.enterBound(arena);

        ArenaRef proxied = entity.binding();
        assertSame(arena, proxied, "泛型强转取回同一引用");
        assertEquals("wall", proxied.name);
        assertFalse(entity.isVirtual());

        Object raw = entity.binding();
        assertSame(arena, raw, "Object 目标同样取回原引用");
    }

    @Test
    @DisplayName("类型不符 → ClassCastException（强转语义，调用点即失败点）")
    void mismatchedTypeThrows() {
        EcsEntity entity = harness.engine.enterBound(new ArenaRef("x"));
        assertThrows(ClassCastException.class, () -> asString(entity));
    }

    @Test
    @DisplayName("纯虚拟：binding() 返回 null 不抛，isVirtual() 为 true")
    void virtualBindingIsNull() {
        EcsEntity entity = harness.engine.createVirtual();
        ArenaRef ref = entity.binding();
        assertNull(ref, "虚拟实体泛型 getter 返回 null（不抛 CCE）");
        assertTrue(entity.isVirtual());
    }

    @Test
    @DisplayName("虚拟判型：instanceof 模式匹配对虚拟与其他类型均安全")
    void instanceOfPatternMatching() {
        EcsEntity virtual = harness.engine.createVirtual();
        assertFalse(virtual.binding() instanceof ArenaRef);

        EcsEntity bound = harness.engine.enterBound("a-string-product");
        assertFalse(bound.binding() instanceof ArenaRef);
        assertTrue(bound.binding() instanceof String s && s.equals("a-string-product"));
    }

    @Test
    @DisplayName("enterBound 幂等：同引用重复进入返回既有实体；getByBinding 对任意对象生效")
    void enterBoundIdempotent() {
        Object levelLike = new Object();
        EcsEntity first = harness.engine.enterBound(levelLike);
        EcsEntity second = harness.engine.enterBound(levelLike);
        assertSame(first, second, "同引用幂等");
        assertSame(first, harness.engine.getByBinding(levelLike));
        assertNull(harness.engine.getByBinding(new Object()));
    }

    @Test
    @DisplayName("destroy 后：byBinding 出表、binding() 仍可读（despawn/事后日志场景）")
    void bindingReadableAfterDestroy() {
        ArenaRef arena = new ArenaRef("y");
        EcsEntity entity = harness.engine.enterBound(arena);
        entity.destroy();

        assertFalse(entity.valid());
        assertNull(harness.engine.getByBinding(arena), "销毁后索引出表");
        assertSame(arena, entity.binding(), "binding() 不做 valid 守卫，销毁后仍可读");
    }

    /** 以 String 为目标触发泛型强转（赋值目标推断需要方法上下文） */
    private static String asString(EcsEntity entity) {
        return entity.binding();
    }
}
