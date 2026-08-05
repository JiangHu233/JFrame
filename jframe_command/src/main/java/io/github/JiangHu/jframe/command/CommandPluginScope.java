package io.github.JiangHu.jframe.command;

import io.github.JiangHu.jframe.command.annotation.CommandController;
import io.github.JiangHu.jframe.core.JFrameLog;
import io.github.JiangHu.jframe.core.scan.AnnotatedClassScanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 命令插件作用域 — 绑定了指定插件 ClassLoader 的命令扫描代理。
 *
 * <p>由 {@link CommandAPI#forPlugin} 创建，封装"使用指定插件类路径扫描命令控制器"的逻辑。
 * 本类只暴露 {@link #scan} 方法，不暴露 register / unregister 等无关方法。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 扫描当前插件 jar 内的命令控制器
 * commandAPI.forPlugin(this).scan("com.myplugin.command");
 *
 * // 扫描其他插件的命令控制器
 * commandAPI.forPlugin("OtherPlugin").scan("com.otherplugin.cmd");
 * }</pre>
 *
 * <h3>线程安全</h3>
 * 每次调用 {@link CommandAPI#forPlugin} 都创建新的 Scope 实例，
 * 独立持有 ClassLoader，互不干扰。扫描结果统一注册到共享的 {@code CommandRegistry}。
 *
 * @see CommandAPI#forPlugin
 * @see AnnotatedClassScanner
 */
public class CommandPluginScope {

    private static final String TAG = "CommandScanner";

    /** 命令服务：扫描到的类通过它注册 */
    private final CommandAPI api;

    /** 绑定的插件类加载器 */
    private final ClassLoader classLoader;

    /**
     * 构造作用域（包级可见，由 {@link CommandAPI#forPlugin} 创建）。
     *
     * @param api         命令服务
     * @param classLoader 插件类加载器
     */
    CommandPluginScope(CommandAPI api, ClassLoader classLoader) {
        this.api = api;
        this.classLoader = classLoader;
    }

    /**
     * 扫描指定基础包（含子包）下所有 {@code @CommandController} 类并注册。
     *
     * @param basePackages 要扫描的基础包名（可变参数）
     * @return 实际注册成功的控制器类列表（保持发现顺序）
     */
    public List<Class<?>> scan(String... basePackages) {
        AnnotatedClassScanner scanner = new AnnotatedClassScanner(CommandController.class);
        Set<Class<?>> candidates = scanner.scan(classLoader, basePackages);

        List<Class<?>> registered = new ArrayList<>();
        for (Class<?> clazz : candidates) {
            try {
                api.register(clazz);
                registered.add(clazz);
                JFrameLog.info(TAG, "已注册命令控制器: " + clazz.getName());
            } catch (Throwable e) {
                JFrameLog.error(TAG, "注册命令控制器失败: " + clazz.getName(), e);
            }
        }

        JFrameLog.info(TAG, "扫描完成，共注册 " + registered.size() + " 个命令控制器。");
        return registered;
    }
}
