package io.github.JiangHu.jframe.data;

import io.github.JiangHu.jframe.core.module.JFrameModule;
import io.github.JiangHu.jframe.core.module.ModuleContext;
import io.github.JiangHu.jframe.data.core.meta.MetadataCache;

import java.util.Set;

/**
 * 数据持久化模块工厂 — 一对一替代原 {@code DataSpringConfig} + {@code data-spring.xml}。
 * <p>
 * 装配 DAG（与原 XML 等价）：MetadataCache → DataSaver(cache)。
 * DataSaver 实现 PluginAware（登记时自动绑定，rootDir 指向宿主插件数据目录）。
 */
public class DataModule implements JFrameModule {

    @Override
    public String id() {
        return "data";
    }

    @Override
    public Set<Class<?>> provides() {
        return Set.of(DataSaver.class);
    }

    @Override
    public void load(ModuleContext ctx) {
        MetadataCache cache = new MetadataCache();
        ctx.register(DataSaver.class, new DataSaver(cache));
    }
}
