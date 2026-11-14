package io.github.JiangHu.jframe.scoreboard;

import io.github.JiangHu.jframe.content_template.TemplateEngine;
import io.github.JiangHu.jframe.core.module.JFrameModule;
import io.github.JiangHu.jframe.core.module.ModuleContext;

import java.util.Set;

/**
 * 计分板模块工厂 — 一对一替代原 {@code ScoreboardSpringConfig} + {@code scoreboard-spring.xml}。
 * <p>
 * 装配 DAG（与原 XML 等价）：TemplateEngine（本模块独立实例，不与 title 共享）→
 * ScoreboardManager(engine) → ScoreboardAPI(manager, engine)。
 */
public class ScoreboardModule implements JFrameModule {

    private ScoreboardAPI api;

    @Override
    public String id() {
        return "scoreboard";
    }

    @Override
    public Set<Class<?>> provides() {
        return Set.of(ScoreboardAPI.class);
    }

    @Override
    public void load(ModuleContext ctx) {
        TemplateEngine templateEngine = new TemplateEngine();
        ScoreboardManager manager = new ScoreboardManager(templateEngine);
        api = new ScoreboardAPI(manager, templateEngine);
        ctx.register(ScoreboardAPI.class, api);
    }

    @Override
    public int shutdownOrder() {
        return 40;
    }

    @Override
    public void shutdown(ModuleContext ctx) {
        if (api != null) {
            api.hideAll();
        }
    }
}
