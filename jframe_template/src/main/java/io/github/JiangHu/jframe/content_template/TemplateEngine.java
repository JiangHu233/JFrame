package io.github.JiangHu.jframe.content_template;

import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.content_template.loader.CompositeTemplateLoader;
import io.github.JiangHu.jframe.content_template.loader.TemplateLoader;
import io.github.JiangHu.jframe.content_template.parser.TemplateParser;
import io.github.JiangHu.jframe.content_template.render.TemplateRenderer;
import io.github.JiangHu.jframe.core.classloader.PluginClassLoaderFactory;
import io.github.JiangHu.jframe.core.data.reactive.DataContext;
import io.github.JiangHu.jframe.core.module.ForPlugin;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模板引擎门面——整合 {@link TemplateParser}（解析）、{@link TemplateRenderer}（渲染）、
 * {@link TemplateLoader}（加载）和缓存机制，提供一站式 API。
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li><b>编译</b>：{@link #compile} / {@link #compileText} 将源码编译为 {@link Template}（带缓存）</li>
 *   <li><b>按名称加载</b>：{@link #getTemplate} 通过 Loader 加载源码 + 编译 + 缓存 + 热重载检测</li>
 *   <li><b>渲染</b>：{@link #render} / {@link #renderText} 加载模板 + 渲染为 {@link RenderResult} / 字符串</li>
 *   <li><b>Loader 管理</b>：{@link #registerLoader} 注册加载器</li>
 * </ul>
 *
 * <h3>缓存策略</h3>
 * <ul>
 *   <li><b>按名称缓存</b>：以模板名称为 key，配合 {@code lastModified} 检测文件变更实现热重载</li>
 *   <li><b>按源码缓存</b>：以源码内容 SHA-256 为 key，避免重复编译相同源码</li>
 *   <li>线程安全：{@link ConcurrentHashMap} + {@link CompositeTemplateLoader}（CopyOnWriteArrayList）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * TemplateEngine engine = new TemplateEngine();
 * engine.registerLoader(new FileTemplateLoader(dataFolder, "templates/"));
 * engine.registerLoader(new ClasspathTemplateLoader("templates/scoreboard/"));
 *
 * // 渲染 XML 模板
 * DataContext data = new DataContext();
 * data.put("serverName", "我的服务器");
 * RenderResult result = engine.render("main", data);
 * result.getTitle();   // "我的服务器 - 联机大厅"
 * result.getLines();   // ["玩家: Steve", "金币: 1000", ...]
 *
 * // 直接编译源码渲染
 * Template template = engine.compile("<template><line>Hello {{name}}</line></template>");
 * RenderResult result2 = engine.render(template, data);
 * }</pre>
 */
public class TemplateEngine implements ForPlugin<TemplatePluginScope> {

    private final TemplateParser parser;
    private final TemplateRenderer renderer;
    private final CompositeTemplateLoader loaderChain;

    /** 按名称缓存：模板名称 → 编译后的 Template */
    private final ConcurrentHashMap<String, Template> nameCache = new ConcurrentHashMap<>();

    /** 按名称记录最后修改时间，用于热重载检测 */
    private final ConcurrentHashMap<String, Long> nameLastModified = new ConcurrentHashMap<>();

    /** 按源码哈希缓存：SHA-256 → 编译后的 Template */
    private final ConcurrentHashMap<String, Template> sourceCache = new ConcurrentHashMap<>();

    /** 无参构造：使用默认 Parser 和 Renderer */
    public TemplateEngine() {
        this.parser = new TemplateParser();
        this.renderer = new TemplateRenderer();
        this.loaderChain = new CompositeTemplateLoader();
    }

    /**
     * 注册模板加载器（追加到 loader 链末尾，优先级最低）。
     *
     * @param loader 加载器
     */
    public void registerLoader(TemplateLoader loader) {
        loaderChain.addLoader(loader);
    }

    // ==================== 编译 API ====================

    /**
     * 编译 XML 模板源码（带缓存）。
     *
     * @param source XML 源码（以 {@code <template>} 为根）
     * @return 编译后的模板
     */
    public Template compile(String source) {
        String key = hashKey(source);
        return sourceCache.computeIfAbsent(key, k -> parser.parseXml(source));
    }

    /**
     * 编译纯文本模板源码（带缓存）。
     *
     * @param source 纯文本源码（仅含 {@code {{ }}} 插值）
     * @return 编译后的纯文本模板
     */
    public Template compileText(String source) {
        String key = "text:" + hashKey(source);
        return sourceCache.computeIfAbsent(key, k -> parser.parseText(source));
    }

    /**
     * 按名称加载并编译模板（带缓存 + 热重载检测）。
     * <p>通过注册的 {@link TemplateLoader} 链加载源码，检测 {@code lastModified} 判断是否需要重新编译。
     *
     * @param name 模板名称
     * @return 编译后的模板
     * @throws TemplateNotFoundException 找不到模板时抛出
     */
    public Template getTemplate(String name) {
        long currentModified = loaderChain.lastModified(name);
        Long cachedModified = nameLastModified.get(name);
        Template cached = nameCache.get(name);

        // 缓存命中且文件未修改（或 loader 不支持热重载）
        if (cached != null && cachedModified != null && cachedModified == currentModified) {
            return cached;
        }

        // 需要加载/重新编译
        try {
            String source = loaderChain.load(name);
            if (source == null) {
                throw new TemplateNotFoundException("模板不存在: " + name);
            }
            Template template = parser.parseXml(source);
            nameCache.put(name, template);
            nameLastModified.put(name, currentModified);
            return template;
        } catch (TemplateNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new TemplateLoadException("模板加载失败: " + name, e);
        }
    }

    // ==================== 渲染 API ====================

    /**
     * 渲染已编译的 XML 模板。
     *
     * @param template 编译后的模板
     * @param data     数据上下文
     * @return 渲染结果
     */
    public RenderResult render(Template template, DataContext data) {
        return renderer.render(template, data);
    }

    /**
     * 按名称加载并渲染 XML 模板（便捷方法，自动缓存）。
     *
     * @param name 模板名称
     * @param data 数据上下文
     * @return 渲染结果
     */
    public RenderResult render(String name, DataContext data) {
        return render(getTemplate(name), data);
    }

    /**
     * 渲染已编译的纯文本模板。
     *
     * @param template 编译后的纯文本模板
     * @param data     数据上下文
     * @return 渲染后的字符串
     */
    public String renderText(Template template, DataContext data) {
        return renderer.renderText(template, data);
    }

    /**
     * 按名称加载并渲染纯文本模板（便捷方法，自动缓存）。
     *
     * @param name 模板名称
     * @param data 数据上下文
     * @return 渲染后的字符串
     */
    public String renderText(String name, DataContext data) {
        return renderText(getTemplate(name), data);
    }

    // ==================== 缓存管理 ====================

    /** 清除所有缓存（按名称 + 按源码） */
    public void clearCache() {
        nameCache.clear();
        nameLastModified.clear();
        sourceCache.clear();
    }

    /** 清除指定名称的缓存 */
    public void invalidate(String name) {
        nameCache.remove(name);
        nameLastModified.remove(name);
    }

    // ==================== 内部工具 ====================

    /** 计算源码的 SHA-256 哈希作为缓存 key */
    private String hashKey(String source) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // 退化为字符串长度 + hashCode
            return source.length() + ":" + source.hashCode();
        }
    }

    // ==================== 插件绑定代理（ForPlugin） ====================

    /**
     * 绑定指定插件实例，返回一个绑定了该插件 ClassLoader 的模板加载作用域。
     * <p>
     * 作用域对象（{@link TemplatePluginScope}）从绑定插件的 jar 内加载模板，
     * 编译结果复用本引擎的全局缓存。
     *
     * @param plugin 插件实例
     * @return 绑定了该插件上下文的模板加载作用域
     */
    @Override
    public TemplatePluginScope forPlugin(Plugin plugin) {
        return new TemplatePluginScope(this, PluginClassLoaderFactory.getClassLoader(plugin));
    }

    /**
     * 按插件名绑定，返回一个绑定了该插件 ClassLoader 的模板加载作用域。
     *
     * @param pluginName 插件名称（需与 plugin.yml 中一致）
     * @return 绑定了该插件上下文的模板加载作用域
     */
    @Override
    public TemplatePluginScope forPlugin(String pluginName) {
        return new TemplatePluginScope(this, PluginClassLoaderFactory.getClassLoader(pluginName));
    }
}
