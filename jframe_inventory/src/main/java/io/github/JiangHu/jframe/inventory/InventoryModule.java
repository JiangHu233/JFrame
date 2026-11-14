package io.github.JiangHu.jframe.inventory;

import io.github.JiangHu.jframe.core.module.JFrameModule;
import io.github.JiangHu.jframe.core.module.ModuleContext;
import io.github.JiangHu.jframe.inventory.ui.InventoryAPI;
import io.github.JiangHu.jframe.inventory.ui.manager.InventoryManager;

import java.util.Set;

/**
 * 箱子界面模块工厂 — 一对一替代原 {@code InventorySpringConfig} + {@code inventory-spring.xml}。
 * <p>
 * 装配 DAG（与原 XML 等价）：InventoryManager → InventoryAPI(manager)。
 * InventoryAPI 实现 PluginAware（登记时自动绑定，注册 Nukkit 库存事件监听）。
 */
public class InventoryModule implements JFrameModule {

    private InventoryAPI api;

    @Override
    public String id() {
        return "inventory";
    }

    @Override
    public Set<Class<?>> provides() {
        return Set.of(InventoryAPI.class);
    }

    @Override
    public void load(ModuleContext ctx) {
        InventoryManager manager = new InventoryManager();
        api = new InventoryAPI(manager);
        ctx.register(InventoryAPI.class, api);
    }

    @Override
    public int shutdownOrder() {
        return 20;
    }

    @Override
    public void shutdown(ModuleContext ctx) {
        if (api != null) {
            api.closeAll();
        }
    }
}
