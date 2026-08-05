package io.github.JiangHu.jframe.core.classloader;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 插件类加载器工厂——从 Nukkit {@link PluginManager} 获取插件的 {@link ClassLoader}。
 *
 * <h3>设计动机</h3>
 * <p>Nukkit 中每个插件由独立的 {@code PluginClassLoader} 加载，互相隔离。
 * 当一个插件想加载「另一个插件 jar 内的资源」（如模板、配置、语言文件）时，
 * 必须拿到对方的类加载器。本工具类封装了「插件名/实例 → 类加载器」的查找逻辑，
 * 配合 {@link CompositeClassLoader} 即可跨插件加载资源。
 *
 * <h3>获取类加载器的原理</h3>
 * <p>插件的类都由其 {@code PluginClassLoader} 加载，因此
 * {@code plugin.getClass().getClassLoader()} 返回的就是该插件的类加载器。
 * 这与 {@code ExamplePlugin} 中 {@code scan(getClass().getClassLoader(), ...)}
 * 扫描自身 jar 内类的做法一致。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 1. 获取单个插件的类加载器
 * ClassLoader loader = PluginClassLoaderFactory.getClassLoader("MyAddon");
 *
 * // 2. 聚合多个插件的类加载器，跨插件加载资源
 * CompositeClassLoader multi = new CompositeClassLoader(
 *     PluginClassLoaderFactory.getClassLoader("JFrame"),
 *     PluginClassLoaderFactory.getClassLoader("MyAddon")
 * );
 * InputStream tpl = multi.getResourceAsStream("templates/main.xml");
 *
 * // 3. 聚合所有已加载插件
 * CompositeClassLoader all = PluginClassLoaderFactory.compositeOfAllPlugins();
 * }</pre>
 *
 * <h3>调用时机</h3>
 * <p>本类依赖 {@link Server#getInstance()}，只能在服务端启动后（如 {@code onEnable}）
 * 调用。在 Spring 容器启动阶段 Server 可能尚未就绪。
 *
 * @see CompositeClassLoader 聚合多个类加载器
 */
public final class PluginClassLoaderFactory {

    private PluginClassLoaderFactory() {
    }

    /**
     * 按插件名获取类加载器。
     *
     * @param pluginName 插件名称（区分大小写，需与 plugin.yml 中一致）
     * @return 该插件的类加载器
     * @throws IllegalStateException  Server 尚未初始化时抛出
     * @throws IllegalArgumentException 插件不存在时抛出
     */
    public static ClassLoader getClassLoader(String pluginName) {
        Server server = requireServer();
        Plugin plugin = server.getPluginManager().getPlugin(pluginName);
        if (plugin == null) {
            throw new IllegalArgumentException("插件不存在: " + pluginName);
        }
        return plugin.getClass().getClassLoader();
    }

    /**
     * 从插件实例获取类加载器。
     *
     * @param plugin 插件实例（不能为 {@code null}）
     * @return 该插件的类加载器
     */
    public static ClassLoader getClassLoader(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin 不能为 null");
        return plugin.getClass().getClassLoader();
    }

    /**
     * 获取当前所有已加载插件的类加载器映射（保持插件加载顺序）。
     *
     * @return 插件名 → 类加载器；Server 未就绪时返回空 Map
     */
    public static Map<String, ClassLoader> getAllPluginClassLoaders() {
        Server server = Server.getInstance();
        if (server == null) {
            return Map.of();
        }
        Map<String, ClassLoader> result = new LinkedHashMap<>();
        for (Plugin plugin : server.getPluginManager().getPlugins().values()) {
            result.put(plugin.getName(), plugin.getClass().getClassLoader());
        }
        return result;
    }

    /**
     * 创建聚合了指定多个插件的 {@link CompositeClassLoader}。
     *
     * @param pluginNames 插件名称列表（按查找优先级排列）
     * @return 聚合类加载器
     */
    public static CompositeClassLoader compositeOf(String... pluginNames) {
        if (pluginNames == null || pluginNames.length == 0) {
            return new CompositeClassLoader();
        }
        ClassLoader[] loaders = new ClassLoader[pluginNames.length];
        for (int i = 0; i < pluginNames.length; i++) {
            loaders[i] = getClassLoader(pluginNames[i]);
        }
        return new CompositeClassLoader(loaders);
    }

    /**
     * 创建聚合了<b>所有已加载插件</b>的 {@link CompositeClassLoader}。
     * <p>适合「从任意插件加载资源」的兜底场景。
     *
     * @return 聚合所有插件的类加载器；Server 未就绪时返回空聚合
     */
    public static CompositeClassLoader compositeOfAllPlugins() {
        Server server = Server.getInstance();
        if (server == null) {
            return new CompositeClassLoader();
        }
        return new CompositeClassLoader(
                getAllPluginClassLoaders().values().toArray(new ClassLoader[0])
        );
    }

    private static Server requireServer() {
        Server server = Server.getInstance();
        if (server == null) {
            throw new IllegalStateException("Nukkit Server 尚未初始化，请在服务端启动后（如 onEnable）调用");
        }
        return server;
    }
}
