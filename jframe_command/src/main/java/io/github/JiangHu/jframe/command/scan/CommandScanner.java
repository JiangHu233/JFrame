package io.github.JiangHu.jframe.command.scan;

import cn.nukkit.Server;
import io.github.JiangHu.jframe.command.CommandAPI;
import io.github.JiangHu.jframe.command.annotation.CommandController;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 命令控制器包扫描器 — 自动发现并注册标注了 {@link CommandController} 的类。
 * <p>
 * 基于 Spring 的 {@link ClassPathScanningCandidateComponentProvider}，与
 * {@code WrapperScanner} 同构：给定基础包，递归扫描其下（含子包）的所有
 * {@code .class} 文件，找出标注 {@code @CommandController} 的具体类，交由
 * {@link CommandAPI#register} 注册。
 *
 * <h3>类加载器策略</h3>
 * <p>
 * 默认使用线程上下文类加载器（TCCL），回退到本类的类加载器。
 * 在 Nukkit 插件中，调用方应在 {@code onEnable} 期间将 TCCL 切换为插件自身的类加载器
 * （参见示例 {@code ExamplePlugin}），扫描器即可正确发现插件 jar 内的控制器类。
 * 也可通过 {@link #scan(ClassLoader, String...)} 显式指定类加载器。
 *
 * <h3>过滤规则</h3>
 * <p>
 * Spring 扫描器内置过滤：即使标注了 {@code @CommandController}，接口、抽象类、注解类型
 * 也会被自动跳过（无法实例化，注册无意义）。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 通过 CommandAPI 便捷方法（推荐）
 * commandService.scan("com.myplugin.command");
 *
 * // 直接使用 CommandScanner
 * CommandScanner scanner = new CommandScanner(commandService);
 * List<Class<?>> registered = scanner.scan("com.myplugin.command");
 * }</pre>
 *
 * <h3>容错性</h3>
 * 单个类的注册失败由 {@link CommandAPI#register} 内部处理并记录，不会中断整体扫描。
 *
 * @see CommandController
 * @see CommandAPI#scan
 */
public class CommandScanner {

    /** 命令服务：扫描到的类通过它注册 */
    private final CommandAPI commandAPI;

    /**
     * 构造扫描器。
     *
     * @param commandAPI 命令服务（用于注册扫描到的控制器）
     */
    public CommandScanner(CommandAPI commandAPI) {
        this.commandAPI = commandAPI;
    }

    /**
     * 扫描指定基础包（含子包）下所有 {@code @CommandController} 类并注册。
     * <p>
     * 使用线程上下文类加载器（TCCL），回退到本类类加载器。
     *
     * @param basePackages 要扫描的基础包名（可变参数）
     * @return 实际注册成功的控制器类列表（保持发现顺序）
     */
    public List<Class<?>> scan(String... basePackages) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = CommandScanner.class.getClassLoader();
        }
        return scan(classLoader, basePackages);
    }

    /**
     * 扫描指定基础包（含子包）下所有 {@code @CommandController} 类并注册。
     * <p>
     * 显式指定类加载器，适用于需要精确控制类加载来源的场景。
     *
     * @param classLoader  用于加载资源和类的类加载器
     * @param basePackages 要扫描的基础包名（可变参数）
     * @return 实际注册成功的控制器类列表（保持发现顺序）
     */
    public List<Class<?>> scan(ClassLoader classLoader, String... basePackages) {
        List<Class<?>> registered = new ArrayList<>();
        if (basePackages == null || basePackages.length == 0) {
            return registered;
        }

        ClassPathScanningCandidateComponentProvider provider = createProvider(classLoader);

        for (String basePackage : basePackages) {
            if (basePackage == null || basePackage.isBlank()) continue;
            for (BeanDefinition bd : provider.findCandidateComponents(basePackage.trim())) {
                String className = bd.getBeanClassName();
                try {
                    Class<?> clazz = Class.forName(className, false, classLoader);
                    commandAPI.register(clazz);
                    registered.add(clazz);
                    Server.getInstance().getLogger().info(
                            "[CommandScanner] 已注册命令控制器: " + className);
                } catch (Throwable e) {
                    Server.getInstance().getLogger().error(
                            "[CommandScanner] 注册命令控制器失败: " + className, e);
                }
            }
        }

        Server.getInstance().getLogger().info(
                "[CommandScanner] 扫描完成，共注册 " + registered.size() + " 个命令控制器。");
        return registered;
    }

    /**
     * 仅扫描（不注册）：返回指定包下所有 {@code @CommandController} 候选类。
     *
     * @param basePackage 基础包名
     * @param classLoader 类加载器
     * @return 候选控制器类集合（保持发现顺序）
     */
    public Set<Class<?>> findControllerClasses(String basePackage, ClassLoader classLoader) {
        Set<Class<?>> result = new LinkedHashSet<>();
        ClassPathScanningCandidateComponentProvider provider = createProvider(classLoader);
        for (BeanDefinition bd : provider.findCandidateComponents(basePackage)) {
            try {
                result.add(Class.forName(bd.getBeanClassName(), false, classLoader));
            } catch (Throwable e) {
                Server.getInstance().getLogger().warning(
                        "[CommandScanner] 跳过无法加载的类: " + bd.getBeanClassName()
                                + " (" + e.getClass().getSimpleName() + ")");
            }
        }
        return result;
    }

    /**
     * 创建配置好的 Spring 类路径扫描器。
     * <p>
     * 关闭默认过滤器，仅添加 {@code @CommandController} 注解过滤器，
     * 并绑定指定类加载器以扫描 Nukkit 插件 jar 内的类。
     */
    private ClassPathScanningCandidateComponentProvider createProvider(ClassLoader classLoader) {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(CommandController.class));
        provider.setResourceLoader(new DefaultResourceLoader(classLoader));
        return provider;
    }
}
