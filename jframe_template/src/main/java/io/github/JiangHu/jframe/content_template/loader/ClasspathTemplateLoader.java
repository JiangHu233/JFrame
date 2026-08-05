package io.github.JiangHu.jframe.content_template.loader;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Classpath 模板加载器——从 classpath 加载打包在 jar 内的模板。
 *
 * <p>适用于内置模板（随插件发布的默认模板）。不支持热重载（{@link #lastModified} 恒返回 0）。
 *
 * <h3>跨插件加载</h3>
 * <p>Nukkit 中每个插件有独立的 {@code PluginClassLoader}，默认构造（不传 {@link ClassLoader}）
 * 只能加载<b>本插件 jar</b>内的资源。若需加载<b>其他插件 jar</b>内的模板，传入目标插件的
 * 类加载器即可：
 * <pre>{@code
 * // 加载 "MyAddon" 插件 jar 内的模板
 * ClassLoader addonLoader = PluginClassLoaderFactory.getClassLoader("MyAddon");
 * engine.registerLoader(new ClasspathTemplateLoader("templates/", addonLoader));
 *
 * // 聚合多个插件，从任一插件 jar 加载
 * CompositeClassLoader multi = PluginClassLoaderFactory.compositeOf("JFrame", "MyAddon");
 * engine.registerLoader(new ClasspathTemplateLoader("templates/", ".xml", multi));
 * }</pre>
 *
 * <p>模板路径规则：{@code prefix + name + suffix}
 * <ul>
 *   <li>prefix：classpath 前缀，如 {@code "templates/scoreboard/"}</li>
 *   <li>name：模板名称，如 {@code "main"}</li>
 *   <li>suffix：文件扩展名，默认 {@code ".xml"}</li>
 * </ul>
 * <p>示例：prefix={@code "templates/"}, name={@code "lobby"}, suffix={@code ".xml"} →
 * classpath:templates/lobby.xml
 *
 * @see io.github.JiangHu.jframe.core.classloader.PluginClassLoaderFactory 按插件名获取类加载器
 * @see io.github.JiangHu.jframe.core.classloader.CompositeClassLoader 聚合多个类加载器
 */
public class ClasspathTemplateLoader implements TemplateLoader {

    private final String prefix;
    private final String suffix;
    /** 用于加载资源的类加载器，{@code null} 表示用默认（当前线程上下文类加载器） */
    private final ClassLoader classLoader;

    /**
     * 用默认扩展名 {@code .xml}、默认类加载器创建。
     *
     * @param prefix classpath 前缀（如 {@code "templates/scoreboard/"}）
     */
    public ClasspathTemplateLoader(String prefix) {
        this(prefix, ".xml", null);
    }

    /**
     * 用默认扩展名 {@code .xml}、指定类加载器创建（支持跨插件加载）。
     *
     * @param prefix      classpath 前缀
     * @param classLoader 用于加载资源的类加载器（可为 {@link io.github.JiangHu.jframe.core.classloader.CompositeClassLoader}）
     */
    public ClasspathTemplateLoader(String prefix, ClassLoader classLoader) {
        this(prefix, ".xml", classLoader);
    }

    /**
     * @param prefix classpath 前缀
     * @param suffix 文件扩展名（如 {@code ".xml"}、{@code ".txt"}）
     */
    public ClasspathTemplateLoader(String prefix, String suffix) {
        this(prefix, suffix, null);
    }

    /**
     * 全参构造——指定前缀、扩展名和类加载器。
     *
     * @param prefix      classpath 前缀
     * @param suffix      文件扩展名
     * @param classLoader 用于加载资源的类加载器，{@code null} 表示用默认（{@code ClassPathResource} 默认行为）
     */
    public ClasspathTemplateLoader(String prefix, String suffix, ClassLoader classLoader) {
        this.prefix = normalizePrefix(prefix);
        this.suffix = suffix != null ? suffix : "";
        this.classLoader = classLoader;
    }

    @Override
    public String load(String name) throws Exception {
        String path = prefix + name + suffix;
        Resource resource = classLoader != null
                ? new ClassPathResource(path, classLoader)
                : new ClassPathResource(path);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public long lastModified(String name) {
        // classpath 资源不支持热重载
        return 0;
    }

    /** 当前使用的类加载器（可能为 {@code null}，表示用默认） */
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    private String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }
}
