package io.github.JiangHu.jframe.content_template;

import io.github.JiangHu.jframe.content_template.loader.ClasspathTemplateLoader;
import io.github.JiangHu.jframe.core.data.reactive.DataContext;

/**
 * 模板插件作用域 — 绑定了指定插件 ClassLoader 的模板加载代理。
 *
 * <p>由 {@link TemplateEngine#forPlugin} 创建，封装"使用指定插件类路径加载模板"的逻辑。
 * 本类只暴露需要 ClassLoader 的方法（{@code getTemplate} / {@code render} / {@code renderText}），
 * 不暴露 compile / registerLoader 等无关方法。
 *
 * <h3>工作原理</h3>
 * <p>
 * 每次调用 {@code getTemplate} 时，使用绑定的 ClassLoader 构造临时的 {@link ClasspathTemplateLoader}，
 * 从插件 jar 内的 {@code prefix} 目录加载模板源码，然后委托 {@link TemplateEngine#compile}
 * 编译（复用引擎的缓存机制）。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 从当前插件 jar 加载模板
 * Template tpl = engine.forPlugin(this).getTemplate("main");
 *
 * // 从当前插件 jar 加载并渲染
 * RenderResult result = engine.forPlugin(this).render("main", data);
 *
 * // 指定自定义前缀和后缀
 * Template tpl = engine.forPlugin(this)
 *     .withPrefix("views/").withSuffix(".html")
 *     .getTemplate("home");
 * }</pre>
 *
 * <h3>线程安全</h3>
 * 每次调用 {@link TemplateEngine#forPlugin} 都创建新的 Scope 实例，
 * 独立持有 ClassLoader，互不干扰。编译结果复用引擎的全局缓存。
 *
 * @see TemplateEngine#forPlugin
 * @see ClasspathTemplateLoader
 */
public class TemplatePluginScope {

    /** 共享的模板引擎（复用编译缓存） */
    private final TemplateEngine engine;

    /** 绑定的插件类加载器 */
    private final ClassLoader classLoader;

    /** 模板在插件 jar 中的路径前缀 */
    private final String prefix;

    /** 模板文件后缀 */
    private final String suffix;

    /**
     * 构造作用域（包级可见，使用默认前缀 {@code templates/} 和后缀 {@code .xml}）。
     *
     * @param engine      模板引擎
     * @param classLoader 插件类加载器
     */
    TemplatePluginScope(TemplateEngine engine, ClassLoader classLoader) {
        this(engine, classLoader, "templates/", ".xml");
    }

    /**
     * 构造作用域（指定前缀和后缀）。
     *
     * @param engine      模板引擎
     * @param classLoader 插件类加载器
     * @param prefix      路径前缀
     * @param suffix      文件后缀
     */
    TemplatePluginScope(TemplateEngine engine, ClassLoader classLoader, String prefix, String suffix) {
        this.engine = engine;
        this.classLoader = classLoader;
        this.prefix = prefix;
        this.suffix = suffix;
    }

    /**
     * 设置路径前缀（返回新 Scope，不可变模式）。
     *
     * @param prefix 路径前缀（如 {@code "views/"})
     * @return 新的作用域实例
     */
    public TemplatePluginScope withPrefix(String prefix) {
        return new TemplatePluginScope(engine, classLoader, prefix, suffix);
    }

    /**
     * 设置文件后缀（返回新 Scope，不可变模式）。
     *
     * @param suffix 文件后缀（如 {@code ".html"})
     * @return 新的作用域实例
     */
    public TemplatePluginScope withSuffix(String suffix) {
        return new TemplatePluginScope(engine, classLoader, prefix, suffix);
    }

    /**
     * 从绑定插件的类路径加载并编译模板。
     *
     * @param name 模板名称（不含后缀）
     * @return 编译后的模板
     * @throws TemplateNotFoundException 模板不存在时抛出
     */
    public Template getTemplate(String name) {
        String source = loadRaw(name);
        return engine.compile(source);
    }

    /**
     * 从绑定插件的类路径加载模板并渲染。
     *
     * @param name 模板名称
     * @param data 数据上下文
     * @return 渲染结果
     */
    public RenderResult render(String name, DataContext data) {
        return engine.render(getTemplate(name), data);
    }

    /**
     * 从绑定插件的类路径加载纯文本模板并渲染。
     *
     * @param name 模板名称
     * @param data 数据上下文
     * @return 渲染后的字符串
     */
    public String renderText(String name, DataContext data) {
        String source = loadRaw(name);
        return engine.renderText(engine.compileText(source), data);
    }

    /**
     * 从绑定插件的类路径加载模板源码（不编译）。
     *
     * @param name 模板名称
     * @return 模板源码
     * @throws TemplateNotFoundException 模板不存在时抛出
     */
    public String loadRaw(String name) {
        ClasspathTemplateLoader loader = new ClasspathTemplateLoader(prefix, suffix, classLoader);
        try {
            String source = loader.load(name);
            if (source == null) {
                throw new TemplateNotFoundException("模板不存在: " + prefix + name + suffix);
            }
            return source;
        } catch (TemplateNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new TemplateLoadException("模板加载失败: " + prefix + name + suffix, e);
        }
    }
}
