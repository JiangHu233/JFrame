package io.github.JiangHu.jframe.engine.ecs.fixture;

import io.github.JiangHu.jframe.engine.ecs.annotation.EcsComponent;
import io.github.JiangHu.jframe.engine.ecs.annotation.OnEcsEvent;
import io.github.JiangHu.jframe.engine.ecs.entity.ComponentBase;
import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试组件夹具 — 计数生命周期、记录事件回调、内联守卫、可注入字段。
 */
public final class TestComponents {

    private TestComponents() {
    }

    /** 生命周期回调记录（静态共享，便于断言顺序） */
    public static final List<String> LOG = new ArrayList<>();

    /**
     * 清空日志。
     */
    public static void reset() {
        LOG.clear();
    }

    /** 计数组件：参数注入 + 挂载/卸载顺序观测 */
    @EcsComponent("counter")
    public static class CounterComp extends ComponentBase {
        /** 可注入参数（params.value / params.name） */
        public int value;
        public String name = "default";

        @Override
        public void onMount() {
            LOG.add("mount:counter:" + name + ":" + value + "@" + host().id());
        }

        @Override
        public void onUnmount() {
            LOG.add("unmount:counter:" + name);
        }
    }

    /** 计数组件 B（与 A 区分挂载顺序用） */
    @EcsComponent("counter-b")
    public static class CounterBComp extends ComponentBase {
        @Override
        public void onMount() {
            LOG.add("mount:counter-b");
        }

        @Override
        public void onUnmount() {
            LOG.add("unmount:counter-b");
        }
    }

    /** onMount 抛异常组件（回滚验证） */
    @EcsComponent("throwing")
    public static class ThrowingComp extends ComponentBase {
        @Override
        public void onMount() {
            LOG.add("mount:throwing");
            throw new IllegalStateException("boom");
        }

        @Override
        public void onUnmount() {
            LOG.add("unmount:throwing");
        }
    }

    /** 事件组件：多 handler 变体（普通 / 内联守卫 / ignoreCancelled / 抛异常） */
    @EcsComponent("eventful")
    public static class EventfulComp extends ComponentBase {
        /** 守卫阈值（params.threshold 注入；方法内首行守卫用） */
        public int threshold = 4;

        /** 收到的事件记录 */
        public final List<Object> received = new ArrayList<>();

        /** 宿主记录（host 识别验证） */
        public EcsEntity seenHost;

        /** 普通处理器 */
        @OnEcsEvent
        public void onHit(FakeHitEvent event) {
            received.add(event);
            seenHost = host();
            LOG.add("event:hit:" + event.damage);
        }

        /** 内联守卫处理器（damage > threshold——方法级过滤 = 方法首行 if，无外部机制） */
        @OnEcsEvent
        public void onGuarded(FakeHitEvent event) {
            if (event.damage <= threshold) {
                return; // 守卫：直接读 this 字段
            }
            received.add("guarded:" + event.damage);
            LOG.add("event:guarded:" + event.damage);
        }

        /** 忽略已取消事件处理器 */
        @OnEcsEvent(ignoreCancelled = true)
        public void onCancellable(FakeCancelEvent event) {
            received.add("cancel");
            LOG.add("event:cancel");
        }

        /** 抛异常处理器（隔离验证） */
        @OnEcsEvent
        public void onBoom(FakeCancelEvent event) {
            throw new IllegalStateException("handler boom");
        }
    }

    /** 非法 @OnEcsEvent：双参数方法（注册期应拒绝） */
    @EcsComponent("bad-sig")
    public static class BadSignatureComp extends ComponentBase {
        /** 双参数处理器（注册期应拒绝） */
        @OnEcsEvent
        public void onHit(FakeHitEvent event, Object extra) {
            // 不可达
        }
    }
}
