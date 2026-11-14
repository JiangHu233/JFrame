package io.github.JiangHu.jframe.engine.ecs.event;

import io.github.JiangHu.jframe.engine.ecs.annotation.EcsSystemAnno;
import io.github.JiangHu.jframe.engine.ecs.annotation.OnEcsEvent;
import io.github.JiangHu.jframe.engine.ecs.engine.EcsEngine;
import io.github.JiangHu.jframe.engine.ecs.entity.ComponentBase;
import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;
import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;
import io.github.JiangHu.jframe.engine.ecs.system.EcsEngineAware;
import io.github.JiangHu.jframe.engine.ecs.system.EcsSystem;
import io.github.JiangHu.jframe.engine.ecs.fixture.FakeCancelEvent;
import io.github.JiangHu.jframe.engine.ecs.fixture.FakeHitEvent;
import io.github.JiangHu.jframe.engine.ecs.fixture.TestComponents;
import io.github.JiangHu.jframe.engine.ecs.fixture.TestRegistries;
import io.github.JiangHu.jframe.engine.ecs.registry.EcsTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统事件单测 — 全局处理器：无解析器分发、when/ignoreCancelled、filter 拒绝、
 * 异常隔离、先系统后组件顺序、EcsEngineAware 绑定。
 */
class SystemEventTest {

    /** 引擎夹具 */
    private EcsTestHarness harness;

    /** 事件桥 */
    private EcsEventBridge bridge;

    /** 调用顺序记录（系统/组件处理器共同写入） */
    private final List<String> order = new ArrayList<>();

    @BeforeEach
    void setUp() {
        harness = new EcsTestHarness();
        bridge = new EcsEventBridge(harness.engine, harness.componentRegistry,
                harness.resolverRegistry, harness.systemEventRegistry);
        bridge.activate();
        order.clear();
    }

    /** 记录型系统（EcsEngineAware） */
    class EchoSystem implements EcsSystem, EcsEngineAware {
        EcsEngine bound;
        int hits;

        @Override
        public void bindEngine(EcsEngine engine) {
            this.bound = engine;
        }

        @Override
        public void tick(EcsEngine engine) {
            // 不 tick，仅事件驱动
        }

        @OnEcsEvent
        public void onHit(FakeHitEvent event) {
            hits++;
            order.add("system");
        }
    }

