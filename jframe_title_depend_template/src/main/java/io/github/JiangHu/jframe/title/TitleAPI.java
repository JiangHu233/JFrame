package io.github.JiangHu.jframe.title;

import cn.nukkit.Player;
import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.content_template.TemplateEngine;
import io.github.JiangHu.jframe.core.classloader.PluginClassLoaderFactory;
import io.github.JiangHu.jframe.core.data.reactive.DataContext;
import io.github.JiangHu.jframe.core.module.ForPlugin;

import java.util.Map;
import java.util.function.Predicate;

/**
 * 标题 API（<b>面向用户的统一入口</b>）：暴露标题系统的全部公开 API。
 *
 * <p>本类是一个轻量门面（facade），自身不持有业务逻辑，仅做<b>委托转发</b>给 {@link TitleManager}。
 *
 * <h3>架构定位</h3>
 * <pre>
 *   TemplateEngine（模板引擎，来自 jframe_template）
 *       ↑
 *   TitleManager（模板注册表 + TITLE/ACTIONBAR 双槽位视图管理）
 *       ↑
 *   TitleAPI（门面）  ← 本类
 * </pre>
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li><b>模板管理</b>：{@link #loadTemplate} 注册模板（支持 XML 源码、按名加载或预构建 TitleTemplate）</li>
 *   <li><b>显示控制</b>：{@link #show}（指定玩家）/ {@link #showIf}（条件筛选）/ {@link #showAll}（全体）</li>
 *   <li><b>隐藏控制</b>：{@link #hide} / {@link #hideAll}</li>
 *   <li><b>数据更新</b>：{@link #updatePlayer}（玩家全局 Session）/ {@link #update}（双槽激活视图局部）/
 *       {@link #updateAll}（全体同 key）/ {@link #updateGlobal}（引擎全局）/
 *       {@link #updateTemplate}（模板全局）—— 自动触发增量渲染</li>
 *   <li><b>数据访问</b>：{@link #getPlayerDataContext}（玩家全局）/ {@link #getDataContext}（TITLE 槽局部）/
 *       {@link #getGlobalDataContext}（引擎全局）/ {@link #getTemplateDataContext}（模板全局）</li>
 *   <li><b>双槽查询</b>：{@link #getView}（TITLE 槽视图）/ {@link #getActionBarView}（ACTIONBAR 槽视图）</li>
 * </ul>
 *
 * <h3>双模式使用示例</h3>
 * <pre>{@code
 * // 1. 注册瞬时公告模板（元数据声明时序与模式）
 * title.loadTemplate("welcome", """
 *     <template>
 *         <meta key="title.mode" value="transient"/>
 *         <meta key="title.timing.fadeIn" value="10"/>
 *         <meta key="title.timing.stay" value="60"/>
 *         <meta key="title.timing.fadeOut" value="20"/>
 *         <title>§e欢迎来到服务器</title>
 *         <line>§a{{player.name}}</line>
 *     </template>
 *     """);
 *
 * // 2. 显示（瞬时：到期自动清除并回收；常驻：KeepAlive + 动作栏周期续期）
 * title.show(player, "welcome");
 *
 * // 3. 更新玩家全局数据（Session，所有标题视图共享）
 * title.updatePlayer(player, "coins", 1000);
 *
 * // 4. 注册常驻动作栏模板（仅占 ACTIONBAR 槽，与 TITLE 槽公告共存）
 * title.loadTemplate("hud", """
 *     <template>
 *         <meta key="title.subtitle.from" value="0"/>
 *         <meta key="title.subtitle.to" value="0"/>
 *         <meta key="title.actionbar.refresh" value="40"/>
 *         <line>§b金币: {{coins}}</line>
 *     </template>
 *     """);
 * title.show(player, "hud");
 * }</pre>
 *
 * @see TitleManager
 * @see TitleTemplate
 * @see TitleView
 */
public class TitleAPI implements ForPlugin<TitlePluginScope> {

    private final TitleManager manager;
    private final TemplateEngine engine;

    /**
     * @param manager 标题管理器
     * @param engine  模板引擎
     */
    public TitleAPI(TitleManager manager, TemplateEngine engine) {
        this.manager = manager;
        this.engine = engine;
    }

    // ==================== 模板管理 ====================

    /**
     * 从 XML 源码编译并注册标题模板（三级配置融合：Builder 显式 > 模板元数据 > 内置默认）。
     *
     * @param name      模板名称
     * @param xmlSource XML 源码（以 {@code <template>} 为根）
     * @return 编译并包装后的 {@link TitleTemplate}
     */
    public TitleTemplate loadTemplate(String name, String xmlSource) {
        return manager.loadTemplate(name, xmlSource);
    }

