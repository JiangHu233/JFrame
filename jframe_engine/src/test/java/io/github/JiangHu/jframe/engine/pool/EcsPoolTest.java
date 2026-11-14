package io.github.JiangHu.jframe.engine.pool;

import io.github.JiangHu.jframe.engine.ecs.entity.ComponentBase;
import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;
import io.github.JiangHu.jframe.engine.ecs.registry.EcsTestHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EcsPool 集成单测 — 组件池复用（opt-in）、reset 契约与调用序、懒建池、
 * 实体壳复用（id 单调不复用 + tags 快照隔离）、onMount 回滚归池、
 * 全局开关回归、stop 清场。
 */
class EcsPoolTest {

    /** 池化测试组件：记录生命周期顺序 + 运行时状态（reset 清理验证） */
    public static class PooledComp extends ComponentBase {
        /** params 注入字段（每次挂载全量覆盖） */
        public int value;

        /** 运行时累积状态（reset 必须清） */
        public final List<String> runtime = new ArrayList<>();

        @Override
        public void onMount() {
            TestLog.LOG.add("mount:" + value);
            runtime.add("dirty");
        }

        @Override
        public void onUnmount() {
            TestLog.LOG.add("unmount");
        }

        @Override
        public void reset() {
            TestLog.LOG.add("reset");
            runtime.clear();
        }
    }

    /** 非池化对照组件 */
    public static class PlainComp extends ComponentBase {
    }

    /** onMount 首次抛异常的池化组件（回滚归池验证） */
    public static class FailOnceComp extends ComponentBase {
        /** 抛异常开关（挂一次后关闭） */
        public static boolean fail = true;

        @Override
        public void onMount() {
            if (fail) {
                fail = false;
                throw new IllegalStateException("first mount fails");
            }
        }
    }

    /** 静态调用序日志 */
    static class TestLog {
        static final List<String> LOG = new ArrayList<>();
    }

    /** 池化组件的 reset 未清字段场景组件（debug 扫描丢弃验证） */
    public static class DirtyResetComp extends ComponentBase {
        /** reset 不清的残留字段 */
        public String leftover = "x";

        @Override
        public void reset() {
            // 故意不清 leftover
        }
    }

    private EcsTestHarness harness(EcsPoolConfig config) {
        EcsPool pool = new EcsPool(config);
        EcsTestHarness harness = new EcsTestHarness(pool);
        harness.componentRegistry.register("pooled", PooledComp.class, true);
        harness.componentRegistry.register("plain", PlainComp.class);
        harness.componentRegistry.register("fail-once", FailOnceComp.class, true);
        harness.componentRegistry.register("dirty", DirtyResetComp.class, true);
        TestLog.LOG.clear();
        FailOnceComp.fail = true;
        return harness;
    }

    private EcsTestHarness defaultHarness() {
        return harness(new EcsPoolConfig());
    }

    @Test
    @DisplayName("组件池复用：pooled=true 卸载后重挂为同一实例；非池化类每次新实例")
    void pooledComponentReuse() {
        EcsTestHarness harness = defaultHarness();

        EcsEntity a = harness.engine.createVirtual();
        a.mount(PooledComp.class, null);
        PooledComp first = a.get(PooledComp.class);
        a.unmount("pooled");
        assertTrue(first.runtime.isEmpty(), "reset 清掉了运行时状态");
        assertTrue(harness.engine.ecsPool().stats().components()
                .get("PooledComp").idle() == 1, "实例已入池");

        EcsEntity b = harness.engine.createVirtual();
        b.mount(PooledComp.class, null);
        PooledComp second = b.get(PooledComp.class);

        assertSame(first, second, "pooled 组件卸载后重挂复用同一实例");
        b.unmount("pooled");

        // 非池化对照
        EcsEntity c = harness.engine.createVirtual();
        c.mount(PlainComp.class, null);
        PlainComp p1 = c.get(PlainComp.class);
        c.unmount("plain");
        EcsEntity d = harness.engine.createVirtual();
        d.mount(PlainComp.class, null);
        PlainComp p2 = d.get(PlainComp.class);
        assertNotSame(p1, p2, "未登记 pooled 的类每次新建");
    }

    @Test
    @DisplayName("reset 契约：free 时先 onUnmount 后 reset；框架注入字段被清")
    void resetContractOrder() {
        EcsTestHarness harness = defaultHarness();
        EcsEntity entity = harness.engine.createVirtual();
        entity.mount(PooledComp.class, null);
        TestLog.LOG.clear();
        entity.unmount("pooled");

        assertEquals(List.of("unmount", "reset"), TestLog.LOG,
                "free 时序：onUnmount 之后 reset");
    }

    @Test
    @DisplayName("懒建池：从未回收过的类不建池（stats 无条目）")
    void lazyPoolCreation() {
        EcsTestHarness harness = defaultHarness();
        EcsEntity entity = harness.engine.createVirtual();
        entity.mount(PooledComp.class, null);

        assertTrue(harness.engine.ecsPool().stats().componentPools() == 0,
                "挂载不建池");
        entity.unmount("pooled");
        assertEquals(1, harness.engine.ecsPool().stats().componentPools(),
                "首次回收建池");
        assertTrue(harness.engine.ecsPool().stats().components()
                .containsKey("PooledComp"));
    }

