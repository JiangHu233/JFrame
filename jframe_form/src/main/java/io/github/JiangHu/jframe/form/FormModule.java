package io.github.JiangHu.jframe.form;

import io.github.JiangHu.jframe.core.module.JFrameModule;
import io.github.JiangHu.jframe.core.module.ModuleContext;

import java.util.Set;

/**
 * 表单模块工厂 — 一对一替代原 {@code FormSpringConfig} + {@code form-spring.xml}。
 * <p>
 * ViewAPI 自身实现 PluginAware（注册到 Nukkit 监听玩家退出自动清理），
 * 经 ctx.register 登记时自动绑定。
 */
public class FormModule implements JFrameModule {

    @Override
    public String id() {
        return "form";
    }

    @Override
    public Set<Class<?>> provides() {
        return Set.of(ViewAPI.class);
    }

    @Override
    public void load(ModuleContext ctx) {
        ctx.register(ViewAPI.class, new ViewAPI());
    }
}
