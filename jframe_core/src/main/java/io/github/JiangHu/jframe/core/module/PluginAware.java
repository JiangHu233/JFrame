package io.github.JiangHu.jframe.core.module;

import cn.nukkit.plugin.Plugin;

/**
 * 模块插件绑定接口。
 * <p>
 * 实现此接口的 Spring Bean 会在<b>模块导入时自动接收插件实例</b>。
 * {@code JFrameMain} 在每次 {@code satisfyRequired} 导入新模块并刷新容器后，
 * 会扫描容器中所有 {@link PluginAware} Bean，调用 {@link #bindPlugin(Plugin)}
 * 将当前插件实例注入进去。
 *
 * <h3>设计动机</h3>
 * <p>
 * {@code jframe_main} 采用<b>选择性导入</b>策略：用户通过
 * {@code JFrameMain.getInstance().satisfyRequired(ConfigEnum.EVENT)} 按需加载模块。
 * 但某些模块的 Bean（例如 {@code EventService}）必须持有 Nukkit {@link Plugin}
 * 实例才能向 {@code PluginManager} 注册事件监听器。
 * 如果没有统一的绑定入口，用户每次导入模块后都得手动查找 Bean 并调用 setter，
 * 既容易遗漏，也违背了"按需导入"的简洁性。
 *
 * <h3>使用方式</h3>
 * <p>
 * 模块只需让需要插件实例的 Bean 实现本接口即可，无需任何手动注册代码：
 * <pre>{@code
 * public class EventService implements PluginAware {
 *     private Plugin plugin;
 *
 *     @Override
 *     public void bindPlugin(Plugin plugin) {
 *         this.plugin = plugin;
 *         // 刷新待注册的订阅 ...
 *     }
 * }
 * }</pre>
 *
 * <h3>绑定时机</h3>
 * <ul>
 *   <li>模块导入时：{@code JFrameMain.satisfyRequired} 刷新容器后自动绑定</li>
 *   <li>插件启用时：{@code JFrameMain.onEnable} 对已加载模块统一绑定一次</li>
 *   <li>由于 {@code refresh()} 会重建所有单例 Bean，每次导入后都会重新绑定，
 *       因此实现类无需担心实例被重建后丢失插件引用</li>
 * </ul>
 *
 * @see cn.nukkit.plugin.Plugin
 */
public interface PluginAware {

    /**
     * 绑定插件实例。
     * <p>
     * 由 {@code JFrameMain} 在模块导入或插件启用时自动调用。
     * 实现方应在此方法中保存插件引用，并完成依赖插件才能进行的初始化
     * （例如向 Nukkit 注册事件监听器、刷新暂存的订阅等）。
     *
     * @param plugin 当前 Nukkit 插件实例，不会为 null
     */
    void bindPlugin(Plugin plugin);
}