    @Test
    @DisplayName("系统订阅分发：无解析器的事件类型也能收到（每事件一次）")
    void systemHandlerDispatchedWithoutResolver() {
        EchoSystem system = new EchoSystem();
        harness.engine.addSystem(system);

        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));
        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));
        assertEquals(2, system.hits, "全局处理器每事件调用一次");
        assertSame(harness.engine, system.bound, "EcsEngineAware 应收到引擎引用");
    }

    @Test
    @DisplayName("系统处理器内联守卫：条件不满足自行返回")
    void systemInlineGuard() {
        class GuardedSystem implements EcsSystem {
            int hits;

            @Override
            public void tick(EcsEngine engine) {
            }

            @OnEcsEvent
            public void onHit(FakeHitEvent event) {
                if (event.damage <= 5) {
                    return; // 方法内首行守卫（系统无宿主，直接用事件自身字段）
                }
                hits++;
            }
        }
        GuardedSystem system = new GuardedSystem();
        harness.engine.addSystem(system);

        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(3));
        assertEquals(0, system.hits, "damage=3 被内联守卫拦截");

        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));
        assertEquals(1, system.hits, "damage=9 通过守卫");
    }

    @Test
    @DisplayName("ignoreCancelled：已取消事件被跳过")
    void ignoreCancelledSkips() {
        class CancelSystem implements EcsSystem {
            int hits;

            @Override
            public void tick(EcsEngine engine) {
            }

            @OnEcsEvent(ignoreCancelled = true)
            public void onCancel(FakeCancelEvent event) {
                hits++;
            }
        }
        CancelSystem system = new CancelSystem();
        harness.engine.addSystem(system);

        FakeCancelEvent cancelled = new FakeCancelEvent();
        cancelled.setCancelled();
        bridge.dispatch(FakeCancelEvent.class, cancelled);
        assertEquals(0, system.hits);

        bridge.dispatch(FakeCancelEvent.class, new FakeCancelEvent());
        assertEquals(1, system.hits);
    }

    @Test
    @DisplayName("注册期校验：签名非法（双参数）→ 拒绝")
    void badSignatureRejected() {
        class BadSigSystem implements EcsSystem {
            @Override
            public void tick(EcsEngine engine) {
            }

            @OnEcsEvent
            public void onHit(FakeHitEvent event, Object extra) {
            }
        }
        assertThrows(EcsConfigException.class, () -> harness.engine.addSystem(new BadSigSystem()));
    }

    @Test
    @DisplayName("异常隔离：第一个系统处理器抛异常不影响后续系统与组件")
    void exceptionIsolation() {
        class BoomSystem implements EcsSystem {
            @Override
            public void tick(EcsEngine engine) {
            }

            @OnEcsEvent
            public void onHit(FakeHitEvent event) {
                order.add("boom");
                throw new IllegalStateException("boom");
            }
        }
        EchoSystem second = new EchoSystem();
        harness.engine.addSystem(new BoomSystem());
        harness.engine.addSystem(second);

        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));

        assertTrue(order.contains("boom"), "异常处理器确实被调用过");
        assertEquals(1, second.hits, "异常不阻断后续系统处理器");
    }

    @Test
    @DisplayName("分发顺序：先系统（全局）后组件（局部）")
    void systemBeforeComponent() {
        // 组件侧：注册 + 解析器 + 挂载（记录到 TestComponents.LOG）
        harness.componentRegistry.register("order-probe", OrderProbeComponent.class);
        harness.resolverRegistry.register("fake-hit", TestRegistries.FakeHitResolver.class);
        TestRegistries.FakeHitResolver resolver = (TestRegistries.FakeHitResolver)
                harness.resolverRegistry.all().get("fake-hit");
        EcsEntity entity = harness.engine.createVirtual();
        harness.engine.mountComponent(entity, OrderProbeComponent.class, java.util.Map.of());
        resolver.candidates = List.of(entity);

        // 系统侧（记录到 TestComponents.LOG）
        class LogSystem implements EcsSystem {
            @Override
            public void tick(EcsEngine engine) {
            }

            @OnEcsEvent
            public void onHit(FakeHitEvent event) {
                TestComponents.LOG.add("system");
            }
        }
        harness.engine.addSystem(new LogSystem());

        TestComponents.reset();
        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));

        int systemAt = TestComponents.LOG.indexOf("system");
        int componentAt = TestComponents.LOG.indexOf("component");
        assertTrue(systemAt >= 0, "系统处理器应被调用");
        assertTrue(componentAt >= 0, "组件处理器应被调用");
        assertTrue(systemAt < componentAt, "系统处理器应先于组件处理器执行");
    }

    /** 顺序探针组件（记录到 TestComponents.LOG） */
    @io.github.JiangHu.jframe.engine.ecs.annotation.EcsComponent("order-probe")
    public static class OrderProbeComponent extends ComponentBase {
        /** 记录处理器 */
        @OnEcsEvent
        public void onHit(FakeHitEvent event) {
            TestComponents.LOG.add("component");
        }
    }

    @Test
    @DisplayName("未激活（deactivate）时系统分发也为空操作")
    void inactiveSkipsSystemDispatch() {
        EchoSystem system = new EchoSystem();
        harness.engine.addSystem(system);
        bridge.deactivate();
        bridge.dispatch(FakeHitEvent.class, new FakeHitEvent(9));
        assertEquals(0, system.hits);
        assertFalse(order.contains("system"));
    }
}
