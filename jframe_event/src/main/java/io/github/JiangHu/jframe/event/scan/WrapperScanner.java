package io.github.JiangHu.jframe.event.scan;

import cn.nukkit.Server;
import io.github.JiangHu.jframe.event.EventAPI;
import io.github.JiangHu.jframe.event.annotation.Wrapper;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 包装类包扫描器 — 自动发现并注册标注了 {@link Wrapper} 的类。
 * <p>
 * 基于 Spring 的 {@link ClassPathScanningCandidateComponentProvider} 实现，
 * 类似 Spring 的 {@code ClassPathBeanDefinitionScanner}：给定一个或多个<b>基础包</b>，
 * 递归扫描其下（含子包）的所有 {@code .class} 文件，找出标注 {@code @Wrapper} 的具体类，
 * 交由 {@link EventAPI#register} 注册。
 *
 * <h3>实现原理</h3>
 * <p>
 * 委托给 Spring 的 {@code ClassPathScanningCandidateComponentProvider}，它内部通过
 * {@code PathMatchingResourcePatternResolver} 自动处理 {@code file:}（开发环境）和
 * {@code jar:}（生产环境 / Nukkit 插件）等多种协议，并用 ASM {@code MetadataReader}
 * 读取字节码注解（无需真正加载类），性能优于反射扫描。
 *
 * <h3>类加载器策略</h3>
 * <p>
 * 默认使用<b>线程上下文类加载器</b>（TCCL），回退到本类的类加载器。
 * 在 Nukkit 插件中，调用方应在 {@code onEnable} 期间将 TCCL 切换为插件自身的类加载器
 * （参见示例 {@code ExamplePlugin}），扫描器即可正确发现插件 jar 内的包装类。
 * 也可通过 {@link #scan(ClassLoader, String...)} 显式指定类加载器。
 *
 * <h3>过滤规则</h3>
 * <p>
 * Spring 扫描器内置过滤：即使标注了 {@code @Wrapper}，以下类型也会被自动跳过
 * （无法实例化，注册无意义）：
 * <ul>
 *   <li>接口</li>
 *   <li>抽象类</li>
 *   <li>注解类型</li>
 * </ul>
 * 此外，{@code AnnotationTypeFilter} 仅匹配直接标注 {@code @Wrapper} 的类
 * （因 {@code @Wrapper} 未声明 {@code @Inherited}，子类不会被误匹配）。
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
 * <p>
 * 单个类的注册失败（如缺少 {@code @KeyExtractor}）由 {@link EventAPI#register} 内部
 * 处理并记录，不会中断整体扫描。
 *
 * @see Wrapper
 * @see EventAPI#scan
 * @see EventAPI#register
 * @see ClassPathScanningCandidateComponentProvider
 */
public class WrapperScanner {

    /** 事件服务：扫描到的类通过它注册 */
    private final EventAPI eventAPI;

    /**
     * 构造扫描器。
     *
     * @param eventAPI 事件服务（用于注册扫描到的包装类）
     */
    public WrapperScanner(EventAPI eventAPI) {
        this.eventAPI = eventAPI;
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
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = WrapperScanner.class.getClassLoader();
        }
        return scan(classLoader, basePackages);
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

        // 创建 Spring 扫描器：关闭默认过滤器，仅匹配 @Wrapper 注解
        ClassPathScanningCandidateComponentProvider provider = createProvider(classLoader);

        for (String basePackage : basePackages) {
            if (basePackage == null || basePackage.isBlank()) continue;
            for (BeanDefinition bd : provider.findCandidateComponents(basePackage.trim())) {
                String className = bd.getBeanClassName();
                try {
                    Class<?> clazz = Class.forName(className, false, classLoader);
                    eventAPI.register(clazz);
                    registered.add(clazz);
                    Server.getInstance().getLogger().info(
                            "[WrapperScanner] 已注册包装类: " + className);
                } catch (Throwable e) {
                    Server.getInstance().getLogger().error(
                            "[WrapperScanner] 注册包装类失败: " + className, e);
                }
            }
        }

        Server.getInstance().getLogger().info(
                "[WrapperScanner] 扫描完成，共注册 " + registered.size() + " 个包装类。");
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
        Set<Class<?>> result = new LinkedHashSet<>();
        ClassPathScanningCandidateComponentProvider provider = createProvider(classLoader);

        for (BeanDefinition bd : provider.findCandidateComponents(basePackage)) {
            try {
                result.add(Class.forName(bd.getBeanClassName(), false, classLoader));
            } catch (Throwable e) {
                Server.getInstance().getLogger().warning(
                        "[WrapperScanner] 跳过无法加载的类: " + bd.getBeanClassName()
                                + " (" + e.getClass().getSimpleName() + ")");
            }
        }
        return result;
    }

    /**
     * 创建配置好的 Spring 类路径扫描器。
     * <p>
     * 关闭默认过滤器（不自动扫描 {@code @Component} 等），仅添加 {@code @Wrapper} 注解过滤器。
     * 关键：通过 {@link DefaultResourceLoader} 绑定指定类加载器，确保能扫描到
     * Nukkit 插件 jar 内的类。
     *
     * @param classLoader 类加载器
     * @return 配置好的扫描器实例
     */
    private ClassPathScanningCandidateComponentProvider createProvider(ClassLoader classLoader) {
        // false = 不使用默认过滤器（@Component / @Repository / @Service 等）
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        // 仅匹配标注了 @Wrapper 的具体类
        provider.addIncludeFilter(new AnnotationTypeFilter(Wrapper.class));
        // 绑定类加载器：确保扫描器用正确的类加载器解析 classpath 资源
        provider.setResourceLoader(new DefaultResourceLoader(classLoader));
        return provider;
    }
}
