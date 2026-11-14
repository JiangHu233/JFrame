package io.github.JiangHu.jframe.core.module;

import cn.nukkit.plugin.Plugin;

/**
 * 模块加载上下文 — {@link JFrameModule#load(ModuleContext)} 与
 * {@link JFrameModule#shutdown(ModuleContext)} 的回调入参。
 * <p>
 * 公开服务经 {@link #register} 登记（类型查找表 + Nukkit ServiceManager +
 * {@link PluginAware} 绑定）；私有 Bean 经 {@link #registerPrivate} 登记
 * （类型查找表 + PluginAware 绑定，不进 ServiceManager）；
 * 不入类型查找表的内部 Bean 用 {@link #bind} 仅做 PluginAware 绑定。
 *
 * @see JFrameModule
 * @see ModuleManager
 */
public interface ModuleContext {

    /**
     * 宿主插件实例。
     *
     * @return 构造 {@link ModuleManager} 时传入的宿主插件，单元测试等无插件环境可为 {@code null}
     */
    Plugin plugin();

    /**
     * 从容器按类型获取实例（懒加载单例，语义同 {@link ModuleManager#get(Class)}）。
     * <p>
     * 典型用法：{@code @JFactoryClass} 的 {@code @JBean} 方法体内
     * {@code ctx.get(A.class)} 显式拉取依赖——引用由工厂代码自己解决。
     *
     * @param type 注册过的类型
     * @param <T>  类型
     * @return 单例实例
     */
    <T> T get(Class<T> type);

    /**
     * 登记公开服务：进入类型查找表，插件启用后以宿主插件名义注册到 Nukkit
     * {@code ServiceManager}，实例为 {@link PluginAware} 时自动绑定宿主插件。
     *
     * @param apiType  服务类型（须 ∈ 该模块 {@link JFrameModule#provides()}）
     * @param instance 已构造完毕的实例（不能为 {@code null}）
     * @param <T>      类型
     * @return 入参 instance（链式便捷）
     */
    <T> T register(Class<T> apiType, T instance);

    /**
     * 登记私有 Bean：进入类型查找表并做 {@link PluginAware} 绑定，
     * 但<b>不</b>注册 ServiceManager（类型须 ∈ 该模块 {@link JFrameModule#provides()}）。
     *
     * @param type     类型
     * @param instance 已构造完毕的实例（不能为 {@code null}）
     * @param <T>      类型
     * @return 入参 instance
     */
    <T> T registerPrivate(Class<T> type, T instance);

    /**
     * 绑定内部 Bean：仅当对象实现 {@link PluginAware} 时绑定宿主插件，
     * 不进入类型查找表（如各模块的 Engine / Registry / Ticker 等非公开对象）。
     *
     * @param bean 内部 Bean（非 {@link PluginAware} 时为 no-op）
     */
    void bind(Object bean);
}
