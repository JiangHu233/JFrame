package io.github.JiangHu.jframe.floatingtext;

import io.github.JiangHu.jframe.core.module.JFrameModule;
import io.github.JiangHu.jframe.core.module.ModuleContext;
import io.github.JiangHu.jframe.floatingtext.nukkit.DefaultViewerDirectory;
import io.github.JiangHu.jframe.floatingtext.nukkit.FloatingTextTicker;
import io.github.JiangHu.jframe.floatingtext.nukkit.ViewerResyncListener;
import io.github.JiangHu.jframe.floatingtext.render.VirtualEntityRenderer;

import java.util.Set;

/**
 * 浮空字模块工厂 — 一对一替代原 {@code FloatingTextSpringConfig} + {@code floatingtext-spring.xml}。
 * <p>
 * 装配 DAG（与原 XML 等价）：VirtualEntityRenderer + DefaultViewerDirectory →
 * FloatingTextManager(renderer, directory) → FloatingTextTicker(manager)（PluginAware，
 * 绑定后自动启动主线程节拍）+ ViewerResyncListener(manager)（PluginAware，观众对账）→
 * FloatingTextAPI(manager)。
 * <p>
 * 关闭顺序 = 15（引擎之后）：先停节拍器再清场（向在场玩家发移除包），
 * 替代原 {@code JFrameMain.onDisable} 直接取内部 Bean 的写法。
 */
public class FloatingTextModule implements JFrameModule {

    private FloatingTextTicker ticker;
    private FloatingTextAPI api;

    @Override
    public String id() {
        return "floatingtext";
    }

    @Override
    public Set<Class<?>> provides() {
        return Set.of(FloatingTextAPI.class);
    }

    @Override
    public void load(ModuleContext ctx) {
        VirtualEntityRenderer renderer = new VirtualEntityRenderer();
        DefaultViewerDirectory directory = new DefaultViewerDirectory();
        FloatingTextManager manager = new FloatingTextManager(renderer, directory);

        ticker = new FloatingTextTicker(manager);
        ctx.bind(ticker);

        ctx.bind(new ViewerResyncListener(manager));

        api = new FloatingTextAPI(manager);
        ctx.register(FloatingTextAPI.class, api);
    }

    @Override
    public int shutdownOrder() {
        return 15;
    }

    @Override
    public void shutdown(ModuleContext ctx) {
        if (ticker != null) {
            ticker.stop();
        }
        if (api != null) {
            api.removeAll();
        }
    }
}
