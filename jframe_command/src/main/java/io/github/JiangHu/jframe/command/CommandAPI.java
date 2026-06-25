package io.github.JiangHu.jframe.command;

import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.command.routing.CommandRegistry;
import io.github.JiangHu.jframe.command.scan.CommandScanner;

import java.util.List;

/**
 * 命令服务（<b>面向用户的统一入口</b>）：暴露命令系统的全部公开 API。
 * <p>
 * 本类是一个轻量门面（facade），自身不持有业务逻辑，仅做<b>委托转发</b>：
 * <ul>
 *   <li>{@code register/unregister} → 委托给 {@link CommandRegistry}（路由引擎），
 *       并触发 {@link CommandEngine#syncRoots} 同步新根命令到 Nukkit</li>
 *   <li>{@code scan} → 委托给 {@link CommandScanner}（包扫描）</li>
 *   <li>{@code bindPlugin} → 转发给 {@link CommandEngine}</li>
 * </ul>
 *
 * <h3>架构定位（单向 DAG）</h3>
 * <pre>
 *   CommandRegistry（无依赖）
 *       ↑
 *   CommandEngine（依赖 Registry）
 *       ↑
 *   CommandAPI（依赖 Registry + Engine）  ← 本类
 * </pre>
 * 三者均可使用构造器注入，Spring 按 Registry → Engine → Service 顺序创建。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 注册单个控制器
 * commandService.register(HomeController.class);
 *
 * // 包扫描注册整个 command 包下的所有 @CommandController 类
 * commandService.scan("com.myplugin.command");
 * }</pre>
 *
 * @see CommandEngine
 * @see CommandRegistry
 */
public class CommandAPI {

    /** 路由引擎：register/unregister 的实际执行者 */
    private final CommandRegistry registry;

    /** Nukkit 桥接引擎：根命令同步 + 分发 */
    private final CommandEngine engine;

    /**
     * 构造命令服务。
     * <p>
     * 通过 Spring 构造器注入。创建顺序由依赖关系保证：
     * CommandRegistry（无依赖）→ CommandEngine（依赖 Registry）→ CommandAPI（依赖两者）。
     *
     * @param registry 路由引擎
     * @param engine   Nukkit 桥接引擎
     */
    public CommandAPI(CommandRegistry registry, CommandEngine engine) {
        this.registry = registry;
        this.engine = engine;
    }

    /**
     * 注册命令控制器。
     * <p>
     * 扫描类中的 {@link io.github.JiangHu.jframe.command.annotation.CommandMapping @CommandMapping}
     * 方法，为每个方法构建路由（含路径模式与参数绑定策略）。注册后自动同步新根命令到 Nukkit。
     *
     * @param controllerClass 控制器类
     * @param <T>             控制器类型
     */
    public <T> void register(Class<T> controllerClass) {
        registry.register(controllerClass);
        engine.syncRoots();
    }

    /**
     * 注销整个控制器的所有路由。
     *
     * @param controllerClass 控制器类
     */
    public void unregister(Class<?> controllerClass) {
        registry.unregister(controllerClass);
    }

    /**
     * 包扫描注册（便捷方法）。
     * <p>
     * 扫描指定基础包（含子包）下所有标注了
     * {@link io.github.JiangHu.jframe.command.annotation.CommandController @CommandController}
     * 的类，自动调用 {@link #register} 注册。类似 Spring 的 {@code @ComponentScan}。
     * <p>
     * 使用线程上下文类加载器（TCCL），调用方需确保 TCCL 指向插件类加载器。
     *
     * @param basePackages 要扫描的基础包名（可变参数）
     * @return 实际注册成功的控制器类列表
     * @see CommandScanner
     */
    public List<Class<?>> scan(String... basePackages) {
        return new CommandScanner(this).scan(basePackages);
    }

    /**
     * 包扫描注册（指定类加载器）。
     *
     * @param classLoader  类加载器
     * @param basePackages 要扫描的基础包名（可变参数）
     * @return 实际注册成功的控制器类列表
     */
    public List<Class<?>> scan(ClassLoader classLoader, String... basePackages) {
        return new CommandScanner(this).scan(classLoader, basePackages);
    }

    /**
     * 绑定关联插件实例，并刷新所有待注册的根命令。
     * <p>
     * 转发给 {@link CommandEngine#bindPlugin(Plugin)}。
     * 当通过 {@code JFrameMain} 使用时，框架会自动扫描 {@code PluginAware} Bean
     * （即 {@link CommandEngine}）并调用其 {@code bindPlugin}，无需手动调用。
     *
     * @param plugin 插件实例
     */
    public void bindPlugin(Plugin plugin) {
        engine.bindPlugin(plugin);
    }

    /**
     * 获取关联的插件实例。
     *
     * @return 插件实例，或 null（尚未绑定）
     */
    public Plugin getPlugin() {
        return engine.getPlugin();
    }
}
