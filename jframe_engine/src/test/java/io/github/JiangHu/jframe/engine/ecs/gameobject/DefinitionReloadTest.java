package io.github.JiangHu.jframe.engine.ecs.gameobject;

import io.github.JiangHu.jframe.engine.ecs.engine.EcsEngine;
import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;
import io.github.JiangHu.jframe.engine.ecs.fixture.TestComponents;
import io.github.JiangHu.jframe.engine.ecs.fixture.TestRegistries;
import io.github.JiangHu.jframe.engine.ecs.registry.EcsTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定义热重载单测 — {@link EcsEngine#reloadDefinition}：
 * spawn 出生实体的参数刷新 / 组件增删差量重挂 / 新定义经 reload 装载 / selector 实体重评估。
 *
 * @see EcsEngine#reloadDefinition
 */
class DefinitionReloadTest {

    /** 引擎夹具 */
    private EcsTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new EcsTestHarness();
        harness.componentRegistry.register("counter", TestComponents.CounterComp.class);
        harness.componentRegistry.register("eventful", TestComponents.EventfulComp.class);
        harness.selectorRegistry.register("always", TestRegistries.AlwaysSelector.class);
        harness.spawnerRegistry.register("null-spawner", TestRegistries.NullSpawner.class);
        TestComponents.reset();
    }

    private void load(String yaml) {
        harness.loader.load(yaml, "test").forEach(d -> harness.engine.loadDefinition(d));
    }

    private void reload(String yaml) {
        harness.loader.load(yaml, "test-reload").forEach(d -> harness.engine.reloadDefinition(d));
    }

    @Test
    @DisplayName("参数刷新：reload 后 spawn 出生实体上的组件以新参数重挂")
    void reloadRefreshesParamsOnSpawnedEntity() {
        load("""
                catalog-entry:
                  spawn: { use: null-spawner }
                  components:
                    - { id: counter, params: { value: 5, name: old } }
                """);
        EcsEntity entity = harness.engine.spawn("catalog-entry", null);
        assertEquals(5, entity.get(TestComponents.CounterComp.class).value);

        reload("""
                catalog-entry:
                  spawn: { use: null-spawner }
                  components:
                    - { id: counter, params: { value: 9, name: fresh } }
                """);

        TestComponents.CounterComp refreshed = entity.get(TestComponents.CounterComp.class);
        assertNotNull(refreshed, "组件仍应挂载（参数刷新而非移除）");
        assertEquals(9, refreshed.value);
        assertEquals("fresh", refreshed.name);
    }

    @Test
    @DisplayName("组件增删：reload 移除旧 spec 独有组件、补挂新 spec 新增组件")
    void reloadAppliesComponentDelta() {
        load("""
                catalog-entry:
                  spawn: { use: null-spawner }
                  components:
                    - { id: counter }
                    - { id: eventful }
                """);
        EcsEntity entity = harness.engine.spawn("catalog-entry", null);
        assertTrue(entity.has(TestComponents.EventfulComp.class));

        reload("""
                catalog-entry:
                  spawn: { use: null-spawner }
                  components:
                    - { id: counter, params: { value: 3 } }
                """);

        assertFalse(entity.has(TestComponents.EventfulComp.class), "新 spec 移除的组件应已卸载");
        assertEquals(3, entity.get(TestComponents.CounterComp.class).value);
    }

    @Test
    @DisplayName("新定义名：reload 等价 loadDefinition（装载 + 可 spawn）")
    void reloadUnknownNameLoadsDefinition() {
        reload("""
                brand-new:
                  spawn: { use: null-spawner }
                  components:
                    - { id: counter, params: { value: 1 } }
                """);
        EcsEntity entity = harness.engine.spawn("brand-new", null);
        assertEquals(1, entity.get(TestComponents.CounterComp.class).value);
    }

    @Test
    @DisplayName("selector 实体：reload 重评估——仍命中者补挂新 spec 组件")
    void reloadReEvaluatesSelectorEntities() {
        load("""
                auto-tagged:
                  selector: { use: always }
                  components:
                    - { id: counter, params: { value: 7 } }
                """);
        EcsEntity entity = harness.engine.createVirtual();
        assertTrue(entity.has(TestComponents.CounterComp.class), "选择器命中应已挂载");
        assertEquals(7, entity.get(TestComponents.CounterComp.class).value);

        reload("""
                auto-tagged:
                  selector: { use: always }
                  components:
                    - { id: counter, params: { value: 11 } }
                    - { id: eventful }
                """);

        assertEquals(11, entity.get(TestComponents.CounterComp.class).value, "仍命中 → 参数刷新");
        assertTrue(entity.has(TestComponents.EventfulComp.class), "新 spec 新增组件应补挂");
    }

    @Test
    @DisplayName("未受影响实体：非本定义组件不受 reload 干扰")
    void reloadDoesNotTouchUnrelatedComponents() {
        load("""
                catalog-entry:
                  spawn: { use: null-spawner }
                  components:
                    - { id: counter }
                """);
        EcsEntity spawned = harness.engine.spawn("catalog-entry", null);

        EcsEntity bystander = harness.engine.createVirtual();
        bystander.mount(TestComponents.EventfulComp.class, null);

        reload("""
                catalog-entry:
                  spawn: { use: null-spawner }
                  components:
                    - { id: counter, params: { value: 2 } }
                """);

        assertEquals(2, spawned.get(TestComponents.CounterComp.class).value);
        assertTrue(bystander.has(TestComponents.EventfulComp.class), "旁观实体组件不受影响");
        assertNull(bystander.get(TestComponents.CounterComp.class));
    }

    @Test
    @DisplayName("共享组件 id：reload 一条定义不误剥其他定义出生实体的同款组件")
    void reloadDoesNotStripOtherDefinitionsSharingComponentId() {
        load("""
                template-a:
                  spawn: { use: null-spawner }
                  components:
                    - { id: counter, params: { value: 1 } }
                template-b:
                  spawn: { use: null-spawner }
                  components:
                    - { id: counter, params: { value: 2 } }
                """);
        EcsEntity a = harness.engine.spawn("template-a", null);
        EcsEntity b = harness.engine.spawn("template-b", null);

        reload("""
                template-a:
                  spawn: { use: null-spawner }
                  components:
                    - { id: counter, params: { value: 100 } }
                """);

        assertNotNull(a.get(TestComponents.CounterComp.class), "本定义出生实体组件保留");
        assertEquals(100, a.get(TestComponents.CounterComp.class).value, "本定义实体参数刷新");
        assertNotNull(b.get(TestComponents.CounterComp.class), "其他定义实体同款组件不被误剥");
        assertEquals(2, b.get(TestComponents.CounterComp.class).value, "其他定义实体参数不变");
    }

    @Test
    @DisplayName("批量顺序重载（/game reload 场景）：全部定义出生实体保住组件并刷新参数")
    void sequentialFullReloadKeepsAllSpawnedEntities() {
        load("""
                template-a:
                  spawn: { use: null-spawner }
                  components:
                    - { id: counter, params: { value: 1 } }
                template-b:
                  spawn: { use: null-spawner }
                  components:
                    - { id: counter, params: { value: 2 } }
                """);
        EcsEntity a = harness.engine.spawn("template-a", null);
        EcsEntity b = harness.engine.spawn("template-b", null);

        reload("""
                template-a:
                  spawn: { use: null-spawner }
                  components:
                    - { id: counter, params: { value: 101 } }
                template-b:
                  spawn: { use: null-spawner }
                  components:
                    - { id: counter, params: { value: 202 } }
                """);

        assertNotNull(a.get(TestComponents.CounterComp.class), "前序定义实体组件不被后续重载剥除");
        assertEquals(101, a.get(TestComponents.CounterComp.class).value);
        assertNotNull(b.get(TestComponents.CounterComp.class));
        assertEquals(202, b.get(TestComponents.CounterComp.class).value);
    }

    @Test
    @DisplayName("手动挂载（无来源）：定义 reload 不剥除")
    void reloadDoesNotStripManuallyMountedComponents() {
        load("""
                template-a:
                  spawn: { use: null-spawner }
                  components:
                    - { id: counter }
                """);
        EcsEntity bystander = harness.engine.createVirtual();
        bystander.mount(TestComponents.CounterComp.class, Map.of("value", 42));

        reload("""
                template-a:
                  spawn: { use: null-spawner }
                  components:
                    - { id: counter, params: { value: 7 } }
                """);

        assertNotNull(bystander.get(TestComponents.CounterComp.class), "手动挂载组件不被定义重载剥除");
        assertEquals(42, bystander.get(TestComponents.CounterComp.class).value);
    }
}
