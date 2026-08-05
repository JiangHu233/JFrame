package io.github.JiangHu.jframe.event;

import io.github.JiangHu.jframe.core.JFrameLog;
import io.github.JiangHu.jframe.core.scan.AnnotatedClassScanner;
import io.github.JiangHu.jframe.event.annotation.Wrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 事件插件作用域 — 绑定了指定插件 ClassLoader 的事件扫描代理。
 *
 * <p>由 {@link EventAPI#forPlugin} 创建，封装"使用指定插件类路径扫描事件包装类"的逻辑。
 * 本类只暴露 {@link #scan} 方法，不暴露 register / unsubscribe 等无关方法。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 扫描当前插件 jar 内的事件包装类
 * eventAPI.forPlugin(this).scan("com.myplugin.event");
 *
 * // 扫描其他插件的事件包装类
 * eventAPI.forPlugin("OtherPlugin").scan("com.otherplugin.event");
 * }</pre>
 *
 * <h3>线程安全</h3>
 * 每次调用 {@link EventAPI#forPlugin} 都创建新的 Scope 实例，
 * 独立持有 ClassLoader，互不干扰。扫描结果统一注册到共享的 {@code HandlerRegistry}。
 *
 * @see EventAPI#forPlugin
 * @see AnnotatedClassScanner
 */
public class EventPluginScope {

    private static final String TAG = "WrapperScanner";

    /** 事件服务：扫描到的类通过它注册 */
    private final EventAPI api;

    /** 绑定的插件类加载器 */
    private final ClassLoader classLoader;

    /**
     * 构造作用域（包级可见，由 {@link EventAPI#forPlugin} 创建）。
     *
     * @param api         事件服务
     * @param classLoader 插件类加载器
     */
    EventPluginScope(EventAPI api, ClassLoader classLoader) {
        this.api = api;
        this.classLoader = classLoader;
    }

    /**
     * 扫描指定基础包（含子包）下所有 {@code @Wrapper} 类并注册。
     *
     * @param basePackages 要扫描的基础包名（可变参数）
     * @return 实际注册成功的包装类列表（保持发现顺序）
     */
    public List<Class<?>> scan(String... basePackages) {
        AnnotatedClassScanner scanner = new AnnotatedClassScanner(Wrapper.class);
        Set<Class<?>> candidates = scanner.scan(classLoader, basePackages);

        List<Class<?>> registered = new ArrayList<>();
        for (Class<?> clazz : candidates) {
            try {
                api.register(clazz);
                registered.add(clazz);
                JFrameLog.info(TAG, "已注册包装类: " + clazz.getName());
            } catch (Throwable e) {
                JFrameLog.error(TAG, "注册包装类失败: " + clazz.getName(), e);
            }
        }

        JFrameLog.info(TAG, "扫描完成，共注册 " + registered.size() + " 个包装类。");
        return registered;
    }
}
