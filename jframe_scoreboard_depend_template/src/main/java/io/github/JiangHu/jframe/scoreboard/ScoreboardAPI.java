package io.github.JiangHu.jframe.scoreboard;

import cn.nukkit.Player;
import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.content_template.TemplateEngine;
import io.github.JiangHu.jframe.content_template.loader.ClasspathTemplateLoader;
import io.github.JiangHu.jframe.core.classloader.PluginClassLoaderFactory;
import io.github.JiangHu.jframe.core.data.reactive.DataContext;
import io.github.JiangHu.jframe.core.module.ForPlugin;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 计分板 API（<b>面向用户的统一入口</b>）：暴露计分板系统的全部公开 API。
 *
 * <p>本类是一个轻量门面（facade），自身不持有业务逻辑，仅做<b>委托转发</b>给 {@link ScoreboardManager}。
 *
 * <h3>架构定位</h3>
 * <pre>
 *   TemplateEngine（模板引擎，来自 jframe_dependency_template）
 *       ↑
 *   ScoreboardManager（模板注册表 + 玩家视图映射）
 *       ↑
 *   ScoreboardAPI（门面）  ← 本类
 * </pre>
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li><b>模板管理</b>：{@link #loadTemplate} 注册模板（支持 XML 源码或预编译 Template）</li>
 *   <li><b>显示控制</b>：{@link #show}（指定玩家）/ {@link #showIf}（条件筛选）/ {@link #showAll}（全体）</li>
 *   <li><b>隐藏控制</b>：{@link #hide} / {@link #hideAll}</li>
 *   <li><b>数据更新</b>：{@link #update}（单玩家）/ {@link #updateAll}（全体同 key）/ {@link #updateGlobal}（全局共享）—— 自动触发重新渲染</li>
 *   <li><b>数据访问</b>：{@link #getDataContext} 获取玩家专属数据上下文 / {@link #getGlobalDataContext} 获取全局数据上下文</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 1. 注册模板（从 XML 源码编译）
 * scoreboard.loadTemplate("main", """
 *     <template>
 *         <title>§e我的服务器</title>
 *         <line>§a玩家: §f{{player.name}}</line>
 *         <line>§b金币: §f{{coins}}</line>
 *     </template>
 *     """);
 *
 * // 2. 给指定玩家显示计分板
 * scoreboard.show(player, "main");
 *
 * // 3. 更新数据（自动刷新计分板）
 * scoreboard.update(player, "coins", 1000);
 *
 * // 4. 给所有在线玩家显示
 * scoreboard.showAll("main");
 *
 * // 5. 给满足条件的玩家显示（如 OP 玩家）
 * scoreboard.showIf(Player::isOp, "admin");
 *
 * // 6. 隐藏
 * scoreboard.hide(player);
 *
 * // 7. 更新全局数据（所有玩家共享，如在线人数）
 * scoreboard.updateGlobal("online", Server.getInstance().getOnlinePlayers().size());
 * }</pre>
 *
 * @see ScoreboardManager
 * @see ScoreboardTemplate
 * @see ScoreboardView
 */
public class ScoreboardAPI implements ForPlugin<ScoreboardPluginScope> {

    private final ScoreboardManager manager;
    private final TemplateEngine engine;

    /**
     * @param manager 计分板管理器
     * @param engine  模板引擎
     */
    public ScoreboardAPI(ScoreboardManager manager, TemplateEngine engine) {
        this.manager = manager;
        this.engine = engine;
    }

    // ==================== 模板管理 ====================

    /**
     * 从 XML 源码编译并注册计分板模板。
     *
     * @param name       模板名称
     * @param xmlSource  XML 源码（以 {@code <template>} 为根）
     * @return 编译并包装后的 {@link ScoreboardTemplate}
     */
    public ScoreboardTemplate loadTemplate(String name, String xmlSource) {
        Template template = engine.compile(xmlSource);
        ScoreboardTemplate sbTemplate = ScoreboardTemplate.of(name, template);
        manager.loadTemplate(sbTemplate);
        return sbTemplate;
    }

    /**
     * 注册预构建的计分板模板。
     *
     * @param template 模板配置
     */
    public void loadTemplate(ScoreboardTemplate template) {
        manager.loadTemplate(template);
    }

    /**
     * 按名称加载模板文件并注册（需先在 TemplateEngine 中注册 FileTemplateLoader）。
     *
     * @param name 模板名称（同时也是文件名）
     * @return 编译并包装后的 {@link ScoreboardTemplate}
     */
    public ScoreboardTemplate loadTemplate(String name) {
        Template template = engine.getTemplate(name);
        ScoreboardTemplate sbTemplate = ScoreboardTemplate.of(name, template);
        manager.loadTemplate(sbTemplate);
        return sbTemplate;
    }

    /**
     * 从指定插件的类路径加载模板文件并注册。
     *
     * <p>使用插件自身的 {@link ClassLoader} 构造 {@link ClasspathTemplateLoader}，
     * 从该插件 jar 内的 {@code prefix} 目录加载 XML 模板。适用于「主插件渲染、扩展插件提供模板」
     * 的场景——业务插件把模板放在自己 jar 里，调用此方法即可让 scoreboard 从该插件加载。
     *
     * <p>与 {@link #loadTemplate(String)} 的区别：后者从 {@link TemplateEngine} 全局 loader 链
     * （JFrame 自身类路径 / 已注册文件目录）加载；本方法<b>显式指定</b>从哪个插件的类路径加载，
     * 不依赖全局 loader 链，互不干扰。
     *
     * @param name   模板名称（文件名，不含 {@code .xml} 扩展名）
     * @param plugin 提供模板的插件（取其类加载器）
     * @param prefix 模板在插件 jar 中的路径前缀（如 {@code "templates/scoreboard/"}）
     * @return 编译并包装后的 {@link ScoreboardTemplate}
     * @throws RuntimeException 模板加载或编译失败时抛出
     * @deprecated 请使用 {@code forPlugin(plugin).withPrefix(prefix).loadTemplate(name)} 代替
     */
    @Deprecated
    public ScoreboardTemplate loadTemplate(String name, Plugin plugin, String prefix) {
        ClassLoader classLoader = plugin.getClass().getClassLoader();
        ClasspathTemplateLoader loader = new ClasspathTemplateLoader(prefix, ".xml", classLoader);
        try {
            String source = loader.load(name);
            Template template = engine.compile(source);
            ScoreboardTemplate sbTemplate = ScoreboardTemplate.of(name, template);
            manager.loadTemplate(sbTemplate);
            return sbTemplate;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("从插件 [" + plugin.getName()
                    + "] 加载模板失败: " + prefix + name, e);
        }
    }

    /**
     * 从指定插件的类路径加载模板文件并注册（使用默认前缀 {@code templates/}）。
     *
     * @param name   模板名称
     * @param plugin 提供模板的插件
     * @return 编译并包装后的 {@link ScoreboardTemplate}
     * @see #loadTemplate(String, Plugin, String)
     * @deprecated 请使用 {@code forPlugin(plugin).loadTemplate(name)} 代替
     */
    @Deprecated
    public ScoreboardTemplate loadTemplate(String name, Plugin plugin) {
        return loadTemplate(name, plugin, "templates/");
    }

    // ==================== 显示控制 ====================

    /**
     * 给指定玩家显示计分板。
     *
     * @param player       目标玩家
     * @param templateName 模板名称
     * @return 对应的 {@link ScoreboardView}，模板不存在返回 {@code null}
     */
    public ScoreboardView show(Player player, String templateName) {
        return manager.show(player, templateName);
    }

    /**
     * 给满足条件的在线玩家显示计分板。
     *
     * @param predicate    玩家筛选条件
     * @param templateName 模板名称
     * @return 成功显示的玩家数量
     */
    public int showIf(Predicate<Player> predicate, String templateName) {
        return manager.showIf(predicate, templateName);
    }

    /**
     * 给所有在线玩家显示计分板。
     *
     * @param templateName 模板名称
     * @return 成功显示的玩家数量
     */
    public int showAll(String templateName) {
        return manager.showAll(templateName);
    }

    // ==================== 隐藏控制 ====================

    /**
     * 隐藏指定玩家的计分板。
     *
     * @param player 目标玩家
     */
    public void hide(Player player) {
        manager.hide(player);
    }

    /**
     * 隐藏所有玩家的计分板。
     */
    public void hideAll() {
        manager.hideAll();
    }

    // ==================== 数据更新 ====================

    /**
     * 更新指定玩家的计分板数据，自动触发重新渲染。
     *
     * @param player 目标玩家
     * @param key    数据键名
     * @param value  数据值
     */
    public void update(Player player, String key, Object value) {
        manager.update(player, key, value);
    }

    /**
     * 批量更新指定玩家的计分板数据，只触发一次重新渲染。
     *
     * @param player  目标玩家
     * @param entries 键值对
     */
    public void update(Player player, Map<String, Object> entries) {
        manager.update(player, entries);
    }

    /**
     * 更新所有正在显示计分板的玩家的同一数据项。
     *
     * @param key   数据键名
     * @param value 数据值
     */
    public void updateAll(String key, Object value) {
        manager.updateAll(key, value);
    }

    /**
     * 批量更新所有正在显示计分板的玩家的同一批数据。
     *
     * @param entries 键值对
     */
    public void updateAll(Map<String, Object> entries) {
        manager.updateAll(entries);
    }

    /**
     * 更新全局数据——所有正在显示计分板的玩家都会自动收到变更并增量刷新。
     * <p>全局数据对所有玩家共享，适合存放服务器名称、在线人数、当前时间等公共信息。
     * <p>当玩家数据与全局数据存在同名 key 时，<b>玩家数据优先</b>（覆盖全局值）。
     *
     * @param key   全局数据键名
     * @param value 数据值
     */
    public void updateGlobal(String key, Object value) {
        manager.updateGlobal(key, value);
    }

    /**
     * 批量更新全局数据——所有正在显示计分板的玩家都会自动收到变更并增量刷新。
     *
     * @param entries 键值对
     * @see #updateGlobal(String, Object)
     */
    public void updateGlobalAll(Map<String, Object> entries) {
        manager.updateGlobalAll(entries);
    }

    /**
     * 更新模板全局数据——所有正在使用该模板的玩家都会自动收到变更并增量刷新。
     * <p>模板全局数据对同模板的所有玩家共享，不同模板之间隔离。
     * 适合存放该模板特有的公共信息（如游戏模式、队伍名称等）。
     * <p>读取优先级：玩家 > 模板 > 引擎。同名 key 玩家覆盖模板，模板覆盖引擎全局。
     *
     * @param templateName 模板名称
     * @param key          数据键名
     * @param value        数据值
     */
    public void updateTemplate(String templateName, String key, Object value) {
        manager.updateTemplate(templateName, key, value);
    }

    /**
     * 批量更新模板全局数据——所有正在使用该模板的玩家都会自动收到变更并增量刷新。
     *
     * @param templateName 模板名称
     * @param entries      键值对
     * @see #updateTemplate(String, String, Object)
     */
    public void updateTemplateAll(String templateName, Map<String, Object> entries) {
        manager.updateTemplateAll(templateName, entries);
    }

    // ==================== 数据访问 ====================

    /**
     * 获取指定玩家的数据上下文（可直接操作数据，变更会自动触发渲染）。
     *
     * @param player 目标玩家
     * @return 数据上下文，不存在返回 {@code null}
     */
    public DataContext getDataContext(Player player) {
        return manager.getDataContext(player);
    }

    /**
     * 获取全局数据上下文（可直接操作全局数据，变更会自动传播到所有玩家视图）。
     * <p>全局数据对所有玩家共享，适合存放服务器名称、在线人数、当前时间等公共信息。
     *
     * @return 全局数据上下文
     */
    public DataContext getGlobalDataContext() {
        return manager.getGlobalDataContext();
    }

    /**
     * 获取指定模板的全局数据上下文（可直接操作模板级数据，变更会自动传播到使用该模板的所有玩家视图）。
     * <p>模板全局数据对同模板的所有玩家共享，不同模板之间隔离。
     * 首次访问时自动创建（parent 为引擎全局数据）。
     *
     * @param templateName 模板名称
     * @return 模板全局数据上下文
     */
    public DataContext getTemplateDataContext(String templateName) {
        return manager.getTemplateDataContext(templateName);
    }

    /**
     * 获取指定玩家的计分板视图。
     *
     * @param player 目标玩家
     * @return 视图，不存在返回 {@code null}
     */
    public ScoreboardView getView(Player player) {
        return manager.getView(player);
    }

    // ==================== 生命周期 ====================

    /**
     * 玩家退出时清理资源——应在 {@code PlayerQuitEvent} 中调用。
     *
     * @param player 退出的玩家
     */
    public void onPlayerQuit(Player player) {
        manager.onPlayerQuit(player);
    }

    // ==================== 内部组件访问 ====================

    /** 获取底层管理器（高级用法） */
    public ScoreboardManager getManager() {
        return manager;
    }

    /** 获取模板引擎 */
    public TemplateEngine getEngine() {
        return engine;
    }

    // ==================== 插件绑定代理（ForPlugin） ====================

    /**
     * 绑定指定插件实例，返回一个绑定了该插件 ClassLoader 的记分板作用域。
     * <p>
     * 作用域对象（{@link ScoreboardPluginScope}）从绑定插件的 jar 内加载模板，
     * 注册结果统一到本 API 共享的 {@link ScoreboardManager}。
     *
     * @param plugin 插件实例
     * @return 绑定了该插件上下文的记分板作用域
     */
    @Override
    public ScoreboardPluginScope forPlugin(Plugin plugin) {
        return new ScoreboardPluginScope(this, PluginClassLoaderFactory.getClassLoader(plugin));
    }

    /**
     * 按插件名绑定，返回一个绑定了该插件 ClassLoader 的记分板作用域。
     *
     * @param pluginName 插件名称（需与 plugin.yml 中一致）
     * @return 绑定了该插件上下文的记分板作用域
     */
    @Override
    public ScoreboardPluginScope forPlugin(String pluginName) {
        return new ScoreboardPluginScope(this, PluginClassLoaderFactory.getClassLoader(pluginName));
    }

    /**
     * 静态工厂——创建 ScoreboardAPI。
     * <p>计分板使用 Nukkit 原生 {@code IScoreboard} API 显示给客户端，
     * 由 Nukkit-MOT 内部处理客户端同步，无需延迟初始化。
     *
     * @param engine 模板引擎
     * @return ScoreboardAPI 实例
     */
    public static ScoreboardAPI create(TemplateEngine engine) {
        ScoreboardManager manager = new ScoreboardManager(engine);
        return new ScoreboardAPI(manager, engine);
    }
}
