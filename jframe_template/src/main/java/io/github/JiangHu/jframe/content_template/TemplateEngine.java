package io.github.JiangHu.jframe.content_template;

import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.content_template.loader.CompositeTemplateLoader;
import io.github.JiangHu.jframe.content_template.loader.TemplateLoader;
import io.github.JiangHu.jframe.content_template.parser.TemplateParser;
import io.github.JiangHu.jframe.content_template.render.TemplateRenderer;
import io.github.JiangHu.jframe.core.JFrameLog;
import io.github.JiangHu.jframe.core.classloader.PluginClassLoaderFactory;
import io.github.JiangHu.jframe.core.data.reactive.DataContext;
import io.github.JiangHu.jframe.core.module.ForPlugin;
import java.util.UUID;
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

    /**
     * 全局数据上下文——所有玩家共享的数据（如服务器名称、在线人数）。
     * <p>配合 {@link HierarchicalDataContext} 使用：玩家 context 以此为 parent，
     * 渲染时自动合并全局 + 玩家数据（玩家同名 key 优先）。
     *
     * @see HierarchicalDataContext
     */
    private final DataContext globalData = DataContext.of();

    /**
     * 模板级全局数据上下文映射——按模板名称隔离的共享数据（第二层）。
     * <p>每个模板名对应一个 {@link HierarchicalDataContext}，parent 为 {@link #globalData}（第一层）。
     * 同一模板的所有玩家共享此数据（如公会战分数、副本进度、队伍信息）。
     * <p>下游模块（scoreboard、inventory、bossbar 等）通过 {@link #getTemplateData} 获取，
     * 再以返回值为 parent 创建玩家级 {@link HierarchicalDataContext}（第三层），形成完整三层链。
     *
     * @see #getTemplateData(String)
     * @see HierarchicalDataContext
     */
    private final ConcurrentHashMap<String, HierarchicalDataContext> templateGlobals = new ConcurrentHashMap<>();

    /**
     * 玩家级全局数据上下文映射——按玩家 UUID 隔离的共享数据（第三层，Session 作用域）。
     * <p>每个玩家 UUID 对应一个 {@link HierarchicalDataContext}，parent 为 {@link #globalData}（第一层，引擎全局）。
     * 该玩家的所有模板、所有下游模块（scoreboard、bossbar、inventory 等）共享同一实例。
     * <p><b>核心收益</b>：玩家核心数据（coins、level、player.name 等）独立于具体模板，
     * 切换计分板时纹丝不动；玩家退出时通过 {@link #removePlayerData} 统一 dispose。
     *
     * @see #getPlayerData(UUID)
     * @see HierarchicalDataContext
     */
    private final ConcurrentHashMap<UUID, HierarchicalDataContext> playerGlobals = new ConcurrentHashMap<>();

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

    // ==================== 全局数据 API ====================

    /**
     * 获取全局数据上下文（所有玩家共享）。
     * <p>返回的 DataContext 可直接操作（put / putAll / onChange），
     * 修改会自动传播到所有以它为 parent 的 {@link HierarchicalDataContext}。
     *
     * @return 全局 DataContext
     * @see HierarchicalDataContext
     */
    public DataContext getGlobalData() {
        return globalData;
    }

    /**
     * 写入单个全局数据（所有玩家共享），触发变更通知。
     * <p>等价于 {@code getGlobalData().put(key, value)}。
     *
     * @param key   键名
     * @param value 值
     * @return this（链式调用）
     */
    public TemplateEngine setGlobal(String key, Object value) {
        globalData.put(key, value);
        return this;
    }

    /**
     * 批量写入全局数据（只触发一次变更通知）。
     * <p>等价于 {@code getGlobalData().putAll(data)}。
     *
     * @param data 键值对集合
     * @return this（链式调用）
     */
    public TemplateEngine setGlobalAll(java.util.Map<String, Object> data) {
        if (data != null && !data.isEmpty()) {
            globalData.putAll(data);
        }
        return this;
    }

    // ==================== 模板全局数据 API（第二层） ====================

    /**
     * 获取指定模板的全局数据上下文（同一模板的所有玩家共享）。
     * <p>首次调用时自动创建 {@link HierarchicalDataContext}，parent 为 {@link #globalData}（引擎全局）。
     * 后续对同一模板名的调用返回同一实例（缓存）。
     *
     * <p><b>三层数据粒度</b>：
     * <pre>{@code
     * 引擎全局（getGlobalData）        ← 所有模板、所有玩家共享
     *     ↑ parent
     * 模板全局（getTemplateData(name）  ← 同一模板的所有玩家共享
     *     ↑ parent
     * 玩家本地（下游模块创建）          ← 仅该玩家可见
     * }</pre>
     *
     * <p>下游模块使用示例：
     * <pre>{@code
     * DataContext templateGlobal = engine.getTemplateData("main");
     * HierarchicalDataContext playerData = HierarchicalDataContext.of(templateGlobal);
     * playerData.put("coins", 1000);  // 玩家本地数据
     * }</pre>
     *
     * @param templateName 模板名称
     * @return 模板级全局数据上下文（parent 为引擎全局）
     * @see HierarchicalDataContext
     */
    public DataContext getTemplateData(String templateName) {
        return templateGlobals.computeIfAbsent(templateName,
                k -> HierarchicalDataContext.of(globalData));
    }

    /**
     * 写入单个模板全局数据（同一模板的所有玩家共享），触发变更通知。
     * <p>等价于 {@code getTemplateData(templateName).put(key, value)}。
     *
     * @param templateName 模板名称
     * @param key          键名
     * @param value        值
     * @return this（链式调用）
     */
    public TemplateEngine setTemplateData(String templateName, String key, Object value) {
        getTemplateData(templateName).put(key, value);
        return this;
    }

    /**
     * 批量写入模板全局数据（只触发一次变更通知）。
     * <p>等价于 {@code getTemplateData(templateName).putAll(data)}。
     *
     * @param templateName 模板名称
     * @param data         键值对集合
     * @return this（链式调用）
     */
    public TemplateEngine setTemplateDataAll(String templateName, java.util.Map<String, Object> data) {
        if (data != null && !data.isEmpty()) {
            getTemplateData(templateName).putAll(data);
        }
        return this;
    }

    /**
     * 移除并释放指定模板的全局数据上下文。
     * <p>调用 {@link HierarchicalDataContext#dispose()} 断开与引擎全局的监听器引用，
     * 然后从内部映射中移除，避免内存泄漏。
     *
     * <p><b>使用场景</b>：模板注销（{@code removeTemplate}）时调用，
     * 确保不再有玩家使用该模板时释放资源。
     *
     * @param templateName 模板名称
     * @return 被移除的数据上下文（可能为 null，如果之前未创建过）
     */
    public DataContext removeTemplateData(String templateName) {
        HierarchicalDataContext removed = templateGlobals.remove(templateName);
        if (removed != null) {
            removed.dispose();
        }
        return removed;
    }

    // ==================== 玩家全局数据 API（第三层，Session 作用域） ====================

    /**
     * 获取指定玩家的全局数据上下文（Session 作用域，该玩家所有模板/下游模块共享）。
     * <p>首次调用时自动创建 {@link HierarchicalDataContext}，parent 为 {@link #globalData}（引擎全局）。
     * 后续对同一 UUID 的调用返回同一实例（缓存）。
     * <p>玩家核心数据（coins、level 等）应存放于此层，切换计分板时不会丢失。
     *
     * <pre>{@code
     * // 存入玩家核心数据（所有计分板共享）
     * engine.setPlayerData(uuid, "coins", 1000);
     * engine.setPlayerData(uuid, "level", 42);
     *
     * // 计分板 view 以玩家全局为 parent 创建局部层
     * DataContext playerGlobal = engine.getPlayerData(uuid);
     * HierarchicalDataContext viewLocal = HierarchicalDataContext.of(playerGlobal);
     * }</pre>
     *
     * @param uuid 玩家 UUID
     * @return 玩家级全局数据上下文（parent 为引擎全局）
     * @see HierarchicalDataContext
     */
    public DataContext getPlayerData(UUID uuid) {
        return playerGlobals.computeIfAbsent(uuid,
                k -> HierarchicalDataContext.of(globalData));
    }

    /**
     * 写入单个玩家全局数据（Session 作用域），触发变更通知。
     * <p>等价于 {@code getPlayerData(uuid).put(key, value)}。
     *
     * @param uuid  玩家 UUID
     * @param key   键名
     * @param value 值
     * @return this（链式调用）
     */
    public TemplateEngine setPlayerData(UUID uuid, String key, Object value) {
        getPlayerData(uuid).put(key, value);
        return this;
    }

    /**
     * 批量写入玩家全局数据（只触发一次变更通知）。
     *
     * @param uuid 玩家 UUID
     * @param data 键值对集合
     * @return this（链式调用）
     */
    public TemplateEngine setPlayerDataAll(UUID uuid, java.util.Map<String, Object> data) {
        if (data != null && !data.isEmpty()) {
            getPlayerData(uuid).putAll(data);
        }
        return this;
    }

    /**
     * 移除并释放指定玩家的全局数据上下文。
     * <p>调用 {@link HierarchicalDataContext#dispose()} 断开与引擎全局的监听器引用，
     * 然后从内部映射中移除，避免内存泄漏。
     * <p><b>使用场景</b>：玩家退出游戏时调用，确保释放该玩家所有 Session 级资源。
     *
     * @param uuid 玩家 UUID
     * @return 被移除的数据上下文（可能为 null，如果之前未创建过）
     */
    public DataContext removePlayerData(UUID uuid) {
        HierarchicalDataContext removed = playerGlobals.remove(uuid);
        if (removed != null) {
            removed.dispose();
        }
        return removed;
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
            logInfo("模板加载成功: " + name);
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

    /**
     * 安全输出 INFO 日志。
     * <p>当 Nukkit Server 尚未初始化时（如单元测试环境）静默忽略，避免抛出异常。
     *
     * @param message 日志消息
     */
    private void logInfo(String message) {
        try {
            JFrameLog.info("TemplateEngine", message);
        } catch (IllegalStateException ignored) {
            // Server 尚未初始化（如单元测试环境），静默忽略
        }
    }

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

    /**
     * 直接按 ClassLoader 绑定，返回一个绑定了该类加载器的模板加载作用域。
     * <p>适用于下游模块（如 scoreboard）已持有 ClassLoader 的场景，避免重复通过
     * 插件名/插件实例间接获取。本方法是 {@link ForPlugin} 契约之外的扩展入口，
     * 供跨模块委托加载时使用。
     *
     * @param classLoader 类加载器（通常为目标插件的 PluginClassLoader）
     * @return 绑定了该类加载器的模板加载作用域
     */
    public TemplatePluginScope forPlugin(ClassLoader classLoader) {
        return new TemplatePluginScope(this, classLoader);
    }
}
