package io.github.JiangHu.jframe.engine.ecs.event;

import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;
import io.github.JiangHu.jframe.engine.ecs.engine.EcsEngine;
import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;
import io.github.JiangHu.jframe.engine.ecs.fixture.FakeCancelEvent;
import io.github.JiangHu.jframe.engine.ecs.fixture.FakeHitEvent;
import io.github.JiangHu.jframe.engine.ecs.fixture.TestComponents;
import io.github.JiangHu.jframe.engine.ecs.fixture.TestRegistries;
import io.github.JiangHu.jframe.engine.ecs.registry.EcsTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EcsEventBridge 单测 — 分发链路：类型级注册、宿主级解析、组件级订阅、方法级守卫/ignoreCancelled、链路告警。
 */
class EventBridgeTest {

    /** 引擎夹具 */
    private EcsTestHarness harness;

    /** 事件桥 */
    private EcsEventBridge bridge;

    /** 主解析器（候选可变） */
    private TestRegistries.FakeHitResolver hitResolver;

    @BeforeEach
    void setUp() {
        harness = new EcsTestHarness();
        harness.componentRegistry.register("eventful", TestComponents.EventfulComp.class);
        harness.resolverRegistry.register("fake-hit", TestRegistries.FakeHitResolver.class);
        hitResolver = (TestRegistries.FakeHitResolver)
                harness.resolverRegistry.all().get("fake-hit");
        bridge = new EcsEventBridge(harness.engine, harness.componentRegistry,
                harness.resolverRegistry, harness.systemEventRegistry);
        bridge.activate();
        TestComponents.reset();
    }

    private EcsEntity mountEventful(int threshold) {
        EcsEntity entity = harness.engine.createVirtual();
        harness.engine.mountComponent(entity, TestComponents.EventfulComp.class,
                java.util.Map.of("threshold", threshold));
        return entity;
    }

    @Test
    @DisplayName("基础分发：解析命中宿主 → 已挂组件的 handler 收到事件且 host() 正确")
    void dispatchToMountedComponent() {
        EcsEntity entity = mountEventful(4);
        hitResolver.candidates = List.of(entity);
        FakeHitEvent event = new FakeHitEvent(9);

        bridge.dispatch(FakeHitEvent.class, event);

        TestComponents.EventfulComp comp = entity.get(TestComponents.EventfulComp.class);
        assertTrue(comp.received.contains(event), "普通 handler 应收到事件");
        assertSame(entity, comp.seenHost, "事件回调内 host() 应识别宿主");
    }