    @Test
    @DisplayName("壳复用：destroy 后新实体复用壳但 id 单调递增；旧 id 查无；复用壳有效")
    void shellReuseWithMonotonicId() {
        EcsTestHarness harness = defaultHarness();
        EcsEntity first = harness.engine.createVirtual();
        long firstId = first.id();
        first.destroy();

        EcsEntity second = harness.engine.createVirtual();
        assertSame(first, second, "壳对象复用");
        assertTrue(second.id() > firstId, "id 单调递增不复用");
        assertNull(harness.engine.getEntity(firstId), "旧 id 查无");
        assertTrue(second.valid(), "复用壳为有效状态");
    }

    @Test
    @DisplayName("tags 快照隔离：失效前包装读到退役内容，与复用壳的新实体互不串扰")
    void tagsSnapshotIsolation() {
        EcsTestHarness harness = defaultHarness();
        EcsEntity first = harness.engine.createVirtual();
        first.addTag("old-tag");
        Set<String> wrapper = first.tags();
        first.destroy();

        EcsEntity second = harness.engine.createVirtual();   // 取回同一壳
        assertSame(first, second, "壳复用前提");
        assertFalse(second.hasTag("old-tag"), "新实体标签为空");
        second.addTag("new-tag");
        assertEquals(Set.of("old-tag"), wrapper, "失效前包装视图仍读退役快照");
        assertEquals(Set.of("new-tag"), second.tags(), "新实体读新标签");
    }

    @Test
    @DisplayName("组件表隔离：复用壳的新实体不带上世的组件")
    void componentsIsolation() {
        EcsTestHarness harness = defaultHarness();
        EcsEntity first = harness.engine.createVirtual();
        first.mount(PooledComp.class, null);
        assertTrue(first.has(PooledComp.class));
        first.destroy();

        EcsEntity second = harness.engine.createVirtual();
        assertTrue(second.componentTypes().isEmpty(), "复用壳组件表为空");
    }

    @Test
    @DisplayName("onMount 异常回滚路径同样归池：第二次挂载复用同一实例")
    void onMountFailureStillPools() {
        EcsTestHarness harness = defaultHarness();
        EcsEntity first = harness.engine.createVirtual();
        first.mount(FailOnceComp.class, null);
        assertFalse(first.has(FailOnceComp.class), "onMount 失败已回滚");

        EcsEntity second = harness.engine.createVirtual();
        second.mount(FailOnceComp.class, null);
        assertTrue(second.has(FailOnceComp.class), "第二次挂载成功（fail 已关）");

        // 回滚实例入池验证：第三次挂载应复用第二次的实例
        FailOnceComp mounted = second.get(FailOnceComp.class);
        second.unmount("fail-once");
        EcsEntity third = harness.engine.createVirtual();
        third.mount(FailOnceComp.class, null);
        assertSame(mounted, third.get(FailOnceComp.class), "正常卸载后复用");
    }

    @Test
    @DisplayName("debug 扫描：reset 后残留字段的组件被丢弃不入池")
    void debugResidualScanDrops() {
        EcsPoolConfig config = new EcsPoolConfig();
        config.setDebug(true);
        EcsTestHarness harness = harness(config);
        EcsEntity entity = harness.engine.createVirtual();
        entity.mount(DirtyResetComp.class, null);
        entity.unmount("dirty");

        assertEquals(0, harness.engine.ecsPool().stats().componentPools(),
                "残留实例被丢弃，池未建立");
    }

    @Test
    @DisplayName("全局开关 enabled=false：行为与无池一致（新实例、壳不复用）")
    void disabledPoolRegression() {
        EcsPoolConfig config = new EcsPoolConfig();
        config.setEnabled(false);
        EcsTestHarness harness = harness(config);

        EcsEntity a = harness.engine.createVirtual();
        a.mount(PooledComp.class, null);
        PooledComp first = a.get(PooledComp.class);
        a.unmount("pooled");
        EcsEntity b = harness.engine.createVirtual();
        b.mount(PooledComp.class, null);
        assertNotSame(first, b.get(PooledComp.class), "关闭时 pooled 也每次新建");

        a = harness.engine.createVirtual();
        EcsEntity oldShell = a;
        a.destroy();
        EcsEntity c = harness.engine.createVirtual();
        assertNotSame(oldShell, c, "关闭时壳不复用");
    }

    @Test
    @DisplayName("removeAll 清场：引擎停止后池清空（空闲引用释放）")
    void removeAllClearsPool() {
        EcsTestHarness harness = defaultHarness();
        EcsEntity entity = harness.engine.createVirtual();
        entity.mount(PooledComp.class, null);
        entity.unmount("pooled");
        assertEquals(1, harness.engine.ecsPool().stats().componentPools());

        harness.engine.removeAll();
        assertEquals(0, harness.engine.ecsPool().stats().shellsIdle(), "壳池清空");
        assertTrue(harness.engine.ecsPool().stats().components().get("PooledComp").idle() == 0,
                "组件池清空");
    }

    @Test
    @DisplayName("引擎查询与卸载管线在池化下行为不变（现有语义回归）")
    void pipelineStillConsistentWithPooling() {
        EcsTestHarness harness = defaultHarness();
        EcsEntity entity = harness.engine.createVirtual();
        entity.mount(PooledComp.class, java.util.Map.of("value", 7));
        assertEquals(7, entity.get(PooledComp.class).value, "params 覆盖注入（复用实例同样成立");
        entity.destroy();
        assertThrows(IllegalStateException.class, () -> entity.get(PooledComp.class),
                "销毁实体组件访问仍被守卫拦截");
    }
}
