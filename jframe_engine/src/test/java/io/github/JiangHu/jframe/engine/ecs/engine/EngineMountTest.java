package io.github.JiangHu.jframe.engine.ecs.engine;

import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;
import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;
import io.github.JiangHu.jframe.engine.ecs.fixture.TestComponents;
import io.github.JiangHu.jframe.engine.ecs.fixture.TestRegistries;
import io.github.JiangHu.jframe.engine.ecs.gameobject.GameObjectDefinition;
import io.github.JiangHu.jframe.engine.ecs.registry.EcsTestHarness;
import io.github.JiangHu.jframe.engine.ecs.system.EcsSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EcsEngine 挂载/卸载管线单测 — 存量回溯、后放识别、refresh、去重、回滚、query、spawn、tick。
 */
class EngineMountTest {

    /** 引擎夹具 */
    private EcsTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new EcsTestHarness();
        harness.componentRegistry.register("counter", TestComponents.CounterComp.class);
        harness.componentRegistry.register("counter-b", TestComponents.CounterBComp.class);
        harness.componentRegistry.register("throwing", TestComponents.ThrowingComp.class);
        harness.selectorRegistry.register("always", TestRegistries.AlwaysSelector.class);
        harness.selectorRegistry.register("never", TestRegistries.NeverSelector.class);
        harness.spawnerRegistry.register("null-spawner", TestRegistries.NullSpawner.class);
        TestComponents.reset();
    }

    private GameObjectDefinition load(String yaml) {
        List<GameObjectDefinition> defs = harness.loader.load(yaml, "test");
        for (GameObjectDefinition def : defs) {
            harness.engine.loadDefinition(def);
        }
        return defs.isEmpty() ? null : defs.get(0);
    }

    @Test
    @DisplayName("存量回溯：实体先存在，定义后加载也能识别挂载")
    void retroMountExistingEntity() {
        EcsEntity entity = harness.engine.createVirtual();
        load("""                
                magic:
                  selector: { use: always }
                  tags: [chest]
                  components:
                    - id: counter
                      params: { value: 5, name: "magic" }
                """);
        TestComponents.CounterComp comp = entity.get(TestComponents.CounterComp.class);
        assertNotNull(comp, "存量实体应被回溯挂载");
        assertEquals(5, comp.value);
        assertEquals("magic", comp.name);
        assertTrue(entity.hasTag("chest"));
        assertSame(entity, comp.host(), "组件应能识别宿主");
    }

    @Test
    @DisplayName("后放识别：定义先加载，实体后创建也能挂载")
    void laterEntityMounted() {
        load("""
                magic:
                  selector: { use: always }
                  components: [ { id: counter, params: { value: 3 } } ]
                """);
        EcsEntity entity = harness.engine.createVirtual();
        assertNotNull(entity.get(TestComponents.CounterComp.class));
    }

    @Test
    @DisplayName("selector 不命中则不挂载")
    void selectorNotMatched() {
        load("""
                magic:
                  selector: { use: never }
                  components: [ { id: counter } ]
                """);
        EcsEntity entity = harness.engine.createVirtual();
        assertNull(entity.get(TestComponents.CounterComp.class));
    }

    @Test
    @DisplayName("refresh 补挂：tag 后加后手动重评命中")
    void refreshSupplements() {
        load("""
                tagged:
                  selector: { use: tag, params: { tag: chest } }
                  components:
                    - id: counter
                """);
        EcsEntity entity = harness.engine.createVirtual();
        assertNull(entity.get(TestComponents.CounterComp.class), "无标签不挂载");
        entity.addTag("chest");
        harness.engine.refresh(entity);
        assertNotNull(entity.get(TestComponents.CounterComp.class), "refresh 后应补挂");
    }

    @Test
    @DisplayName("定义并集去重：同组件 id 多条定义命中只挂首个（按加载顺序）")
    void firstDefinitionWins() {
        load("""
                first:
                  selector: { use: always }
                  components: [ { id: counter, params: { value: 1, name: "first" } } ]
                second:
                  selector: { use: always }
                  components: [ { id: counter, params: { value: 2, name: "second" } } ]
                """);
        EcsEntity entity = harness.engine.createVirtual();
        TestComponents.CounterComp comp = entity.get(TestComponents.CounterComp.class);
        assertEquals(1, comp.value, "首个定义生效");
        assertEquals("first", comp.name);
    }

    @Test
    @DisplayName("不同组件并集：多条定义命中各自挂载")
    void unionOfDifferentComponents() {
        load("""
                a:
                  selector: { use: always }
                  components: [ { id: counter } ]
                b:
                  selector: { use: always }
                  components: [ { id: counter-b } ]
                """);
        EcsEntity entity = harness.engine.createVirtual();
        assertNotNull(entity.get(TestComponents.CounterComp.class));
        assertNotNull(entity.get(TestComponents.CounterBComp.class));
    }

    @Test
    @DisplayName("onMount 异常回滚：该组件不留半挂载状态，不影响其他组件")
    void onMountRollback() {
        load("""
                risky:
                  selector: { use: always }
                  components:
                    - id: throwing
                    - id: counter
                """);
        EcsEntity entity = harness.engine.createVirtual();
        assertNull(entity.get(TestComponents.ThrowingComp.class), "异常组件应回滚");
        assertTrue(harness.engine.query(TestComponents.ThrowingComp.class).isEmpty());
        assertNotNull(entity.get(TestComponents.CounterComp.class), "其余组件不受影响");
        assertTrue(TestComponents.LOG.contains("mount:throwing"), "onMount 应被调用过");
        assertFalse(TestComponents.LOG.contains("unmount:throwing"), "回滚不触发 onUnmount");
    }

    @Test
    @DisplayName("卸载逆序：destroy 时后挂组件先卸载")
    void unmountReverseOrder() {
        load("""
                ordered:
                  selector: { use: always }
                  components: [ { id: counter }, { id: counter-b } ]
                """);
        EcsEntity entity = harness.engine.createVirtual();
        int mountCounterB = TestComponents.LOG.indexOf("mount:counter-b");
        TestComponents.reset();
        entity.destroy();
        int unmountCounter = TestComponents.LOG.indexOf("unmount:counter:default");
        int unmountCounterB = TestComponents.LOG.indexOf("unmount:counter-b");
        assertTrue(unmountCounterB < unmountCounter, "counter-b 后挂应先卸载");
        assertTrue(mountCounterB >= 0 && unmountCounterB >= 0);
        assertTrue(harness.engine.entities().isEmpty());
    }

    @Test
    @DisplayName("query 交集：只返回同时挂有全部指定组件的实体")
    void queryIntersection() {
        load("""
                combo:
                  selector: { use: tag, params: { tag: combo } }
                  components: [ { id: counter }, { id: counter-b } ]
                solo:
                  selector: { use: tag, params: { tag: solo } }
                  components: [ { id: counter } ]
                """);
        EcsEntity combo = harness.engine.createVirtual();
        combo.addTag("combo");
        harness.engine.refresh(combo);
        EcsEntity solo = harness.engine.createVirtual();
        solo.addTag("solo");
        harness.engine.refresh(solo);

        List<EcsEntity> bothResult = harness.engine.query(TestComponents.CounterComp.class,
                TestComponents.CounterBComp.class);
        List<EcsEntity> aResult = harness.engine.query(TestComponents.CounterComp.class);
        assertEquals(2, aResult.size(), "两个实体都挂 counter");
        assertEquals(1, bothResult.size(), "只有一个实体同时挂 counter+counter-b");
        assertSame(combo, bothResult.get(0));
    }

    @Test
    @DisplayName("spawn：virtual 生成器 + 本定义组件（无需 selector 命中）")
    void spawnVirtualWithOwnComponents() {
        load("""
                manager:
                  spawn: { use: virtual }
                  tags: [manager]
                  components: [ { id: counter, params: { value: 9 } } ]
                """);
        EcsEntity entity = harness.engine.spawn("manager", null);
        assertNotNull(entity);
        assertTrue(entity.isVirtual(), "virtual 生成器产物为 null → 纯虚拟实体");
        assertEquals(9, entity.get(TestComponents.CounterComp.class).value);
        assertTrue(entity.hasTag("manager"));
    }

    @Test
    @DisplayName("spawn：定义不存在 / 无 spawn 配置 → 报错")
    void spawnErrors() {
        assertThrows(EcsConfigException.class, () -> harness.engine.spawn("nope", null));
        load("""
                bindonly:
                  selector: { use: always }
                  components: [ { id: counter } ]
                """);
        assertThrows(EcsConfigException.class, () -> harness.engine.spawn("bindonly", null));
    }

    @Test
    @DisplayName("定义名冲突 → 报错（不合并覆盖）")
    void definitionConflict() {
        load("""
                dup:
                  selector: { use: always }
                  components: [ { id: counter } ]
                """);
        assertThrows(EcsConfigException.class, () -> load("""
                dup:
                  selector: { use: always }
                  components: [ { id: counter } ]
                """));
    }

    @Test
    @DisplayName("系统 tick：interval 对齐 + priority 排序 + 异常隔离")
    void systemTick() {
        StringBuilder order = new StringBuilder();
        EcsSystem slow = new RecordingSystem("S", 2, 100, order, false);
        EcsSystem fastFirst = new RecordingSystem("F", 1, -10, order, false);
        EcsSystem boom = new RecordingSystem("B", 1, 0, order, true);
        harness.engine.addSystem(slow);
        harness.engine.addSystem(boom);
        harness.engine.addSystem(fastFirst);
        for (int i = 0; i < 4; i++) {
            harness.engine.tick();
        }
        String log = order.toString();
        assertEquals(2, count(log, "S"), "interval=2 在 4 tick 内执行 2 次");
        assertEquals(4, count(log, "F"), "异常系统不中断其余系统");
        assertTrue(log.indexOf("F") <= log.indexOf("S"), "order 小者先执行");
        assertEquals(4, harness.engine.tickCount());
    }

    @Test
    @DisplayName("tick 按需化：未 override tick 的系统零轮询（非空调用）")
    void noTickSystemNeverTicked() {
        class PureEventSystem implements EcsSystem {
            int forbidden;

            @Override
            public int interval() {
                return 1;
            }

            @Override
            public int order() {
                return -100;
            }
        }
        PureEventSystem pure = new PureEventSystem();
        // 间接证明：若引擎调用 tick 只能走接口 default 空方法，无法观测——
        // 用 overridesTick 判定 + tickingSystems 行为（下方）组合验证
        assertFalse(EcsEngine.overridesTick(pure.getClass()), "未 override 的系统应判定为非 ticking");

        harness.engine.addSystem(pure);
        // 同帧注册一个真 ticking 系统作对照
        StringBuilder order = new StringBuilder();
        harness.engine.addSystem(new RecordingSystem("R", 1, 0, order, false));
        for (int i = 0; i < 3; i++) {
            harness.engine.tick();
        }
        assertEquals(3, count(order.toString(), "R"), "ticking 系统照常执行");
        // pure.forbidden 无法被计数（default 空方法不可观测）——判定测试已覆盖核心语义
    }

    @Test
    @DisplayName("overridesTick 判定三态：仅 default→false / override→true / 基类声明→true")
    void overridesTickDetection() {
        // ① 仅接口 default（含匿名类形态）
        class Plain implements EcsSystem {
        }
        assertFalse(EcsEngine.overridesTick(Plain.class));

        // ② override tick
        class Ticking implements EcsSystem {
            @Override
            public void tick(EcsEngine engine) {
            }
        }
        assertTrue(EcsEngine.overridesTick(Ticking.class));

        // ③ 抽象基类声明 tick、子类不 override
        abstract class Base implements EcsSystem {
            @Override
            public void tick(EcsEngine engine) {
            }
        }
        class Child extends Base {
        }
        class ChildRedeclare extends Base {
            @Override
            public void tick(EcsEngine engine) {
                super.tick(engine);
            }
        }
        assertTrue(EcsEngine.overridesTick(Child.class));
        assertTrue(EcsEngine.overridesTick(ChildRedeclare.class));
    }

    @Test
    @DisplayName("生成语义：调用方循环多次单生成 → N 个独立实体各挂组件")
    void loopSpawnMultipleEntities() {
        load("""
                mgr:
                  spawn: { use: virtual }
                  components: [ { id: counter, params: { value: 7 } } ]
                """);
        int before = harness.engine.query(TestComponents.CounterComp.class).size();
        for (int i = 0; i < 3; i++) {
            EcsEntity spawned = harness.engine.spawn("mgr", null);
            assertNotNull(spawned, "每次单生成都返回独立实体");
        }
        assertEquals(before + 3, harness.engine.query(TestComponents.CounterComp.class).size(),
                "3 次生成 → 3 个实体各挂 counter（波次计数精确的引擎侧保证）");
    }

    private int count(String log, String marker) {
        int count = 0;
        for (char c : log.toCharArray()) {
            if (c == marker.charAt(0)) {
                count++;
            }
        }
        return count;
    }

    /** 记录型系统夹具 */
    private record RecordingSystem(String marker, int interval, int order,
                                   StringBuilder log, boolean boom) implements EcsSystem {

        @Override
        public void tick(EcsEngine engine) {
            if (boom) {
                throw new IllegalStateException("system boom");
            }
            log.append(marker);
        }

        @Override
        public int interval() {
            return interval;
        }

        @Override
        public int order() {
            return order;
        }
    }

    @Test
    @DisplayName("mountComponent 手动 API：按类直接挂载并注入参数")
    void manualMount() {
        EcsEntity entity = harness.engine.createVirtual();
        harness.engine.mountComponent(entity, TestComponents.CounterComp.class,
                Map.of("value", 77));
        assertEquals(77, entity.get(TestComponents.CounterComp.class).value);
        harness.engine.unmountComponent(entity, "counter");
        assertNull(entity.get(TestComponents.CounterComp.class));
    }
}
