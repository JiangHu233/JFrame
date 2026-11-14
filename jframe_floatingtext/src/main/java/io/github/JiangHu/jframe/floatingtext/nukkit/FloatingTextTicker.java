package io.github.JiangHu.jframe.floatingtext.nukkit;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.scheduler.TaskHandler;
import io.github.JiangHu.jframe.core.module.PluginAware;
import io.github.JiangHu.jframe.floatingtext.FloatingTextLog;
import io.github.JiangHu.jframe.floatingtext.FloatingTextManager;

/**
 * 浮空字节拍器 — Nukkit 主线程 repeating task，每 tick 驱动 {@link FloatingTextManager#tick()}
 * （动画/寿命推进 + 周期对账）。
 *
 * <p>{@link #bindPlugin(Plugin)} 时<b>自动启动</b>（零配置）；stop 后可再次 start。
 */
public class FloatingTextTicker implements PluginAware {

    private final FloatingTextManager manager;
    private Plugin plugin;
    private TaskHandler task;

    /**
     * 构造节拍器。
     *
     * @param manager 管理器
     */
    public FloatingTextTicker(FloatingTextManager manager) {
        this.manager = manager;
    }

    /**
     * 绑定插件并自动启动调度。
     *
     * @param plugin 插件
     */
    @Override
    public void bindPlugin(Plugin plugin) {
        this.plugin = plugin;
        try {
            start();
        } catch (Exception e) {
            FloatingTextLog.error("浮空字节拍器启动失败", e);
        }
    }

    /**
     * 启动（幂等：已运行则忽略；服务器未就绪抛 ISE 由调用方决定时机）。
     */
    public synchronized void start() {
        if (task != null) {
            return;
        }
        if (plugin == null) {
            throw new IllegalStateException("尚未绑定插件，无法调度");
        }
        task = Server.getInstance().getScheduler()
                .scheduleRepeatingTask(plugin, this::safeTick, 1);
        FloatingTextLog.info("浮空字节拍器已启动");
    }

    /**
     * 停止调度（幂等）。
     */
    public synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
            FloatingTextLog.info("浮空字节拍器已停止");
        }
    }

    /**
     * 是否运行中。
     *
     * @return 运行 true
     */
    public synchronized boolean running() {
        return task != null;
    }

    private void safeTick() {
        try {
            manager.tick();
        } catch (Exception e) {
            FloatingTextLog.error("浮空字 tick 异常", e);
        }
    }
}
