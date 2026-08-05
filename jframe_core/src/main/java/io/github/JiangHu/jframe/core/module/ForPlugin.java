package io.github.JiangHu.jframe.core.module;

import cn.nukkit.plugin.Plugin;

/**
 * 插件绑定代理接口 — 返回一个绑定了指定插件上下文（ClassLoader）的<b>作用域对象</b>。
 *
 * <h3>设计动机</h3>
 * <p>
 * Nukkit 中每个插件由独立的 {@code PluginClassLoader} 加载，互相隔离。
 * 当 JFrame 的模块（command / event / template / scoreboard）需要扫描或加载
 * <b>调用方插件 jar 内</b>的类 / 资源时，必须使用调用方插件的 ClassLoader。
 * <p>
 * 本接口提供统一的 {@code forPlugin} 契约：实现方返回一个<b>同模块的作用域对象</b>
 * （Scope），该对象内部绑定了指定插件的 ClassLoader，后续操作自动在正确的类路径下执行。
 *
 * <h3>与 {@link PluginAware} 的区别</h3>
 * <ul>
 *   <li>{@code PluginAware} — <b>被动</b>接收插件实例，由 {@code JFrameMain} 在模块导入时自动注入</li>
 *   <li>{@code ForPlugin} — <b>主动</b>绑定插件，由调用方按需获取作用域代理</li>
 * </ul>
 *
 * <h3>作用域对象（Scope）的设计</h3>
 * <p>
 * {@code forPlugin} 返回的不是自身类型，而是<b>专门的 Scope 类</b>。
 * Scope 只暴露需要插件 ClassLoader 的方法（如 scan / loadTemplate），
 * 不暴露无关方法（如 register / render），从而：
 * <ul>
 *   <li>API 精确引导 — IDE 自动补全只显示相关方法</li>
 *   <li>防止误用 — 在 Scope 上调用 register 会被编译器拒绝</li>
 *   <li>不污染原类 — API 类无需新增 ClassLoader 字段</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 命令：扫描调用方插件包下的 @CommandController
 * commandAPI.forPlugin(this).scan("com.myplugin.command");
 *
 * // 事件：扫描调用方插件包下的 @Wrapper
 * eventAPI.forPlugin(this).scan("com.myplugin.event");
 *
 * // 模板：从调用方插件 jar 加载模板
 * engine.forPlugin(this).getTemplate("main");
 *
 * // 记分板：从调用方插件 jar 加载记分板模板
 * scoreboardAPI.forPlugin(this).loadTemplate("hud");
 * }</pre>
 *
 * <h3>线程安全</h3>
 * <p>
 * 每次 {@code forPlugin} 调用都创建<b>新的 Scope 实例</b>，独立持有 ClassLoader，
 * 互不干扰。原 API 对象（Spring 单例）保持无状态，不受影响。
 *
 * @param <T> 作用域对象类型（如 CommandPluginScope、TemplatePluginScope）
 * @see PluginAware
 */
public interface ForPlugin<T> {

    /**
     * 绑定指定插件实例，返回绑定了该插件 ClassLoader 的作用域对象。
     *
     * @param plugin 插件实例（不能为 {@code null}）
     * @return 绑定了该插件上下文的作用域对象
     */
    T forPlugin(Plugin plugin);

    /**
     * 按插件名绑定，返回绑定了该插件 ClassLoader 的作用域对象。
     *
     * @param pluginName 插件名称（需与 plugin.yml 中一致）
     * @return 绑定了该插件上下文的作用域对象
     */
    T forPlugin(String pluginName);
}