    /**
     * 注册预构建的标题模板（Builder 定制产物）。
     *
     * @param template 模板配置
     * @return 同一模板（链式）
     */
    public TitleTemplate loadTemplate(TitleTemplate template) {
        return manager.loadTemplate(template);
    }

    /**
     * 按名称从引擎 loader 链加载模板文件并注册。
     *
     * @param name 模板名称（同时也是文件名）
     * @return 编译并包装后的 {@link TitleTemplate}；引擎无此模板时返回 null
     */
    public TitleTemplate loadTemplate(String name) {
        return manager.loadTemplate(name);
    }

    /**
     * 移除模板：销毁全部玩家的对应视图并清理槽位标记、模板级数据
     *
     * @param name 模板名
     */
    public void removeTemplate(String name) {
        manager.removeTemplate(name);
    }

    // ==================== 显示控制 ====================

    /**
     * 给指定玩家显示标题（双槽位切换：先停用同槽位当前视图再激活目标视图）。
     *
     * @param player       目标玩家
     * @param templateName 模板名称
     * @return 对应的 {@link TitleView}，模板不存在返回 {@code null}
     */
    public TitleView show(Player player, String templateName) {
        return manager.show(player, templateName);
    }

    /**
     * 给满足条件的在线玩家显示标题。
     *
     * @param predicate    玩家筛选条件
     * @param templateName 模板名称
     * @return 成功显示的玩家数量
     */
    public int showIf(Predicate<Player> predicate, String templateName) {
        return manager.showIf(predicate, templateName);
    }

    /**
     * 给所有在线玩家显示标题。
     *
     * @param templateName 模板名称
     * @return 成功显示的玩家数量
     */
    public int showAll(String templateName) {
        return manager.showAll(templateName);
    }

    // ==================== 隐藏控制 ====================

    /**
     * 隐藏指定玩家当前激活的双槽视图（KeepAlive：可再 show 恢复）。
     *
     * @param player 目标玩家
     */
    public void hide(Player player) {
        manager.hide(player);
    }

    /** 隐藏所有在线玩家的双槽视图。 */
    public void hideAll() {
        manager.hideAll();
    }

    // ==================== 数据更新 ====================

    /**
     * 更新指定玩家的<b>玩家全局数据</b>（Session 作用域），自动触发该玩家全部视图增量刷新。
     * <p>玩家全局数据对该玩家所有标题视图、所有下游模块共享。适合存放玩家核心数据
     * （coins、level、player.name 等），切换标题模板时不会丢失。</p>
     *
     * @param player 目标玩家
     * @param key    数据键名
     * @param value  数据值
     */
    public void updatePlayer(Player player, String key, Object value) {
        manager.updatePlayer(player, key, value);
    }

    /**
     * 批量更新指定玩家的玩家全局数据（只触发一次变更通知）。
     *
     * @param player  目标玩家
     * @param entries 键值对
     */
    public void updatePlayerAll(Player player, Map<String, Object> entries) {
        manager.updatePlayerAll(player, entries);
    }

    /**
     * 更新指定玩家<b>当前激活视图</b>的局部数据（Request 作用域，遍历双槽激活视图），
     * 自动触发增量渲染。
     *
     * @param player 目标玩家
     * @param key    数据键名
     * @param value  数据值
     */
    public void update(Player player, String key, Object value) {
        manager.update(player, key, value);
    }

    /**
     * 批量更新指定玩家当前激活视图的局部数据，只触发一次增量渲染。
     *
     * @param player  目标玩家
     * @param entries 键值对
     */
    public void update(Player player, Map<String, Object> entries) {
        manager.update(player, entries);
    }

    /**
     * 更新所有正在显示标题的玩家的同一数据项（玩家全局层）。
     *
     * @param key   数据键名
     * @param value 数据值
     */
    public void updateAll(String key, Object value) {
        manager.updateAll(key, value);
    }

    /**
     * 批量更新所有正在显示标题的玩家的同一批数据（玩家全局层）。
     *
     * @param entries 键值对
     */
    public void updateAll(Map<String, Object> entries) {
        manager.updateAll(entries);
    }

    /**
     * 更新引擎全局数据——所有正在显示标题的玩家都会自动收到变更并增量刷新。
     * <p>全局数据对所有玩家共享，适合存放服务器名称、在线人数、当前时间等公共信息。</p>
     *
     * @param key   全局数据键名
     * @param value 数据值
     */
    public void updateGlobal(String key, Object value) {
        manager.updateGlobal(key, value);
    }

