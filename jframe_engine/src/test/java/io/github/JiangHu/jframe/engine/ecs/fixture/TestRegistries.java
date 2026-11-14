package io.github.JiangHu.jframe.engine.ecs.fixture;

import io.github.JiangHu.jframe.engine.ecs.annotation.EcsDespawner;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsResolver;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsSelector;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsSpawner;
import io.github.JiangHu.jframe.engine.ecs.despawner.DespawnContext;
import io.github.JiangHu.jframe.engine.ecs.despawner.Despawner;
import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;
import io.github.JiangHu.jframe.engine.ecs.resolver.EcsEventResolver;
import io.github.JiangHu.jframe.engine.ecs.selector.EntitySelector;
import io.github.JiangHu.jframe.engine.ecs.spawner.Spawner;
import io.github.JiangHu.jframe.engine.ecs.spawner.SpawnContext;
import cn.nukkit.event.Event;

import java.util.List;

/**
 * 测试选择器 / 生成器 / 回收器 / 解析器夹具。
 */
public final class TestRegistries {

    private TestRegistries() {
    }

    /** 恒真选择器（全部命中） */
    @EcsSelector("always")
    public static class AlwaysSelector implements EntitySelector {
        @Override
        public boolean matches(EcsEntity entity) {
            return true;
        }
    }

    /** 恒假选择器（全不命中） */
    @EcsSelector("never")
    public static class NeverSelector implements EntitySelector {
        @Override
        public boolean matches(EcsEntity entity) {
            return false;
        }
    }

    /** 返回 null 的生成器（纯虚拟） */
    @EcsSpawner("null-spawner")
    public static class NullSpawner implements Spawner {
        @Override
        public Object spawn(SpawnContext context) {
            return null;
        }
    }

    /** 带参数的生成器：记录调用与注入结果 */
    @EcsSpawner("recording-spawner")
    public static class RecordingSpawner implements Spawner {
        /** 可注入参数（params.count） */
        public int count = -1;

        /** 最近一次调用上下文定义名 */
        public String lastDefinition;

        @Override
        public Object spawn(SpawnContext context) {
            lastDefinition = context.definitionName();
            return null;
        }
    }

    /** POJO 产物生成器：返回固定非 null 产物（任意对象代理验证） */
    @EcsSpawner("pojo-spawner")
    public static class PojoSpawner implements Spawner {
        /** 产物实例（每次加载定义独立） */
        public final Object product = new Object();

        @Override
        public Object spawn(SpawnContext context) {
            return product;
        }
    }

    /** 记录型回收器：记录 definitionName / product 供断言 */
    @EcsDespawner("recording-despawner")
    public static class RecordingDespawner implements Despawner {
        /** 可注入参数（params.count） */
        public int count = -1;

        /** 最近一次回收的上下文 */
        public DespawnContext last;

        @Override
        public void despawn(DespawnContext context) {
            last = context;
            TestComponents.LOG.add("despawn:" + context.definitionName());
        }
    }

    /** 抛异常回收器（隔离验证） */
    @EcsDespawner("throwing-despawner")
    public static class ThrowingDespawner implements Despawner {
        @Override
        public void despawn(DespawnContext context) {
            throw new IllegalStateException("despawner boom");
        }
    }

    /** FakeHitEvent → 实体候选（宽匹配父类型声明） */
    @EcsResolver("fake-hit")
    public static class FakeHitResolver implements EcsEventResolver {
        /** 每次分发返回的候选 */
        public List<Object> candidates = List.of();

        @Override
        public Class<? extends Event> eventType() {
            return FakeEvent.class;
        }

        @Override
        public List<Object> extractHosts(Event event) {
            return candidates;
        }
    }

    /** FakeCancelEvent → 实体候选（精确类型声明 + 固定候选） */
    @EcsResolver("fake-cancel")
    public static class FakeCancelResolver implements EcsEventResolver {
        /** 固定候选 */
        public EcsEntity target;

        @Override
        public Class<? extends Event> eventType() {
            return FakeCancelEvent.class;
        }

        @Override
        public List<Object> extractHosts(Event event) {
            return target == null ? List.of() : List.of(target);
        }
    }

    /** 与 fake-hit 相同候选的第二个解析器（同事件去重验证） */
    @EcsResolver("fake-hit-dup")
    public static class DupHitResolver implements EcsEventResolver {
        /** 与主解析器共享的候选 */
        public List<Object> candidates = List.of();

        @Override
        public Class<? extends Event> eventType() {
            return FakeEvent.class;
        }

        @Override
        public List<Object> extractHosts(Event event) {
            return candidates;
        }
    }
}
