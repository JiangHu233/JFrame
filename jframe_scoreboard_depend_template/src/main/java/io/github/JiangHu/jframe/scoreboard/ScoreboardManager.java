package io.github.JiangHu.jframe.scoreboard;

import cn.nukkit.Player;
import cn.nukkit.Server;
import io.github.JiangHu.jframe.content_template.HierarchicalDataContext;
import io.github.JiangHu.jframe.content_template.TemplateEngine;
import io.github.JiangHu.jframe.core.JFrameLog;
import io.github.JiangHu.jframe.core.data.reactive.DataContext;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 计分板管理器——管理 {@link ScoreboardTemplate}（模板注册表）和 {@link ScoreboardView}（玩家视图）。
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li><b>模板注册</b>：{@code loadTemplate} / {@code getTemplate} / {@code removeTemplate}</li>
 *   <li><b>显示控制</b>：{@code show}（指定玩家）/ {@code showIf}（条件筛选）/ {@code showAll}（全体）</li>
 *   <li><b>隐藏控制</b>：{@code hide} / {@code hideAll}</li>
 *   <li><b>数据更新</b>：{@code update}（单玩家）/ {@code updateAll}（全体同 key）</li>
 *   <li><b>生命周期</b>：{@code onPlayerQuit} 玩家退出时自动清理 Nukkit 资源</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>使用 {@link ConcurrentHashMap} 存储模板和视图，线程安全。
 * 但发包操作应在主线程执行。
 *
 * @see ScoreboardTemplate
 * @see ScoreboardView
 */
public class ScoreboardManager {

    private final TemplateEngine engine;

    /** 模板注册表：模板名称 → ScoreboardTemplate */
    private final ConcurrentHashMap<String, ScoreboardTemplate> templates = new ConcurrentHashMap<>();

    /** 玩家视图映射：玩家 UUID → ScoreboardView */
    private final ConcurrentHashMap<UUID, ScoreboardView> views = new ConcurrentHashMap<>();

    /**
     * @param engine 模板引擎
     */
    public ScoreboardManager(TemplateEngine engine) {
        this.engine = engine;
    }

    // ==================== 模板管理 ====================

    /**
     * 注册计分板模板。
     *
     * @param template 模板配置
     */
    public void loadTemplate(ScoreboardTemplate template) {
        templates.put(template.getName(), template);
        logInfo("计分板模板注册成功: " + template.getName());
    }

    /**
     * 获取已注册的模板。
     *
     * @param name 模板名称
     * @return 模板配置，不存在返回 {@code null}
     */
    public ScoreboardTemplate getTemplate(String name) {
        return templates.get(name);
    }

    /**
     * 移除已注册的模板。
     *
     * @param name 模板名称
     */
    public void removeTemplate(String name) {
        templates.remove(name);
        // 清理模板全局数据上下文（委托 TemplateEngine），解除对引擎全局数据的监听，防止内存泄漏
        engine.removeTemplateData(name);
    }

    // ==================== 显示控制 ====================

    /**
     * 给指定玩家显示计分板。
     * <p>如果玩家已有计分板，先隐藏旧的再显示新的。
     *
     * @param player       目标玩家
     * @param templateName 模板名称
     * @return 对应的 {@link ScoreboardView}，模板不存在返回 {@code null}
     */
    public ScoreboardView show(Player player, String templateName) {
        ScoreboardTemplate sbTemplate = templates.get(templateName);
        if (sbTemplate == null) {
            logWarning("计分板模板未注册: " + templateName + "，请检查 loadTemplate 名称是否与 show 一致");
            return null;
        }
        return show(player, sbTemplate);
    }

