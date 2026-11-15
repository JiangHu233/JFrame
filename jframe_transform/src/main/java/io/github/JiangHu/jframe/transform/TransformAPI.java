package io.github.JiangHu.jframe.transform;

import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.core.classloader.PluginClassLoaderFactory;
import io.github.JiangHu.jframe.core.module.ForPlugin;
import io.github.JiangHu.jframe.transform.agent.AttachSource;
import io.github.JiangHu.jframe.transform.registry.HookRegistry;

/**
 * 动态类增强门面（模块公开服务，懒加载触发点）。
 * <p>
 * 典型接入（业务插件 {@code plugin.yml} 声明 {@code depend: [JFrame]}）：
 * <pre>{@code
 * TransformPluginScope transform = jframe.getTransformAPI().forPlugin(this);
 * transform.scan("com.example.myplugin.hook");          // 注解声明式（推荐）
 * // 或命令式：
 * transform.hook("cn.nukkit.level.Level", "setRaining", new MyHook());
 * }</pre>
 *
 * @see TransformPluginScope
 * @see HookHandle
 */
public class TransformAPI implements ForPlugin<TransformPluginScope> {

    private final HookRegistry registry;

    /**
     * 构造门面（模块 load 装配）。
     *
     * @param registry 注册表
     */
    public TransformAPI(HookRegistry registry) {
        this.registry = registry;
    }

    /**
     * agent 是否可用（首次调用会尝试自附加，失败吞异常返回 false）。
     * <p>
     * JDK 21+ 自附加成功时控制台会打一条 JVM 警告，属预期、无害。
     *
     * @return true 可用
     */
    public boolean isAvailable() {
        return registry.isAvailable();
    }

    /**
     * Instrumentation 获取途径（{@code PREMAIN} / {@code SELF_ATTACH} / {@code NONE}）。
     *
     * @return 途径
     */
    public AttachSource attachSource() {
        return registry.attachSource();
    }

    /**
     * 绑定插件实例，返回该插件的注册作用域。
     *
     * @param plugin 插件实例
     * @return 作用域
     */
    @Override
    public TransformPluginScope forPlugin(Plugin plugin) {
        return new TransformPluginScope(registry, plugin.getName(),
                PluginClassLoaderFactory.getClassLoader(plugin));
    }

    /**
     * 按插件名绑定，返回该插件的注册作用域。
     *
     * @param pluginName 插件名（需与 plugin.yml 一致）
     * @return 作用域
     */
    @Override
    public TransformPluginScope forPlugin(String pluginName) {
        return new TransformPluginScope(registry, pluginName,
                PluginClassLoaderFactory.getClassLoader(pluginName));
    }

    /**
     * 内部：注册表（供示例/诊断）。
     *
     * @return 注册表
     */
    HookRegistry registry() {
        return registry;
    }
}