    /**
     * 批量更新引擎全局数据。
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
     * 适合存放该模板特有的公共信息（如游戏模式、队伍名称等）。</p>
     * <p>读取优先级：玩家 > 模板 > 引擎。同名 key 玩家覆盖模板，模板覆盖引擎全局。</p>
     *
     * @param templateName 模板名称
     * @param key          数据键名
     * @param value        数据值
     */
    public void updateTemplate(String templateName, String key, Object value) {
        manager.updateTemplate(templateName, key, value);
    }

    /**
     * 批量更新模板全局数据。
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
     * 获取指定玩家的<b>玩家全局</b>数据上下文（Session 作用域）。
     * <p>该玩家所有标题视图、所有下游模块共享同一实例。变更会自动传播到所有以它为 parent 的视图。</p>
     *
     * @param player 目标玩家
     * @return 玩家全局数据上下文
     */
    public DataContext getPlayerDataContext(Player player) {
        return manager.getPlayerDataContext(player);
    }

    /**
     * 获取指定玩家<b>当前 TITLE 槽激活视图</b>的局部数据上下文（Request 作用域）。
     * <p>变更会自动触发该视图的增量渲染。</p>
     *
     * @param player 目标玩家
     * @return 局部数据上下文，不存在返回 {@code null}
     */
    public DataContext getDataContext(Player player) {
        return manager.getDataContext(player);
    }

    /**
     * 获取引擎全局数据上下文（可直接操作全局数据，变更会自动传播到所有玩家视图）。
     *
     * @return 全局数据上下文
     */
    public DataContext getGlobalDataContext() {
        return manager.getGlobalDataContext();
    }

    /**
     * 获取指定模板的全局数据上下文（可直接操作模板级数据，变更会自动传播到使用该模板的所有玩家视图）。
     * <p>模板全局数据对同模板的所有玩家共享，不同模板之间隔离。</p>
     *
     * @param templateName 模板名称
     * @return 模板全局数据上下文
     */
    public DataContext getTemplateDataContext(String templateName) {
        return manager.getTemplateDataContext(templateName);
    }

    /**
     * 获取指定玩家当前 TITLE 槽激活的标题视图。
     *
     * @param player 目标玩家
     * @return 视图，不存在返回 {@code null}
     */
    public TitleView getView(Player player) {
        return manager.getView(player);
    }

    /**
     * 获取指定玩家当前 ACTIONBAR 槽激活的标题视图（双槽查询扩展）。
     *
     * @param player 目标玩家
     * @return 视图，不存在返回 {@code null}
     */
    public TitleView getActionBarView(Player player) {
        return manager.getActionBarView(player);
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

    /** 销毁全部视图并清空缓存与槽位标记（模块停用入口）。 */
    public void disposeAll() {
        manager.disposeAll();
    }

    // ==================== 内部组件访问 ====================

    /** 获取底层管理器（高级用法） */
    public TitleManager getManager() {
        return manager;
    }

    /** 获取模板引擎 */
    public TemplateEngine getEngine() {
        return engine;
    }

    // ==================== 插件绑定代理（ForPlugin） ====================

    /**
     * 绑定指定插件实例，返回一个绑定了该插件 ClassLoader 的标题作用域。
     * <p>
     * 作用域对象（{@link TitlePluginScope}）从绑定插件的 jar 内加载模板，
     * 注册结果统一到本 API 共享的 {@link TitleManager}。
     *
     * @param plugin 插件实例
     * @return 绑定了该插件上下文的标题作用域
     */
    @Override
    public TitlePluginScope forPlugin(Plugin plugin) {
        return new TitlePluginScope(this, PluginClassLoaderFactory.getClassLoader(plugin));
    }

    /**
     * 按插件名绑定，返回一个绑定了该插件 ClassLoader 的标题作用域。
     *
     * @param pluginName 插件名称（需与 plugin.yml 中一致）
     * @return 绑定了该插件上下文的标题作用域
     */
    @Override
    public TitlePluginScope forPlugin(String pluginName) {
        return new TitlePluginScope(this, PluginClassLoaderFactory.getClassLoader(pluginName));
    }

    /**
     * 静态工厂——创建 TitleAPI。
     *
     * @param engine 模板引擎
     * @return TitleAPI 实例
     */
    public static TitleAPI create(TemplateEngine engine) {
        TitleManager manager = new TitleManager(engine);
        return new TitleAPI(manager, engine);
    }
}
