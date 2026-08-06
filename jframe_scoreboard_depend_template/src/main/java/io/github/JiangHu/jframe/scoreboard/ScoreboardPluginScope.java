package io.github.JiangHu.jframe.scoreboard;

import io.github.JiangHu.jframe.content_template.Template;

/**
 * 记分板插件作用域 — 绑定了指定插件 ClassLoader 的模板加载代理。
 *
 * <p>由 {@link ScoreboardAPI#forPlugin} 创建，封装"使用指定插件类路径加载记分板模板"的逻辑。
 * 本类只暴露 {@link #loadTemplate} 方法，不暴露 show / hide / update 等无关方法。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 从当前插件 jar 加载记分板模板
 * scoreboardAPI.forPlugin(this).loadTemplate("hud");
 *
 * // 指定自定义前缀
 * scoreboardAPI.forPlugin(this).withPrefix("scoreboard/").loadTemplate("main");
 *
 * // 从其他插件加载
 * scoreboardAPI.forPlugin("OtherPlugin").loadTemplate("custom");
 * }</pre>
 *
 * <h3>线程安全</h3>
 * 每次调用 {@link ScoreboardAPI#forPlugin} 都创建新的 Scope 实例，
 * 独立持有 ClassLoader，互不干扰。注册结果统一到共享的 {@link ScoreboardManager}。
 *
 * @see ScoreboardAPI#forPlugin
 */
public class ScoreboardPluginScope {

    /** 记分板 API（用于注册模板） */
    private final ScoreboardAPI api;

    /** 绑定的插件类加载器 */
    private final ClassLoader classLoader;

    /** 模板在插件 jar 中的路径前缀 */
    private String prefix = "templates/";

    /**
     * 构造作用域（包级可见，由 {@link ScoreboardAPI#forPlugin} 创建）。
     *
     * @param api         记分板 API
     * @param classLoader 插件类加载器
     */
    ScoreboardPluginScope(ScoreboardAPI api, ClassLoader classLoader) {
        this.api = api;
        this.classLoader = classLoader;
    }

    /**
     * 设置路径前缀（返回新 Scope，不可变模式）。
     *
     * @param prefix 路径前缀（如 {@code "scoreboard/"})
     * @return 新的作用域实例
     */
    public ScoreboardPluginScope withPrefix(String prefix) {
        ScoreboardPluginScope scope = new ScoreboardPluginScope(api, classLoader);
        scope.prefix = prefix;
        return scope;
    }

    /**
     * 从绑定插件的类路径加载模板文件并注册。
     *
     * @param name 模板名称（文件名，不含 {@code .xml} 扩展名）
     * @return 编译并包装后的 {@link ScoreboardTemplate}
     * @throws RuntimeException 模板加载或编译失败时抛出
     */
    public ScoreboardTemplate loadTemplate(String name) {
        // 委托：加载+编译交给 TemplateEngine 的 TemplatePluginScope（复用缓存、prefix/suffix 逻辑）
        // 不再自己 new ClasspathTemplateLoader，消除与 template 模块的重复加载逻辑
        Template template = api.getEngine()
                .forPlugin(classLoader)
                .withPrefix(prefix)
                .getTemplate(name);
        // 独有：只负责计分板专属包装 + 注册
        ScoreboardTemplate sbTemplate = ScoreboardTemplate.of(name, template);
        api.getManager().loadTemplate(sbTemplate);
        return sbTemplate;
    }
}
