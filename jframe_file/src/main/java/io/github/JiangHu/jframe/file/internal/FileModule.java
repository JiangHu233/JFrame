package io.github.JiangHu.jframe.file.internal;

import io.github.JiangHu.jframe.core.module.JFrameModule;
import io.github.JiangHu.jframe.core.module.ModuleContext;
import io.github.JiangHu.jframe.file.FileAPI;
import io.github.JiangHu.jframe.serialize.core.meta.MetadataCache;

import java.util.Set;

/**
 * 文件持久化模块工厂（模块 id {@code "file"}，原 {@code DataModule}）。
 * <p>
 * 装配 DAG：MetadataCache（serialize 内核）→ FileAPI(cache)。
 * FileAPI 实现 PluginAware（登记时自动绑定，rootDir 指向宿主插件数据目录）。
 */
public class FileModule implements JFrameModule {

    @Override
    public String id() {
        return "file";
    }

    @Override
    public Set<Class<?>> provides() {
        return Set.of(FileAPI.class);
    }

    @Override
    public void load(ModuleContext ctx) {
        MetadataCache cache = new MetadataCache();
        ctx.register(FileAPI.class, new FileAPI(cache));
    }
}
