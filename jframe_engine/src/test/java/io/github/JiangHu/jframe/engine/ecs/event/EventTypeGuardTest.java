package io.github.JiangHu.jframe.engine.ecs.event;

import io.github.JiangHu.jframe.engine.ecs.annotation.OnEcsEvent;
import io.github.JiangHu.jframe.engine.ecs.entity.ComponentBase;
import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;
import io.github.JiangHu.jframe.engine.ecs.fixture.FakeEvent;
import io.github.JiangHu.jframe.engine.ecs.fixture.FakeHitEvent;
import io.github.JiangHu.jframe.engine.ecs.fixture.TestComponents;
import io.github.JiangHu.jframe.engine.ecs.fixture.TestRegistries;
import io.github.JiangHu.jframe.engine.ecs.registry.EcsTestHarness;
import io.github.JiangHu.jframe.engine.ecs.system.EcsSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 事件类型守卫单测 — 基类事件实例经 Nukkit HandlerList 继承链进入子类型监听时
 * （MOT 无独立 HandlerList 的子类事件常态，如 fall 伤害进 ByEntity 监听），
 * 桥必须按 {@code isInstance} 跳过声明类型不匹配的处理器，不得抛
 * {@code IllegalArgumentException: argument type mismatch}。
 */
class EventTypeGuardTest {

    /**
     * 与 FakeHitEvent 同父的具体事件——模拟 fall 伤害的 {@code EntityDamageEvent}
     * 基类实例（非 ByEntity 子类实例）被送进 ByEntity 监听的形态。
     */
    static class PlainFakeEvent extends FakeEvent {
    }

    /** 引擎夹具 */
    private EcsTestHarness harness;

    /** 事件桥 */
    private EcsEventBridge bridge;

    /** 主解析器（候选可变） */
    private TestRegistries.FakeHitResolver hitResolver;

    @BeforeEach
    void setUp() {
        harness = new EcsTestHarness();
        harness.resolverRegistry.register("fake-hit", TestRegistries.FakeHitResolver.class);
        hitResolver = (TestRegistries.FakeHitResolver) harness.resolverRegistry.all().get("fake-hit");
        bridge = new EcsEventBridge(harness.engine, harness.componentRegistry,
                harness.resolverRegistry, harness.systemEventRegistry);
        bridge.activate();
        TestComponents.reset();
    }

    /** 订阅子类型（FakeHitEvent）的组件——模拟 CombatRuntimeComp#onMeleeAttack */
    public static class SubTypedComp extends ComponentBase {
        /** 执行计数 */
        public static int runs = 0;

        @OnEcsEvent
        public void onHit(FakeHitEvent event) {
            runs++;
        }
    }

    /** 订阅子类型的系统——模拟系统级 ByEntity 处理器 */
    class SubTypedSystem implements EcsSystem {
        /** 执行计数 */
        int runs = 0;

        @Override
        public void tick(io.github.JiangHu.jframe.engine.ecs.engine.EcsEngine engine) {
        }

        @OnEcsEvent
        public void onHit(FakeHitEvent event) {
            runs++;
        }
    }

    @Test
    @DisplayName("组件守卫：基类事件实例进子类型监听 → 跳过不炸（原 mismatch 场景）")
    void componentHandlerSkippedOnBaseEventInstance() {
        harness.componentRegistry.register("sub-typed", SubTypedComp.class);
        EcsEntity entity = harness.engine.createVirtual();
        harness.engine.mountComponent(entity, SubTypedComp.class, Map.of());
        hitResolver.candidates = List.of(entity);

        // 真实场景复现：eventType=注册类型（子类），实际事件=基类族非子类实例
        // （Nukkit HandlerList 继承链把基类事件送进子类型监听器的形态）
        bridge.dispatch(FakeHitEvent.class, new PlainFakeEvent());

        assertEquals(0, SubTypedComp.runs, "基类实例不应触达子类型处理器");
        // 同场景下子类型事件实例正常触达（守卫不影响正常路径）
        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));
        assertEquals(1, SubTypedComp.runs, "子类型实例正常触达");
    }

    @Test
    @DisplayName("系统守卫：基类事件实例进子类型监听 → 跳过不炸")
    void systemHandlerSkippedOnBaseEventInstance() {
        SubTypedSystem system = new SubTypedSystem();
        harness.systemEventRegistry.register(system);

        bridge.dispatch(FakeHitEvent.class, new PlainFakeEvent());
        assertEquals(0, system.runs, "基类实例不应触达子类型系统处理器");

        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));
        assertEquals(1, system.runs, "子类型实例正常触达");
    }
}
