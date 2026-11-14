package io.github.JiangHu.jframe.engine.ecs.engine;

import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;
import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;
import io.github.JiangHu.jframe.engine.ecs.fixture.TestComponents;
import io.github.JiangHu.jframe.engine.ecs.fixture.TestRegistries;
import io.github.JiangHu.jframe.engine.ecs.gameobject.GameObjectDefinition;
import io.github.JiangHu.jframe.engine.ecs.registry.EcsTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * despawn 回收管线单测 — 销毁通知驱动触发（destroy / 世界侧 removeByBinding 双来源）、
 * 时序（全部 onUnmount 之后）、stop 不触发、异常隔离、spawnBound、虚拟产物传回。
 */
class DespawnTest {

    /** 引擎夹具 */
    private EcsTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new EcsTestHarness();
        harness.componentRegistry.register("counter", TestComponents.CounterComp.class);
        harness.spawnerRegistry.register("null-spawner", TestRegistries.NullSpawner.class);
        harness.spawnerRegistry.register("pojo-spawner", TestRegistries.PojoSpawner.class);
        harness.despawnerRegistry.register("recording-despawner", TestRegistries.RecordingDespawner.class);
        harness.despawnerRegistry.register("throwing-despawner", TestRegistries.ThrowingDespawner.class);
        TestComponents.reset();
    }

    private GameObjectDefinition load(String yaml) {
        List<GameObjectDefinition> defs = harness.loader.load(yaml, "test");
        for (GameObjectDefinition def : defs) {
            harness.engine.loadDefinition(def);
        }
        return defs.get(0);
    }

    private TestRegistries.RecordingDespawner despawnerOf(GameObjectDefinition def) {
        return (TestRegistries.RecordingDespawner) def.despawner();
    }

    @Test
    @DisplayName("触发时序：despawn 在全部组件逆序 onUnmount 之后（虚拟产物传回）")
    void despawnAfterAllUnmounts() {
        harness.componentRegistry.register("counter-b", TestComponents.CounterBComp.class);
        GameObjectDefinition def = load("""
                mgr:
                  spawn: { use: null-spawner }
                  despawn: { use: recording-despawner }
                  components: [ { id: counter }, { id: counter-b } ]
                """);
        TestRegistries.RecordingDespawner despawner = despawnerOf(def);

        EcsEntity entity = harness.engine.spawn("mgr", null);
        TestComponents.reset();
        entity.destroy();

        int unmountCounter = TestComponents.LOG.indexOf("unmount:counter:default");
        int unmountCounterB = TestComponents.LOG.indexOf("unmount:counter-b");
        int despawnAt = TestComponents.LOG.indexOf("despawn:mgr");
        assertTrue(unmountCounterB >= 0 && unmountCounter >= 0 && unmountCounterB < unmountCounter,
                "逆序卸载：counter-b 先卸");
        assertTrue(despawnAt > unmountCounter, "despawn 回调在全部 onUnmount 之后");
        assertNotNull(despawner.last, "destroy 触发回收");
        assertEquals("mgr", despawner.last.definitionName());
        assertNull(despawner.last.product(), "virtual 生成器产物为 null（虚拟产物原样传回）");
    }

    @Test
    @DisplayName("游戏侧销毁通知：removeByBinding(产物) 同样触发，product 传回产物引用")
    void worldSideRemovalTriggers() {
        GameObjectDefinition def = load("""
                ghost:
                  spawn: { use: pojo-spawner }
                  despawn: { use: recording-despawner }
                """);
        TestRegistries.RecordingDespawner despawner = despawnerOf(def);

        EcsEntity entity = harness.engine.spawn("ghost", null);
        Object product = entity.binding();
        assertNotNull(product);

        harness.engine.removeByBinding(product);

        assertNotNull(despawner.last, "世界侧销毁通知（tracker 路径）触发回收");
        assertSame(product, despawner.last.product(), "product = 实体代理引用（spawn 产物）");
        assertNull(harness.engine.getByBinding(product));
    }

    @Test
    @DisplayName("引擎 stop：removeAll 不触发 despawn（产物存续）")
    void stopDoesNotTrigger() {
        GameObjectDefinition def = load("""
                mgr:
                  spawn: { use: null-spawner }
                  despawn: { use: recording-despawner }
                """);
        TestRegistries.RecordingDespawner despawner = despawnerOf(def);

        harness.engine.spawn("mgr", null);
        harness.engine.removeAll();

        assertNull(despawner.last, "stop 卸载组件但不回收产物");
        assertTrue(harness.engine.entities().isEmpty());
    }

    @Test
    @DisplayName("非 spawn 出生（手动 createVirtual / enterBound 收养）不触发")
    void nonSpawnedNeverTriggers() {
        GameObjectDefinition def = load("""
                mgr:
                  selector: { use: tag, params: { tag: x } }
                  despawn: { use: recording-despawner }
                """);
        TestRegistries.RecordingDespawner despawner = despawnerOf(def);
        EcsEntity manual = harness.engine.createVirtual();
        manual.destroy();
        EcsEntity adopted = harness.engine.enterBound(new Object());
        adopted.destroy();

        assertNull(despawner.last, "非 spawn 出生无出生记录，永不触发回收");
        assertTrue(harness.engine.entities().isEmpty(), "手动销毁正常移除");
    }

    @Test
    @DisplayName("定义未配 despawn：destroy 正常移除，无回调")
    void noDespawnConfig() {
        load("""
                mgr:
                  spawn: { use: null-spawner }
                """);
        EcsEntity entity = harness.engine.spawn("mgr", null);
        entity.destroy();
        assertTrue(harness.engine.entities().isEmpty());
    }

    @Test
    @DisplayName("despawn 抛异常隔离：实体仍完整移除，异常不外抛")
    void despawnerExceptionIsolated() {
        load("""
                mgr:
                  spawn: { use: null-spawner }
                  despawn: { use: throwing-despawner }
                """);
        EcsEntity entity = harness.engine.spawn("mgr", null);
        entity.destroy();
        assertFalse(entity.valid(), "异常回收器不影响实体移除");
        assertTrue(harness.engine.entities().isEmpty());
    }

    @Test
    @DisplayName("spawnBound：调用方产物即代理引用，定义组件照挂，destroy 触发回收")
    void spawnBoundFlow() {
        GameObjectDefinition def = load("""
                arena:
                  despawn: { use: recording-despawner }
                  tags: [arena]
                  components: [ { id: counter, params: { value: 6 } } ]
                """);
        TestRegistries.RecordingDespawner despawner = despawnerOf(def);

        Object product = new Object();
        EcsEntity entity = harness.engine.spawnBound("arena", product);

        assertSame(product, entity.binding());
        assertEquals(6, entity.get(TestComponents.CounterComp.class).value);
        assertTrue(entity.hasTag("arena"));

        entity.destroy();

        assertNotNull(despawner.last);
        assertSame(product, despawner.last.product(), "spawnBound 产物经 despawn 传回");
        assertEquals("arena", despawner.last.definitionName());
    }

    @Test
    @DisplayName("spawnBound 校验：未知定义 / null 产物 → EcsConfigException")
    void spawnBoundValidation() {
        load("""
                arena:
                  despawn: { use: recording-despawner }
                """);
        assertThrows(EcsConfigException.class, () -> harness.engine.spawnBound("nope", new Object()));
        assertThrows(EcsConfigException.class, () -> harness.engine.spawnBound("arena", null));
    }
}
