package io.github.JiangHu.jframe.transform.scan;

import io.github.JiangHu.jframe.core.JFrameLog;

import java.io.IOException;
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
 * 包内类枚举器——枚举基础包（含子包）下的全部具体类（jar / 目录 classpath 均覆盖），
 * 供方法级注解扫描使用（core 的 {@code AnnotatedClassScanner} 只找类级注解，
 * {@code @HookOn} 标注在方法上，需要先枚举类再反射查方法）。
 * <p>
 * 与 {@code AnnotatedClassScanner} 同源逻辑：接口/抽象类/注解类型自动跳过；
 * 单类加载失败记 warning 不中断。
 */
final class PackageClassWalker {

    private PackageClassWalker() {
    }

    /**
     * 枚举基础包下全部具体类。
     *
     * @param classLoader 类加载器（插件 jar）
     * @param basePackage 基础包名
     * @return 类集合（发现序）
     */
    static Set<Class<?>> walk(ClassLoader classLoader, String basePackage) {
        Set<Class<?>> result = new LinkedHashSet<>();
        String packagePath = basePackage.trim().replace('.', '/');
        Enumeration<URL> roots;
        try {
            roots = classLoader.getResources(packagePath);
        } catch (IOException e) {
            warn("枚举包资源失败: " + basePackage + " (" + e + ")");
            return result;
        }
        while (roots.hasMoreElements()) {
            collect(roots.nextElement(), packagePath, classLoader, result);
        }
        return result;
    }

    private static void collect(URL root, String packagePath, ClassLoader classLoader, Set<Class<?>> result) {
        if ("jar".equals(root.getProtocol())) {
            collectFromJar(root, packagePath, classLoader, result);
        } else if ("file".equals(root.getProtocol())) {
            collectFromDirectory(root, packagePath, classLoader, result);
        } else {
            warn("跳过不支持的 classpath 协议: " + root);
        }
    }

    private static void collectFromJar(URL root, String packagePath, ClassLoader classLoader, Set<Class<?>> result) {
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
                    offer(className.substring(0, className.length() - ".class".length()), classLoader, result);
                }
            }
        } catch (Exception e) {
            warn("读取 jar 失败: " + root + " (" + e + ")");
        }
    }

    private static void collectFromDirectory(URL root, String packagePath, ClassLoader classLoader, Set<Class<?>> result) {
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
                        .forEach(n -> offer(n, classLoader, result));
            }
        } catch (Exception e) {
            warn("扫描目录失败: " + root + " (" + e + ")");
        }
    }

    private static void offer(String className, ClassLoader classLoader, Set<Class<?>> result) {
        try {
            Class<?> cls = Class.forName(className, false, classLoader);
            if (cls.isInterface() || cls.isAnnotation() || cls.isEnum()
                    || java.lang.reflect.Modifier.isAbstract(cls.getModifiers())) {
                return;
            }
            result.add(cls);
        } catch (Throwable e) {
            warn("跳过无法加载的类: " + className + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static void warn(String message) {
        try {
            JFrameLog.warning("PackageClassWalker", message);
        } catch (Throwable noServer) {
            // 单元测试无 Server 环境静默
        }
    }
}
