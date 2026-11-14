package io.github.JiangHu.jframe.engine.ecs.selector;

import io.github.JiangHu.jframe.engine.ecs.engine.EcsEngine;
import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;
import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;
import io.github.JiangHu.jframe.engine.ecs.fixture.TestComponents;
import io.github.JiangHu.jframe.engine.ecs.gameobject.GameObjectDefinition;
import io.github.JiangHu.jframe.engine.ecs.registry.EcsTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置选择器/生成器与保留名单测。
 */
class BuiltinSelectorTest {

    /** 引擎夹具 */
    private EcsTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new EcsTestHarness();
    }

    @Test
    @DisplayName("内置 tag 选择器：按标签匹配")
    void tagSelector() {
        EntitySelector selector = harness.selectorRegistry.create("tag",
                java.util.Map.of("tag", "chest"));
        EcsEntity entity = harness.engine.createVirtual();
        assertFalse(selector.matches(entity));
        entity.addTag("chest");
        assertTrue(selector.matches(entity));
    }

    @Test
    @DisplayName("内置 entity-class 选择器：虚拟实体（无绑定）懒求值不命中、不报错")
    void entityClassSelector() {
        EntitySelector selector = harness.selectorRegistry.create("entity-class",
                java.util.Map.of("type", "FakeNukkitEntityForResolution"));
        EcsEntity virtualEntity = harness.engine.createVirtual();
        assertFalse(selector.matches(virtualEntity), "虚拟实体无 Nukkit 绑定不命中");

        // 类型名即使非法，虚拟实体在懒求值路径下也不触发解析（有绑定实体才 resolve）
        EntitySelector badType = harness.selectorRegistry.create("entity-class",
                java.util.Map.of("type", "NoSuchNukkitType"));
        assertFalse(badType.matches(harness.engine.createVirtual()));
        // 解析失败路径由 ParamBinderTest.resolveClassNames 直接覆盖
    }

    @Test
    @DisplayName("内置 blockentity-class 选择器：虚拟实体不命中（类解析路径同上）")
    void blockEntityClassSelector() {
        EntitySelector selector = harness.selectorRegistry.create("blockentity-class",
                java.util.Map.of("type", "BlockEntityChest"));
        assertFalse(selector.matches(harness.engine.createVirtual()));
    }

    @Test
    @DisplayName("保留名：内置选择器/生成器名自定义同名注册被拒绝")
    void reservedNamesRejected() {
        assertThrows(EcsConfigException.class,
                () -> harness.selectorRegistry.register("tag", io.github.JiangHu.jframe.engine.ecs.fixture.TestRegistries.AlwaysSelector.class));
        assertThrows(EcsConfigException.class,
                () -> harness.selectorRegistry.register("entity-class", io.github.JiangHu.jframe.engine.ecs.fixture.TestRegistries.AlwaysSelector.class));
        assertThrows(EcsConfigException.class,
                () -> harness.selectorRegistry.register("blockentity-class", io.github.JiangHu.jframe.engine.ecs.fixture.TestRegistries.AlwaysSelector.class));
        assertThrows(EcsConfigException.class,
                () -> harness.spawnerRegistry.register("virtual", io.github.JiangHu.jframe.engine.ecs.fixture.TestRegistries.NullSpawner.class));
        assertThrows(EcsConfigException.class,
                () -> harness.spawnerRegistry.register("entity", io.github.JiangHu.jframe.engine.ecs.fixture.TestRegistries.NullSpawner.class));
        assertThrows(EcsConfigException.class,
                () -> harness.spawnerRegistry.register("block", io.github.JiangHu.jframe.engine.ecs.fixture.TestRegistries.NullSpawner.class));
    }

    @Test
    @DisplayName("内置 virtual 生成器：spawn 返回纯虚拟实体并挂本定义组件")
    void virtualSpawner() {
        harness.componentRegistry.register("counter", TestComponents.CounterComp.class);
        GameObjectDefinition def = harness.loader.load("""
                mgr:
                  spawn: { use: virtual }
                  components: [ { id: counter, params: { value: 3 } } ]
                """, "test").get(0);
        harness.engine.loadDefinition(def);
        EcsEntity entity = harness.engine.spawn("mgr", null);
        assertNotNull(entity);
        assertTrue(entity.isVirtual(), "virtual 生成器产物为 null → 纯虚拟实体");
        assertEquals(3, entity.get(TestComponents.CounterComp.class).value);
    }

    @Test
    @DisplayName("内置 entity/block 生成器：缺必要参数或位置时报错")
    void builtinSpawnerParamErrors() {
        GameObjectDefinition entityDef = harness.loader.load("""
                e:
                  spawn: { use: entity }
                """, "test").get(0);
        harness.engine.loadDefinition(entityDef);
        assertThrows(EcsConfigException.class, () -> harness.engine.spawn("e", null));

        GameObjectDefinition blockDef = harness.loader.load("""
                b:
                  spawn: { use: block, params: { blockId: 0 } }
                """, "test").get(0);
        harness.engine.loadDefinition(blockDef);
        assertThrows(EcsConfigException.class, () -> harness.engine.spawn("b", null));
    }
}
