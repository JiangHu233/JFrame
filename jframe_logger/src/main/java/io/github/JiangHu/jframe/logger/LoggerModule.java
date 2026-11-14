package io.github.JiangHu.jframe.logger;

import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.core.module.JFrameModule;
import io.github.JiangHu.jframe.core.module.ModuleContext;
import io.github.JiangHu.jframe.logger.core.LogStore;
import io.github.JiangHu.jframe.logger.core.PersistSink;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 统一日志模块工厂。
 * <p>
 * 装配 DAG：{@code LoggerConfig → LogStore →（可选 PersistSink）→ LoggerAPI(WebRuntime)}。
 * Web 展示端口<b>不随模块加载启动</b>——由 {@code /jlog start} 手动开启
 * （{@code JFrameMain.onCommand} 首次执行即懒加载本模块）。
 */
public class LoggerModule implements JFrameModule {

    private LoggerAPI api;

    @Override
    public String id() {
        return "logger";
    }

    @Override
    public Set<Class<?>> provides() {
        return Set.of(LoggerAPI.class);
    }

    @Override
    public void load(ModuleContext ctx) {
        Plugin plugin = ctx.plugin();
        Path dataFolder = plugin != null ? plugin.getDataFolder().toPath() : null;
        LoggerConfig config = plugin != null
                ? LoggerConfig.fromSection(plugin.getConfig().getSection("logger"), dataFolder)
                : LoggerConfig.defaults(null);

        AtomicReference<String> idCache = new AtomicReference<>();
        Supplier<String> ownServerId = () -> {
            String v = idCache.get();
            if (v == null) {
                idCache.compareAndSet(null, LoggerAPI.computeOwnServerId(config));
                v = idCache.get();
            }
            return v;
        };

        LogStore store = new LogStore(config.perSourceLimit(), config.globalLimit());

        PersistSink sink = null;
        if (config.persist() && dataFolder != null) {
            sink = new PersistSink(dataFolder.resolve("logs"), ownServerId);
            store.addListener(sink);
        }

        this.api = new LoggerAPI(store, config, sink, ownServerId);
        ctx.register(LoggerAPI.class, api);
    }

    @Override
    public int shutdownOrder() {
        return 900;
    }

    @Override
    public void shutdown(ModuleContext ctx) {
        if (api != null) {
            api.shutdownInternal();
        }
    }
}