    /**
     * 给指定玩家显示计分板（直接传入模板对象）。
     *
     * @param player     目标玩家
     * @param sbTemplate 模板配置
     * @return 对应的 {@link ScoreboardView}
     */
    public ScoreboardView show(Player player, ScoreboardTemplate sbTemplate) {
        UUID uuid = player.getUniqueId();

        // 先隐藏已有计分板
        ScoreboardView oldView = views.get(uuid);
        if (oldView != null && oldView.isShown()) {
            oldView.hide(player);
        }

        // 创建新视图并显示
        // 三层 parent 链：引擎全局 → 模板全局 → 玩家局部（由 TemplateEngine 统一管理前两层）
        // 读取优先级：玩家 > 模板 > 引擎；任一层变更都会沿链自动传播，触发增量刷新
        DataContext templateGlobal = engine.getTemplateData(sbTemplate.getName());
        HierarchicalDataContext data = HierarchicalDataContext.of(templateGlobal);
        ScoreboardView view = new ScoreboardView(uuid, sbTemplate, data, engine);
        view.show(player);
        views.put(uuid, view);
        return view;
    }

    /**
     * 给满足条件的在线玩家显示计分板。
     *
     * @param predicate    玩家筛选条件
     * @param templateName 模板名称
     * @return 成功显示的玩家数量
     */
    public int showIf(Predicate<Player> predicate, String templateName) {
        ScoreboardTemplate sbTemplate = templates.get(templateName);
        if (sbTemplate == null) {
            logWarning("计分板模板未注册: " + templateName + "，请检查 loadTemplate 名称是否与 show 一致");
            return 0;
        }
        int count = 0;
        for (Player player : Server.getInstance().getOnlinePlayers().values()) {
            if (predicate.test(player)) {
                show(player, sbTemplate);
                count++;
            }
        }
        return count;
    }

    /**
     * 给所有在线玩家显示计分板。
     *
     * @param templateName 模板名称
     * @return 成功显示的玩家数量
     */
    public int showAll(String templateName) {
        return showIf(player -> true, templateName);
    }

    // ==================== 隐藏控制 ====================

    /**
     * 隐藏指定玩家的计分板。
     *
     * @param player 目标玩家
     */
    public void hide(Player player) {
        UUID uuid = player.getUniqueId();
        ScoreboardView view = views.remove(uuid);
        if (view != null) {
            view.hide(player);
            disposeDataContext(view);
        }
    }

    /**
     * 隐藏所有玩家的计分板。
     */
    public void hideAll() {
        for (Map.Entry<UUID, ScoreboardView> entry : views.entrySet()) {
            Player player = Server.getInstance().getOnlinePlayers().get(entry.getKey());
            if (player != null) {
                entry.getValue().hide(player);
            }
            disposeDataContext(entry.getValue());
        }
        views.clear();
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
        ScoreboardView view = views.get(player.getUniqueId());
        if (view != null) {
            view.set(key, value);
        }
    }

    /**
     * 更新指定玩家的计分板数据（批量），只触发一次重新渲染。
     *
     * @param player  目标玩家
     * @param entries 键值对
     */
    public void update(Player player, Map<String, Object> entries) {
        ScoreboardView view = views.get(player.getUniqueId());
        if (view != null) {
            view.setAll(entries);
        }
    }

    /**
     * 更新所有正在显示计分板的玩家的同一数据项。
     *
     * @param key   数据键名
     * @param value 数据值
     */
    public void updateAll(String key, Object value) {
        for (ScoreboardView view : views.values()) {
            view.set(key, value);
        }
    }

    /**
     * 更新所有正在显示计分板的玩家的同一批数据（批量），每个玩家只触发一次重新渲染。
     *
     * @param entries 键值对
     */
    public void updateAll(Map<String, Object> entries) {
        for (ScoreboardView view : views.values()) {
            view.setAll(entries);
        }
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
        engine.setGlobal(key, value);
    }

    /**
     * 批量更新全局数据——所有正在显示计分板的玩家都会自动收到变更并增量刷新。
     *
     * @param entries 键值对
     * @see #updateGlobal(String, Object)
     */
    public void updateGlobalAll(Map<String, Object> entries) {
        engine.setGlobalAll(entries);
    }

