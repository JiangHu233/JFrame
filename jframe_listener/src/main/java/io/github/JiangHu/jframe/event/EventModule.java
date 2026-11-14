package io.github.JiangHu.jframe.event;

import io.github.JiangHu.jframe.core.module.JFrameModule;
import io.github.JiangHu.jframe.core.module.ModuleContext;
import io.github.JiangHu.jframe.event.routing.HandlerRegistry;

import java.util.Set;

/**
 * 事件模块工厂 — 一对一替代原 {@code EventSpringConfig} + {@code event-spring.xml}。
 * <p>
 * 装配 DAG（与原 XML 等价）：EventEngine → HandlerRegistry(engine) → EventAPI(registry, engine)。
 * EventEngine 为内部 Bean（PluginAware 绑定但不暴露）；EventAPI 为公开服务。
 */
public class EventModule implements JFrameModule {

    @Override
    public String id() {
        return "event";
    }

    @Override
    public Set<Class<?>> provides() {
        return Set.of(EventAPI.class);
    }

    @Override
    public void load(ModuleContext ctx) {
        EventEngine engine = new EventEngine();
        ctx.bind(engine);
        HandlerRegistry registry = new HandlerRegistry(engine);
        ctx.register(EventAPI.class, new EventAPI(registry, engine));
    }
}
