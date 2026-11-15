package io.github.JiangHu.jframe.transform.lifecycle;

import cn.nukkit.Server;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.plugin.PluginDisableEvent;
import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.core.module.PluginAware;
import io.github.JiangHu.jframe.transform.registry.HookRegistry;
import io.github.JiangHu.jframe.transform.registry.TLog;

/**
 * 插件卸载监听器（设计方案 §3.6）——业务插件 disable 时自动 unhook：
 * ①链上摘除（分发即刻停止）②相关类无其他钩子时 retransform 还原原始字节码，
 * 防 {@code /reload} 后残留指向已销毁类加载器的回调。
 */
public final class PluginDisableListener implements PluginAware, Listener {

    private final HookRegistry registry;
    private Plugin plugin;
    private boolean active;

    /**
     * 构造监听器。
     *
     * @param registry 注册表
     */
    public PluginDisableListener(HookRegistry registry) {
        this.registry = registry;
    }

    /**
     * 绑定插件并注册事件（幂等）。
     *
     * @param plugin 插件
     */
    @Override
    public void bindPlugin(Plugin plugin) {
        this.plugin = plugin;
        try {
            activate();
        } catch (Exception e) {
            TLog.error("插件卸载监听器注册失败（自动 unhook 不可用，卸载需手动 unhookAll）", e);
        }
    }

    private synchronized void activate() {
        if (active || plugin == null || Server.getInstance() == null) {
            return;
        }
        cn.nukkit.plugin.EventExecutor executor = (listener, event) -> {
            if (event instanceof PluginDisableEvent disable) {
                Plugin disabled = disable.getPlugin();
                // 跳过 JFrame 自身：其清理由 TransformModule.shutdown 统一执行
                if (disabled != null && !disabled.getName().equals(plugin.getName())) {
                    registry.unhookPlugin(disabled.getName());
                }
            }
        };
        Server.getInstance().getPluginManager().registerEvent(
                PluginDisableEvent.class, this, EventPriority.NORMAL, executor, plugin, false);
        active = true;
    }
}
