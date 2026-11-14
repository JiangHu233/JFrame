package io.github.JiangHu.jframe.engine.ecs.event;

import io.github.JiangHu.jframe.engine.ecs.annotation.OnEcsEvent;
import io.github.JiangHu.jframe.engine.ecs.engine.EcsEngine;
import io.github.JiangHu.jframe.engine.ecs.entity.ComponentBase;
import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;
import io.github.JiangHu.jframe.engine.ecs.fixture.FakeHitEvent;
import io.github.JiangHu.jframe.engine.ecs.fixture.TestComponents;
import io.github.JiangHu.jframe.engine.ecs.fixture.TestRegistries;
import io.github.JiangHu.jframe.engine.ecs.registry.EcsTestHarness;
import io.github.JiangHu.jframe.engine.ecs.system.EcsSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 顺序与截断单测 — {@code @OnEcsEvent#order()} 组内排序、平级注册序契约、
 * {@code truncate} 组边界截断（系统层事件级 / 组件层同实体粒度）、异常不截断、默认值向后兼容。
 *
 * @see io.github.JiangHu.jframe.engine.ecs.annotation.OnEcsEvent
 */
class EventOrderTruncateTest {

    /** 引擎夹具 */
    private EcsTestHarness harness;

    /** 事件桥 */
    private EcsEventBridge bridge;

    /** 主解析器（候选可变） */
    private TestRegistries.FakeHitResolver hitResolver;

    /** 执行顺序记录 */
    private final List<String> order = new ArrayList<>();

    @BeforeEach
    void setUp() {
        harness = new EcsTestHarness();
        harness.resolverRegistry.register("fake-hit", TestRegistries.FakeHitResolver.class);
        hitResolver = (TestRegistries.FakeHitResolver) harness.resolverRegistry.all().get("fake-hit");
        bridge = new EcsEventBridge(harness.engine, harness.componentRegistry,
                harness.resolverRegistry, harness.systemEventRegistry);
        bridge.activate();
        order.clear();
        TestComponents.reset();
    }

    private void mount(String id, Class<? extends ComponentBase> type, EcsEntity entity) {
        if (!harness.componentRegistry.contains(id)) {
            harness.componentRegistry.register(id, type);
        }
        harness.engine.mountComponent(entity, type, Map.of());
    }

    // ==================== 组件组内 order 排序 ====================

    /** order=10 的组件（注册第一个，验证排序打乱注册序） */
    public static class OrderTenComp extends ComponentBase {
        @OnEcsEvent(order = 10)
        public void onHit(FakeHitEvent event) {
            TestComponents.LOG.add("comp:ten");
        }
    }

    /** order=-5 的组件 */
    public static class OrderMinusFiveComp extends ComponentBase {
        @OnEcsEvent(order = -5)
        public void onHit(FakeHitEvent event) {
            TestComponents.LOG.add("comp:minus-five");
        }
    }

    /** order=0 的组件 */
    public static class OrderZeroComp extends ComponentBase {
        @OnEcsEvent
        public void onHit(FakeHitEvent event) {
            TestComponents.LOG.add("comp:zero");
        }
    }

    @Test
    @DisplayName("组件组内排序：同实体多组件按 order 升序（-5 → 0 → 10），与注册序无关")
    void componentHandlersSortedByOrder() {
        EcsEntity entity = harness.engine.createVirtual();
        // 故意按 10 → -5 → 0 的顺序注册并挂载
        mount("order-ten", OrderTenComp.class, entity);
        mount("order-minus-five", OrderMinusFiveComp.class, entity);
        mount("order-zero", OrderZeroComp.class, entity);
        hitResolver.candidates = List.of(entity);

        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));

        assertEquals(List.of("comp:minus-five", "comp:zero", "comp:ten"), TestComponents.LOG,
                "order 升序执行：-5 → 0 → 10");
    }

    /** 平级甲（order=0，注册在前） */
    public static class TieFirstComp extends ComponentBase {
        @OnEcsEvent
        public void onHit(FakeHitEvent event) {
            TestComponents.LOG.add("comp:tie-first");
        }
    }

    /** 平级乙（order=0，注册在后） */
    public static class TieSecondComp extends ComponentBase {
        @OnEcsEvent(order = 0)
        public void onHit(FakeHitEvent event) {
            TestComponents.LOG.add("comp:tie-second");
        }
    }

    @Test
    @DisplayName("平级契约：同 order 按注册顺序执行（文档化契约，防回归）")
    void sameOrderFallsBackToRegistrationOrder() {
        EcsEntity entity = harness.engine.createVirtual();
        mount("tie-first", TieFirstComp.class, entity);
        mount("tie-second", TieSecondComp.class, entity);
        hitResolver.candidates = List.of(entity);

        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));

        assertEquals(List.of("comp:tie-first", "comp:tie-second"), TestComponents.LOG,
                "平级按注册序执行");
    }

    // ==================== 组件截断（同实体粒度） ====================

    /** order=0 截断组件 */
    public static class TruncatingComp extends ComponentBase {
        @OnEcsEvent(order = 0, truncate = true)
        public void onHit(FakeHitEvent event) {
            TestComponents.LOG.add("comp:trunc");
        }
    }

    /** order=0 平级组件（截断者的同 order 伙伴，组边界语义下仍应执行） */
    public static class TruncPeerComp extends ComponentBase {
        @OnEcsEvent(order = 0)
        public void onHit(FakeHitEvent event) {
            TestComponents.LOG.add("comp:trunc-peer");
        }
    }

    /** order=10 低序组件（被截断目标） */
    public static class LowerComp extends ComponentBase {
        @OnEcsEvent(order = 10)
        public void onHit(FakeHitEvent event) {
            TestComponents.LOG.add("comp:lower");
        }
    }

    @Test
    @DisplayName("组件截断：同实体更低 order 跳过，同 order 平级仍执行（组边界检查）")
    void componentTruncateCutsLowerOrderOnSameEntity() {
        EcsEntity entity = harness.engine.createVirtual();
        mount("trunc", TruncatingComp.class, entity);
        mount("trunc-peer", TruncPeerComp.class, entity);
        mount("lower", LowerComp.class, entity);
        hitResolver.candidates = List.of(entity);

        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));

        assertEquals(List.of("comp:trunc", "comp:trunc-peer"), TestComponents.LOG,
                "截断者及其平级执行；同实体更低 order=10 的组件被跳过");
    }

    @Test
    @DisplayName("组件截断范围：仅同实体——其他宿主的组件照常分发")
    void componentTruncateDoesNotAffectOtherHosts() {
        EcsEntity entityA = harness.engine.createVirtual();
        EcsEntity entityB = harness.engine.createVirtual();
        mount("trunc", TruncatingComp.class, entityA);
        mount("lower", LowerComp.class, entityB);
        // 宿主 A 在前：A 截断不应影响 B
        hitResolver.candidates = List.of(entityA, entityB);

        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));

        assertEquals(List.of("comp:trunc", "comp:lower"), TestComponents.LOG,
                "A 实体截断只作用于 A；B 实体的低序组件照常执行");
    }

    /** order=0 截断但抛异常的组件（异常 → 不截断） */
    public static class TruncThrowingComp extends ComponentBase {
        @OnEcsEvent(order = 0, truncate = true)
        public void onHit(FakeHitEvent event) {
            TestComponents.LOG.add("comp:trunc-throw");
            throw new IllegalStateException("handler boom");
        }
    }

    @Test
    @DisplayName("截断生效时机：处理器抛异常 → 不截断，低序组件照常执行")
    void truncateNotEffectiveOnException() {
        EcsEntity entity = harness.engine.createVirtual();
        mount("trunc-throw", TruncThrowingComp.class, entity);
        mount("lower", LowerComp.class, entity);
        hitResolver.candidates = List.of(entity);

        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));

        assertEquals(List.of("comp:trunc-throw", "comp:lower"), TestComponents.LOG,
                "异常处理器不产生截断效果");
    }

    // ==================== 系统组内 order 排序与事件级截断 ====================

    /** order=10 记录系统 */
    class SysTen implements EcsSystem {
        @Override
        public void tick(EcsEngine engine) {
        }

        @OnEcsEvent(order = 10)
        public void onHit(FakeHitEvent event) {
            order.add("sys:ten");
        }
    }

    /** order=-5 记录系统 */
    class SysMinusFive implements EcsSystem {
        @Override
        public void tick(EcsEngine engine) {
        }

        @OnEcsEvent(order = -5)
        public void onHit(FakeHitEvent event) {
            order.add("sys:minus-five");
        }
    }

    @Test
    @DisplayName("系统组内排序：按 order 升序执行，与注册序无关")
    void systemHandlersSortedByOrder() {
        harness.engine.addSystem(new SysTen());
        harness.engine.addSystem(new SysMinusFive());

        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));

        assertEquals(List.of("sys:minus-five", "sys:ten"), order, "系统处理器 order 升序");
    }

    /** order=0 截断系统 */
    class SysTrunc implements EcsSystem {
        @Override
        public void tick(EcsEngine engine) {
        }

        @OnEcsEvent(order = 0, truncate = true)
        public void onHit(FakeHitEvent event) {
            order.add("sys:trunc");
        }
    }

    /** order=5 平级后系统（截断者的同 order 检查后应被跳过——order=5 > 0） */
    class SysFive implements EcsSystem {
        @Override
        public void tick(EcsEngine engine) {
        }

        @OnEcsEvent(order = 5)
        public void onHit(FakeHitEvent event) {
            order.add("sys:five");
        }
    }

    /** order=0 系统平级（组边界语义下与截断者同组，仍执行） */
    class SysPeer implements EcsSystem {
        @Override
        public void tick(EcsEngine engine) {
        }

        @OnEcsEvent(order = 0)
        public void onHit(FakeHitEvent event) {
            order.add("sys:peer");
        }
    }

    @Test
    @DisplayName("系统截断（事件级）：同层更低 order 跳过 + 整个组件层不分发；同 order 平级仍执行")
    void systemTruncateCutsLowerSystemsAndComponentTier() {
        // 注册序 Five(5) → Trunc(0) → Peer(0)；排序稳定化后组0=[Trunc, Peer]、组5=[Five]
        harness.engine.addSystem(new SysFive());      // order=5，应被截断
        harness.engine.addSystem(new SysTrunc());     // order=0，截断者
        harness.engine.addSystem(new SysPeer());      // order=0，平级仍执行
        EcsEntity entity = harness.engine.createVirtual();
        mount("lower", LowerComp.class, entity);      // 组件层：应整体不分发
        hitResolver.candidates = List.of(entity);

        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));

        // 组0 全执行（Trunc、Peer），组边界截断 → 组5 的 Five 跳过
        assertEquals(List.of("sys:trunc", "sys:peer"), order,
                "截断者与其同 order 平级执行；order=5 系统被跳过");
        assertEquals(List.of(), TestComponents.LOG, "系统层截断 → 组件层整体不分发");
    }

    @Test
    @DisplayName("系统截断与取消正交：截断不 setCancelled（Nukkit 下游不受影响）")
    void systemTruncateDoesNotCancelEvent() {
        class SysTruncCancel implements EcsSystem {
            @Override
            public void tick(EcsEngine engine) {
            }

            @OnEcsEvent(order = 0, truncate = true)
            public void onCancel(io.github.JiangHu.jframe.engine.ecs.fixture.FakeCancelEvent event) {
                order.add("sys:trunc-cancel");
            }
        }
        harness.engine.addSystem(new SysTruncCancel());

        io.github.JiangHu.jframe.engine.ecs.fixture.FakeCancelEvent event =
                new io.github.JiangHu.jframe.engine.ecs.fixture.FakeCancelEvent();
        bridge.dispatch(event.getClass(), event);

        assertEquals(List.of("sys:trunc-cancel"), order);
        assertEquals(false, event.isCancelled(),
                "截断只作用于 ECS 内部分发，不取消底层 Nukkit 事件");
    }

    // ==================== 默认值向后兼容 ====================

    @Test
    @DisplayName("向后兼容：全部默认（order=0/false）时行为与旧版一致——按注册序、无截断")
    void defaultsPreserveLegacyBehavior() {
        EcsEntity entity = harness.engine.createVirtual();
        mount("tie-first", TieFirstComp.class, entity);
        mount("tie-second", TieSecondComp.class, entity);
        mount("lower", LowerComp.class, entity);   // order=10 → 排在默认 0 之后
        hitResolver.candidates = List.of(entity);

        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));

        assertEquals(List.of("comp:tie-first", "comp:tie-second", "comp:lower"), TestComponents.LOG,
                "默认值下：平级注册序、正 order 靠后、无截断——与旧注册序行为一致");
    }
}