    /**
     * 更新模板全局数据——所有正在使用该模板的玩家都会自动收到变更并增量刷新。
     * <p>模板全局数据对同模板的所有玩家共享，不同模板之间隔离。
     * 适合存放该模板特有的公共信息（如游戏模式、队伍名称等）。
     * <p>当玩家数据与模板/引擎全局数据存在同名 key 时，<b>玩家数据优先</b>；
     * 当模板数据与引擎全局数据存在同名 key 时，<b>模板数据优先</b>。
     *
     * @param templateName 模板名称
     * @param key          数据键名
     * @param value        数据值
     */
    public void updateTemplate(String templateName, String key, Object value) {
        engine.setTemplateData(templateName, key, value);
    }

    /**
     * 批量更新模板全局数据——所有正在使用该模板的玩家都会自动收到变更并增量刷新。
     *
     * @param templateName 模板名称
     * @param entries      键值对
     * @see #updateTemplate(String, String, Object)
     */
    public void updateTemplateAll(String templateName, Map<String, Object> entries) {
        engine.setTemplateDataAll(templateName, entries);
    }

    // ==================== 查询 ====================

    /**
     * 获取指定玩家的计分板视图。
     *
     * @param player 目标玩家
     * @return 视图，不存在返回 {@code null}
     */
    public ScoreboardView getView(Player player) {
        return views.get(player.getUniqueId());
    }

    /**
     * 获取指定玩家的数据上下文（可直接操作数据，变更会自动触发渲染）。
     *
     * @param player 目标玩家
     * @return 数据上下文，不存在返回 {@code null}
     */
    public DataContext getDataContext(Player player) {
        ScoreboardView view = views.get(player.getUniqueId());
        return view != null ? view.getDataContext() : null;
    }

    /** 当前正在显示计分板的玩家数量 */
    public int getActiveCount() {
        return views.size();
    }

    /**
     * 获取全局数据上下文（可直接操作全局数据，变更会自动传播到所有玩家视图）。
     *
     * @return 全局数据上下文
     */
    public DataContext getGlobalDataContext() {
        return engine.getGlobalData();
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
        return engine.getTemplateData(templateName);
    }

    // ==================== 生命周期 ====================

    /**
     * 玩家退出时清理资源——应在 {@code PlayerQuitEvent} 中调用。
     *
     * @param player 退出的玩家
     */
    public void onPlayerQuit(Player player) {
        hide(player);
    }

    /**
     * 清理视图的数据上下文——如果是 {@link HierarchicalDataContext}，
     * 移除其对全局数据的监听引用，防止内存泄漏。
     * <p>HierarchicalDataContext 在构造时向 parent（全局数据）注册了 onChange 监听器，
     * ScoreboardView.hide() 只移除了自己的监听器，不会移除 parent 上的监听器。
     * 因此必须在 hide 后调用 {@link HierarchicalDataContext#dispose()} 显式清理。
     *
     * @param view 要清理的视图
     */
    private void disposeDataContext(ScoreboardView view) {
        DataContext data = view.getDataContext();
        if (data instanceof HierarchicalDataContext) {
            ((HierarchicalDataContext) data).dispose();
        }
    }

    // ==================== 日志 ====================

    /**
     * 安全输出 INFO 日志。
     * <p>当 Nukkit Server 尚未初始化时（如单元测试环境）静默忽略，避免抛出异常。
     *
     * @param message 日志消息
     */
    private void logInfo(String message) {
        try {
            JFrameLog.info("ScoreboardManager", message);
        } catch (IllegalStateException ignored) {
            // Server 尚未初始化（如单元测试环境），静默忽略
        }
    }

    /**
     * 安全输出 WARNING 日志。
     * <p>当 Nukkit Server 尚未初始化时（如单元测试环境）静默忽略，避免抛出异常。
     *
     * @param message 日志消息
     */
    private void logWarning(String message) {
        try {
            JFrameLog.warning("ScoreboardManager", message);
        } catch (IllegalStateException ignored) {
            // Server 尚未初始化（如单元测试环境），静默忽略
        }
    }
}
