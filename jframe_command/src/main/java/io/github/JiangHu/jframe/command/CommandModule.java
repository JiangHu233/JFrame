package io.github.JiangHu.jframe.command;

import io.github.JiangHu.jframe.core.module.JFrameModule;
import io.github.JiangHu.jframe.core.module.ModuleContext;
import io.github.JiangHu.jframe.command.routing.CommandRegistry;

import java.util.Set;

/**
 * 命令模块工厂 — 一对一替代原 {@code CommandSpringConfig} + {@code command-spring.xml}。
 * <p>
 * 装配 DAG（与原 XML 等价）：CommandRegistry → CommandEngine(registry) → CommandAPI(registry, engine)。
 */
public class CommandModule implements JFrameModule {

    @Override
    public String id() {
        return "command";
    }

    @Override
    public Set<Class<?>> provides() {
        return Set.of(CommandAPI.class);
    }

    @Override
    public void load(ModuleContext ctx) {
        CommandRegistry registry = new CommandRegistry();
        CommandEngine engine = new CommandEngine(registry);
        ctx.bind(engine);
        ctx.register(CommandAPI.class, new CommandAPI(registry, engine));
    }
}
