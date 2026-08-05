package io.github.JiangHu.jframe.event.scan;

import io.github.JiangHu.jframe.core.JFrameLog;
import io.github.JiangHu.jframe.core.scan.AnnotatedClassScanner;
import io.github.JiangHu.jframe.event.EventAPI;
import io.github.JiangHu.jframe.event.annotation.Wrapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 包装类包扫描器 — 自动发现并注册标注了 {@link Wrapper} 的类。
 * <p>
 * 内部委托给 {@link AnnotatedClassScanner}（封装 Spring 的
 * {@code ClassPathScanningCandidateComponentProvider} 样板），
 * 给定一个或多个<b>基础包</b>，递归扫描其下（含子包）的所有 {@code .class} 文件，
 * 找出标注 {@code @Wrapper} 的具体类，交由 {@link EventAPI#register} 注册。
 *
 * <h3>类加载器策略</h3>
 * <p>
 * 默认使用<b>线程上下文类加载器</b>（TCCL），回退到本类的类加载器。
 * 在 Nukkit 插件中，调用方应在 {@code onEnable} 期间将 TCCL 切换为插件自身的类加载器
 * （参见示例 {@code ExamplePlugin}），扫描器即可正确发现插件 jar 内的包装类。
 * 也可通过 {@link #scan(ClassLoader, String...)} 显式指定类加载器。
 *
 * <h3>推荐方式</h3>
 * <p>
 * 更推荐使用 {@link EventAPI#forPlugin(cn.nukkit.plugin.Plugin)} 获取
 * {@link io.github.JiangHu.jframe.event.EventPluginScope}，
 * 无需手动管理类加载器：
 * <pre>{@code
 * eventAPI.forPlugin(this).scan("com.myplugin.event");
 * }</pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 方式一：通过 EventAPI 便捷方法（推荐）
 * eventAPI.scan("io.github.JiangHu.jframe.example.wrapper");
 *
 * // 方式二：直接使用 WrapperScanner
 * WrapperScanner scanner = new WrapperScanner(eventAPI);
 * List<Class<?>> registered = scanner.scan("com.myplugin.features");
 * }</pre>
 *
 * <h3>容错性</h3>
 * 单个类的注册失败（如缺少 {@code @KeyExtractor}）由 {@link EventAPI#register} 内部
 * 处理并记录，不会中断整体扫描。
 *
 * @see Wrapper
 * @see EventAPI#scan
 * @see EventAPI#register
 * @see AnnotatedClassScanner
 */
public class WrapperScanner {

    private static final String TAG = "WrapperScanner";

    /** 事件服务：扫描到的类通过它注册 */
    private final EventAPI eventAPI;

    /** 复用的注解扫描器（封装 Spring 样板） */
    private final AnnotatedClassScanner annotatedScanner;

    /**
     * 构造扫描器。
     *
     * @param eventAPI 事件服务（用于注册扫描到的包装类）
     */
    public WrapperScanner(EventAPI eventAPI) {
        this.eventAPI = eventAPI;
        this.annotatedScanner = new AnnotatedClassScanner(Wrapper.class);
    }

    /**
     * 扫描指定基础包（含子包）下所有 {@code @Wrapper} 类并注册。
     * <p>
     * 使用线程上下文类加载器（TCCL），回退到本类类加载器。
     *
     * @param basePackages 要扫描的基础包名（可变参数）
     * @return 实际注册成功的包装类列表（保持发现顺序）
     */
    public List<Class<?>> scan(String... basePackages) {
        return scan(AnnotatedClassScanner.resolveClassLoader(), basePackages);
    }

    /**
     * 扫描指定基础包（含子包）下所有 {@code @Wrapper} 类并注册。
     * <p>
     * 显式指定类加载器，适用于需要精确控制类加载来源的场景。
     *
     * @param classLoader  用于加载资源和类的类加载器
     * @param basePackages 要扫描的基础包名（可变参数）
     * @return 实际注册成功的包装类列表（保持发现顺序）
     */
    public List<Class<?>> scan(ClassLoader classLoader, String... basePackages) {
        List<Class<?>> registered = new ArrayList<>();
        if (basePackages == null || basePackages.length == 0) {
            return registered;
        }

        Set<Class<?>> candidates = annotatedScanner.scan(classLoader, basePackages);
        for (Class<?> clazz : candidates) {
            try {
                eventAPI.register(clazz);
                registered.add(clazz);
                JFrameLog.info(TAG, "已注册包装类: " + clazz.getName());
            } catch (Throwable e) {
                JFrameLog.error(TAG, "注册包装类失败: " + clazz.getName(), e);
            }
        }

        JFrameLog.info(TAG, "扫描完成，共注册 " + registered.size() + " 个包装类。");
        return registered;
    }

    /**
     * 仅扫描（不注册）：返回指定包下所有 {@code @Wrapper} 候选类。
     * <p>
     * 适用于需要预览扫描结果、或自定义注册逻辑的场景。
     *
     * @param basePackage 基础包名
     * @param classLoader 类加载器
     * @return 候选包装类集合（保持发现顺序）
     */
    public Set<Class<?>> findWrapperClasses(String basePackage, ClassLoader classLoader) {
        return new LinkedHashSet<>(annotatedScanner.scan(classLoader, basePackage));
    }
}
