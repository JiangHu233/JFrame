package io.github.JiangHu.jframe.core.scan;

import io.github.JiangHu.jframe.core.JFrameLog;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 注解类路径扫描器 — 封装 Spring {@link ClassPathScanningCandidateComponentProvider} 的配置样板。
 *
 * <h3>设计动机</h3>
 * <p>
 * {@code CommandScanner} 和 {@code WrapperScanner} 中存在几乎完全相同的 {@code createProvider} 方法：
 * <pre>
 * ClassPathScanningCandidateComponentProvider provider =
 *     new ClassPathScanningCandidateComponentProvider(false);  // 关闭默认过滤器
 * provider.addIncludeFilter(new AnnotationTypeFilter(XXX));    // 唯一差异：注解类型
 * provider.setResourceLoader(new DefaultResourceLoader(cl));   // 绑定 ClassLoader
 * </pre>
 * 本类将这段样板提取为通用工具，消除重复代码。各模块只需传入注解类型即可。
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>{@link #scan} — 扫描指定包，返回标注了目标注解的具体类集合</li>
 *   <li>{@link #resolveClassLoader()} — TCCL 回退工具方法（静态）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 扫描 @CommandController
 * AnnotatedClassScanner scanner = new AnnotatedClassScanner(CommandController.class);
 * Set<Class<?>> classes = scanner.scan(pluginClassLoader, "com.myplugin.command");
 *
 * // 扫描 @Wrapper
 * AnnotatedClassScanner scanner = new AnnotatedClassScanner(Wrapper.class);
 * Set<Class<?>> classes = scanner.scan(pluginClassLoader, "com.myplugin.event");
 * }</pre>
 *
 * <h3>过滤规则</h3>
 * Spring 扫描器内置过滤：即使标注了目标注解，接口、抽象类、注解类型也会被自动跳过。
 */
public class AnnotatedClassScanner {

    /** 目标注解类型 */
    private final Class<? extends Annotation> annotationType;

    /**
     * 构造扫描器。
     *
     * @param annotationType 要扫描的注解类型（如 {@code CommandController.class}）
     */
    public AnnotatedClassScanner(Class<? extends Annotation> annotationType) {
        if (annotationType == null) {
            throw new IllegalArgumentException("annotationType 不能为 null");
        }
        this.annotationType = annotationType;
    }

    /**
     * 扫描指定基础包（含子包）下所有标注了目标注解的具体类。
     *
     * @param classLoader  用于加载资源和类的类加载器
     * @param basePackages 要扫描的基础包名（可变参数）
     * @return 候选类集合（保持发现顺序，跳过无法加载的类）
     */
    public Set<Class<?>> scan(ClassLoader classLoader, String... basePackages) {
        Set<Class<?>> result = new LinkedHashSet<>();
        if (basePackages == null || basePackages.length == 0) {
            return result;
        }

        ClassPathScanningCandidateComponentProvider provider = createProvider(classLoader);

        for (String basePackage : basePackages) {
            if (basePackage == null || basePackage.isBlank()) continue;
            for (BeanDefinition bd : provider.findCandidateComponents(basePackage.trim())) {
                String className = bd.getBeanClassName();
                try {
                    result.add(Class.forName(className, false, classLoader));
                } catch (Throwable e) {
                    JFrameLog.warning("AnnotatedClassScanner",
                            "跳过无法加载的类: " + className
                                    + " (" + e.getClass().getSimpleName() + ")");
                }
            }
        }
        return result;
    }

    /**
     * 创建配置好的 Spring 类路径扫描器。
     * <p>
     * 关闭默认过滤器（不自动扫描 {@code @Component} 等），仅添加目标注解过滤器，
     * 并绑定指定类加载器以扫描 Nukkit 插件 jar 内的类。
     *
     * @param classLoader 类加载器
     * @return 配置好的 Spring 扫描器
     */
    private ClassPathScanningCandidateComponentProvider createProvider(ClassLoader classLoader) {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(annotationType));
        provider.setResourceLoader(new DefaultResourceLoader(classLoader));
        return provider;
    }

    /**
     * 解析当前可用的类加载器：优先使用线程上下文类加载器（TCCL），
     * 回退到本类的类加载器。
     * <p>
     * 在 Nukkit 插件中，调用方应在 {@code onEnable} 期间将 TCCL 切换为插件自身的类加载器，
     * 扫描器即可正确发现插件 jar 内的类。也可通过 {@link #scan(ClassLoader, String...)}
     * 显式指定类加载器来避免依赖 TCCL。
     *
     * @return 当前可用的类加载器（不会为 null）
     */
    public static ClassLoader resolveClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = AnnotatedClassScanner.class.getClassLoader();
        }
        return cl;
    }
}
