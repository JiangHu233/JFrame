package io.github.JiangHu.jframe.core.module;

/**
 * 模块容器的插件作用域对象 — 绑定指定插件 ClassLoader 后仅暴露
 * {@link #scan(String...)}，供跨插件包扫描注册。
 * <p>
 * 由 {@link ModuleManager#forPlugin(Plugin)} /
 * {@link ModuleManager#forPlugin(String)} 创建；扫描产物注册进
 * <b>创建该 Scope 的容器</b>自己的注册表（隔离语义不变——扫别人的类，注册进自己的容器）。
 */
public final class ModulePluginScope {

    private final ModuleManager manager;
    private final ClassLoader classLoader;

    ModulePluginScope(ModuleManager manager, ClassLoader classLoader) {
        this.manager = manager;
        this.classLoader = classLoader;
    }

    /**
     * 以绑定插件的 ClassLoader 扫描基础包（含子包），登记
     * {@link io.github.JiangHu.jframe.core.module.annotation.JBean @JBean} 与
     * {@link io.github.JiangHu.jframe.core.module.annotation.JFactoryClass @JFactoryClass}
     * 目标（零实例化，保持懒加载）。
     *
     * @param basePackages 基础包名
     * @return 登记成功的类集合
     */
    public java.util.Set<Class<?>> scan(String... basePackages) {
        return manager.scan(classLoader, basePackages);
    }
}
