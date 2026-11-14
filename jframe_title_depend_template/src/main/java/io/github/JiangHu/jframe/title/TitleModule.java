package io.github.JiangHu.jframe.title;

import io.github.JiangHu.jframe.content_template.TemplateEngine;
import io.github.JiangHu.jframe.core.module.JFrameModule;
import io.github.JiangHu.jframe.core.module.ModuleContext;

import java.util.Set;

/**
 * 标题模块工厂 — 一对一替代原 {@code TitleSpringConfig} + {@code title-spring.xml}。
 * <p>
 * 装配 DAG（与原 XML 等价）：TemplateEngine（本模块独立实例，不与 scoreboard 共享）→
 * TitleManager(engine) → TitleAPI(manager, engine)。
 */
public class TitleModule implements JFrameModule {

    private TitleAPI api;

    @Override
    public String id() {
        return "title";
    }

    @Override
    public Set<Class<?>> provides() {
        return Set.of(TitleAPI.class);
    }

    @Override
    public void load(ModuleContext ctx) {
        TemplateEngine templateEngine = new TemplateEngine();
        TitleManager manager = new TitleManager(templateEngine);
        api = new TitleAPI(manager, templateEngine);
        ctx.register(TitleAPI.class, api);
    }

    @Override
    public int shutdownOrder() {
        return 41;
    }

    @Override
    public void shutdown(ModuleContext ctx) {
        if (api != null) {
            api.disposeAll();
        }
    }
}
