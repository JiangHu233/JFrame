package io.github.JiangHu.jframe.engine.ecs.entity;

import io.github.JiangHu.jframe.engine.ecs.fixture.TestComponents;
import io.github.JiangHu.jframe.engine.ecs.fixture.TestRegistries;
import io.github.JiangHu.jframe.engine.ecs.registry.EcsTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EcsEntity 门面强化单测 — valid 生命周期防护、onUnmount 期间兄弟查询、
 * find Optional、实例级 mount/unmount。
 */
class EcsEntityAccessTest {

    /** 引擎夹具 */
    private EcsTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new EcsTestHarness();
        harness.componentRegistry.register("counter", TestComponents.CounterComp.class);
        harness.componentRegistry.register("counter-b", TestComponents.CounterBComp.class);
        harness.selectorRegistry.register("always", TestRegistries.AlwaysSelector.class);
        TestComponents.reset();
    }

    @Test
    @DisplayName("valid：destroy 后组件访问抛 ISE，tags 仍可读")
    void validLifecycleGuard() {
        EcsEntity entity = harness.engine.createVirtual();
        assertTrue(entity.valid());
        entity.addTag("chest");

        entity.destroy();

        assertFalse(entity.valid());
        assertThrows(IllegalStateException.class, () -> entity.get(TestComponents.CounterComp.class));
        assertThrows(IllegalStateException.class, () -> entity.has(TestComponents.CounterComp.class));
        assertThrows(IllegalStateException.class, () -> entity.componentTypes());
        assertThrows(IllegalStateException.class, () -> entity.find(TestComponents.CounterComp.class));
        assertThrows(IllegalStateException.class,
                () -> entity.mount(TestComponents.CounterComp.class, Map.of()));
        assertThrows(IllegalStateException.class, () -> entity.unmount("counter"));
        assertTrue(entity.hasTag("chest"), "标签读取在销毁后保留");
        assertTrue(entity.tags().contains("chest"));
    }

    @Test
    @DisplayName("onUnmount 期间兄弟查询仍可用（逆序语义：先卸的为 null、后卸的可读）")
    void siblingAccessDuringUnmount() {
        // counter 先挂、counter-b 后挂 → 卸载逆序：counter-b 先卸、counter 后卸
        // probe-b.onUnmount 时 counter 仍在；probe-a.onUnmount 时 counter-b 已移除
        TestComponents.reset();
        EcsEntity entity = harness.engine.createVirtual();
        harness.engine.mountComponent(entity, ProbeA.class, Map.of());
        harness.engine.mountComponent(entity, ProbeB.class, Map.of());
        TestComponents.reset();

        entity.destroy();

        assertTrue(TestComponents.LOG.contains("probe-b sees counter=true"),
                "counter-b 先卸载，此时 counter 仍可读");
        assertTrue(TestComponents.LOG.contains("probe-a sees counter-b=false"),
                "counter 后卸载，此时 counter-b 已移除");
    }

    /** 探针组件 A（先挂载；onUnmount 观察后挂的 B） */
    private static class ProbeA extends TestComponents.CounterComp {
        @Override
        public void onUnmount() {
            TestComponents.LOG.add("probe-a sees counter-b="
                    + (host().get(ProbeB.class) != null));
        }
    }

    /** 探针组件 B（后挂载；onUnmount 观察先挂的 A） */
    private static class ProbeB extends TestComponents.CounterBComp {
        @Override
        public void onUnmount() {
            TestComponents.LOG.add("probe-b sees counter="
                    + (host().get(ProbeA.class) != null));
        }
    }

    @Test
    @DisplayName("find：Optional 风格取组件")
    void findOptional() {
        EcsEntity entity = harness.engine.createVirtual();
        Optional<TestComponents.CounterComp> empty = entity.find(TestComponents.CounterComp.class);
        assertTrue(empty.isEmpty());

        harness.engine.mountComponent(entity, TestComponents.CounterComp.class, Map.of("value", 5));
        Optional<TestComponents.CounterComp> present = entity.find(TestComponents.CounterComp.class);
        assertTrue(present.isPresent());
        assertEquals(5, present.get().value);
    }

    @Test
    @DisplayName("实例级 mount：参数注入生效并进倒排索引")
    void instanceMount() {
        EcsEntity entity = harness.engine.createVirtual();

        entity.mount(TestComponents.CounterComp.class, Map.of("value", 42, "name", "manual"));

        TestComponents.CounterComp comp = entity.get(TestComponents.CounterComp.class);
        assertNotNull(comp);
        assertEquals(42, comp.value);
        assertEquals("manual", comp.name);
        assertSame(entity, comp.host());
        assertEquals(1, harness.engine.query(TestComponents.CounterComp.class).size(),
                "实例级挂载应进倒排索引（query 可见）");
    }

    @Test
    @DisplayName("实例级 unmount：按注册 id 卸载并回调 onUnmount")
    void instanceUnmount() {
        EcsEntity entity = harness.engine.createVirtual();
        entity.mount(TestComponents.CounterComp.class, Map.of("name", "bye"));
        TestComponents.reset();

        entity.unmount("counter");

        assertTrue(TestComponents.LOG.contains("unmount:counter:bye"), "onUnmount 应被回调");
        assertNull(entity.get(TestComponents.CounterComp.class));
        assertTrue(harness.engine.query(TestComponents.CounterComp.class).isEmpty());
        assertTrue(entity.valid(), "单组件卸载不影响实体有效性");
    }
}
