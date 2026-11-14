package io.github.JiangHu.jframe.core.scan;

import io.github.JiangHu.jframe.core.JFrameLog;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 注解类路径扫描器 — 无 Spring 依赖的自研实现（原实现封装 Spring 的
 * {@code ClassPathScanningCandidateComponentProvider}，去除 IoC 容器时重写）。
 *
 * <h3>实现原理</h3>
 * <ol>
 *   <li>{@link ClassLoader#getResources(String)} 枚举基础包对应的全部 classpath 根
 *       （jar 或目录，Nukkit 插件 jar / IDE 的 target/classes 均覆盖）</li>
 *   <li>jar 根遍历 {@link java.util.jar.JarFile} entry，目录根 {@link Files#walk} 递归</li>
 *   <li>收集 {@code .class} 限定名，{@link Class#forName(String, boolean, ClassLoader)}
 *       不初始化加载后检查目标注解</li>
 * </ol>
 *
 * <h3>过滤规则</h3>
 * 即使标注了目标注解，接口、抽象类、注解类型也会被自动跳过；
 * 单个类加载失败记 warning 跳过，不中断整体扫描（保持原实现行为）。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 扫描 @CommandController
 * AnnotatedClassScanner scanner = new AnnotatedClassScanner(CommandController.class);
 * Set<Class<?>> classes = scanner.scan(pluginClassLoader, "com.myplugin.command");
 * }</pre>
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
        for (String basePackage : basePackages) {
            if (basePackage == null || basePackage.isBlank()) {
                continue;
            }
            String packagePath = basePackage.trim().replace('.', '/');
            Enumeration<URL> roots;
            try {
                roots = classLoader.getResources(packagePath);
            } catch (IOException e) {
                warn("枚举包资源失败: " + basePackage + " (" + e + ")");
                continue;
            }
            while (roots.hasMoreElements()) {
                collect(roots.nextElement(), packagePath, classLoader, result);
            }
        }
        return result;
    }

    /**
     * 从单个 classpath 根（jar 或目录）收集候选类。
     */
    private void collect(URL root, String packagePath, ClassLoader classLoader, Set<Class<?>> result) {
        String protocol = root.getProtocol();
        if ("jar".equals(protocol)) {
            collectFromJar(root, packagePath, classLoader, result);
        } else if ("file".equals(protocol)) {
            collectFromDirectory(root, packagePath, classLoader, result);
        } else {
            warn("跳过不支持的 classpath 协议: " + root);
        }
    }

    /**
     * jar 根：遍历 JarFile entry。
     */
    private void collectFromJar(URL root, String packagePath, ClassLoader classLoader, Set<Class<?>> result) {
        String prefix = packagePath + "/";
        try {
            JarURLConnection connection = (JarURLConnection) root.openConnection();
            connection.setUseCaches(false);
            try (var jar = connection.getJarFile()) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.startsWith(prefix) || !name.endsWith(".class")) {
                        continue;
                    }
                    String className = name.replace('/', '.');
                    offerClassName(className.substring(0, className.length() - ".class".length()), classLoader, result);
                }
            }
        } catch (Exception e) {
            warn("读取 jar 失败: " + root + " (" + e + ")");
        }
    }

    /**
     * 目录根：递归遍历文件树（IDE / 测试环境的 target/classes）。
     */
    private void collectFromDirectory(URL root, String packagePath, ClassLoader classLoader, Set<Class<?>> result) {
        try {
            Path dir = Paths.get(root.toURI());
            String packagePrefix = packagePath.replace('/', '.');
            int drop = ".class".length();
            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(Files::isRegularFile)
                        .map(dir::relativize)
                        .map(p -> p.toString().replace(java.io.File.separatorChar, '.'))
                        .filter(n -> n.endsWith(".class"))
                        .map(n -> packagePrefix + "." + n.substring(0, n.length() - drop))
                        .forEach(n -> offerClassName(n, classLoader, result));
            }
        } catch (Exception e) {
            warn("扫描目录失败: " + root + " (" + e + ")");
        }
    }

    /**
     * 加载并校验候选类：标注目标注解且非接口/抽象类/注解类型才收录。
     */
    private void offerClassName(String className, ClassLoader classLoader, Set<Class<?>> result) {
        try {
            Class<?> cls = Class.forName(className, false, classLoader);
            if (!cls.isAnnotationPresent(annotationType)) {
                return;
            }
            int modifiers = cls.getModifiers();
            if (cls.isInterface() || cls.isAnnotation()
                    || Modifier.isAbstract(modifiers)) {
                return;
            }
            result.add(cls);
        } catch (Throwable e) {
            warn("跳过无法加载的类: " + className + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * 输出警告日志；Server 未初始化（如单元测试）时降级为静默，
     * 保证扫描器在无服务端环境下可用。
     */
    private static void warn(String message) {
        try {
            JFrameLog.warning("AnnotatedClassScanner", message);
        } catch (Throwable ignored) {
            // 无 Server 环境（单元测试）下降级为静默
        }
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