    @Test
    @DisplayName("组件级过滤：未挂该组件的实体不收事件（卸载即退订）")
    void componentLevelFilter() {
        EcsEntity mounted = mountEventful(4);
        EcsEntity bare = harness.engine.createVirtual();
        hitResolver.candidates = List.of(mounted, bare);

        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));

        assertNotNull(mounted.get(TestComponents.EventfulComp.class));
        assertEquals(1, TestComponents.LOG.stream().filter(s -> s.equals("event:hit:9")).count(),
                "只有已挂组件的实体收到一次分发");
        assertNull(bare.get(TestComponents.EventfulComp.class), "未挂组件的实体不收事件");

        // 卸载后不再接收（卸载即退订）
        mounted.destroy();
        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));
        assertEquals(1, TestComponents.LOG.stream().filter(s -> s.equals("event:hit:9")).count(),
                "destroy 后不应再收到事件");
    }

    @Test
    @DisplayName("宿主级过滤：未跟踪候选（任意对象）丢弃，不报错")
    void untrackedHostDropped() {
        mountEventful(4);
        hitResolver.candidates = List.of(new Object(), "weird-holder");
        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));
        assertTrue(TestComponents.LOG.stream().noneMatch(s -> s.startsWith("event:")),
                "未跟踪宿主不应触发任何 handler");
    }

    @Test
    @DisplayName("内联守卫：边界精确（damage > threshold，等值不通过）")
    void inlineGuardBoundary() {
        EcsEntity entity = mountEventful(4);   // threshold=4
        hitResolver.candidates = List.of(entity);

        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(4));
        assertTrue(TestComponents.LOG.stream().noneMatch(s -> s.startsWith("event:guarded")),
                "damage=4 等于阈值（> 判定）不通过");

        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(5));
        assertTrue(TestComponents.LOG.stream().anyMatch(s -> s.equals("event:guarded:5")),
                "damage=5 > threshold=4 通过（守卫在方法内首行，直接读 this 字段）");
    }

    @Test
    @DisplayName("ignoreCancelled：已取消事件被跳过，未取消正常分发")
    void ignoreCancelled() {
        EcsEntity entity = mountEventful(4);
        harness.resolverRegistry.register("fake-cancel", TestRegistries.FakeCancelResolver.class);
        TestRegistries.FakeCancelResolver cancelResolver = (TestRegistries.FakeCancelResolver)
                harness.resolverRegistry.all().get("fake-cancel");
        cancelResolver.target = entity;

        FakeCancelEvent cancelled = new FakeCancelEvent();
        cancelled.setCancelled();
        bridge.dispatch(FakeCancelEvent.class, cancelled);
        assertTrue(TestComponents.LOG.stream().noneMatch(s -> s.equals("event:cancel")),
                "已取消事件应被 ignoreCancelled 跳过");

        bridge.dispatch(FakeCancelEvent.class, new FakeCancelEvent());
        assertTrue(TestComponents.LOG.stream().anyMatch(s -> s.equals("event:cancel")),
                "未取消事件正常分发");
    }

    @Test
    @DisplayName("handler 异常隔离：抛异常的 handler 不影响其余 handler")
    void handlerExceptionIsolation() {
        EcsEntity entity = mountEventful(4);
        hitResolver.candidates = List.of(entity);
        // EventfulComp 的 onBoom(FakeCancelEvent) 会抛异常，但 fake-cancel 解析器未注册，
        // 这里用 hit 路径验证：onHit 正常执行即证明整体分发未被异常破坏
        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));
        assertTrue(TestComponents.LOG.stream().anyMatch(s -> s.equals("event:hit:9")));

        // 注册 fake-cancel 后 onBoom 抛异常，onCancellable 仍应执行
        harness.resolverRegistry.register("fake-cancel", TestRegistries.FakeCancelResolver.class);
        TestRegistries.FakeCancelResolver cancelResolver = (TestRegistries.FakeCancelResolver)
                harness.resolverRegistry.all().get("fake-cancel");
        cancelResolver.target = entity;
        bridge.dispatch(FakeCancelEvent.class, new FakeCancelEvent());
        assertTrue(TestComponents.LOG.stream().anyMatch(s -> s.equals("event:cancel")),
                "onBoom 异常后 onCancellable 仍应执行");
    }

    @Test
    @DisplayName("解析器宽匹配：声明父类型（FakeEvent）可服务子类型事件（FakeHitEvent）")
    void resolverWideMatch() {
        EcsEntity entity = mountEventful(4);
        hitResolver.candidates = List.of(entity);
        // hitResolver.eventType() = FakeEvent（父类型），分发 FakeHitEvent 应命中
        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));
        assertTrue(TestComponents.LOG.stream().anyMatch(s -> s.equals("event:hit:9")));
    }

    @Test
    @DisplayName("同事件多解析器命中同一实体只分发一次")
    void dedupeAcrossResolvers() {
        EcsEntity entity = mountEventful(4);
        hitResolver.candidates = List.of(entity);
        harness.resolverRegistry.register("dup", TestRegistries.DupHitResolver.class);
        TestRegistries.DupHitResolver dup = (TestRegistries.DupHitResolver)
                harness.resolverRegistry.all().get("dup");
        dup.candidates = List.of(entity);

        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));
        long hits = TestComponents.LOG.stream().filter(s -> s.equals("event:hit:9")).count();
        assertEquals(1, hits, "同实体同事件只应分发一次");
    }

    @Test
    @DisplayName("注册期校验：@OnEcsEvent 方法签名非法（双参数）→ 拒绝")
    void badHandlerSignatureRejected() {
        assertThrows(EcsConfigException.class,
                () -> harness.componentRegistry.register("bad-sig",
                        TestComponents.BadSignatureComp.class));
    }

    @Test
    @DisplayName("无解析器告警：组件订阅无覆盖事件类型 → 进入 unresolvedWarned（一次性）")
    void unresolvedSubscriptionWarned() {
        // 独立夹具：不注册 fake-hit，FakeEvent 族（FakeHit/FakeCancel）无解析器覆盖
        var fresh = new io.github.JiangHu.jframe.engine.ecs.registry.EcsTestHarness();
        var freshBridge = new EcsEventBridge(fresh.engine, fresh.componentRegistry,
                fresh.resolverRegistry, fresh.systemEventRegistry);
        freshBridge.activate();
        assertTrue(freshBridge.unresolvedWarned().isEmpty(), "初始无告警");

        fresh.componentRegistry.register("eventful", TestComponents.EventfulComp.class);
        freshBridge.syncRegistrations();
        assertTrue(freshBridge.unresolvedWarned().contains(FakeHitEvent.class),
                "无解析器覆盖的订阅类型应进入告警集合");
        assertTrue(freshBridge.unresolvedWarned().contains(io.github.JiangHu.jframe.engine.ecs.fixture.FakeCancelEvent.class));

        // 二次 sync 不重复告警（按类型一次性）
        freshBridge.syncRegistrations();
        assertEquals(2, freshBridge.unresolvedWarned().size(), "按类型一次性去重");
    }

    @Test
    @DisplayName("未激活（deactivate）时分发为空操作")
    void inactiveBridgeSkipsDispatch() {
        EcsEntity entity = mountEventful(4);
        hitResolver.candidates = List.of(entity);
        bridge.deactivate();
        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));
        assertTrue(TestComponents.LOG.stream().noneMatch(s -> s.startsWith("event:")));
    }

    @Test
    @DisplayName("事件类型级过滤：无解析器覆盖的事件类型即使有订阅也不分发")
    void eventTypeWithoutResolverNotDispatched() {
        EcsEntity entity = mountEventful(4);
        hitResolver.candidates = List.of(entity);
        // UnrelatedEvent 与 FakeEvent 族无关：无解析器覆盖 → 一级过滤直接拦截
        cn.nukkit.event.Event unrelated = new cn.nukkit.event.Event() {
        };
        bridge.dispatch(unrelated.getClass(), unrelated);
        assertTrue(TestComponents.LOG.stream().noneMatch(s -> s.startsWith("event:")),
                "无解析器覆盖的事件不应进入 ECS");
    }
}
