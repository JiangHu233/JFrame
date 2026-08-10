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
 * <h3>四层作用域数据模型</h3>
 * <pre>{@code
 * 引擎全局（engine.getGlobalData）         ← Application，所有玩家共享
 *     ↑ parent
 * 玩家全局（engine.getPlayerData uuid）     ← Session，该玩家所有模板/下游模块共享
 *     ↑ parent
 * 单计分板局部（view 持有）                  ← Request，单个 view 私有
 * }</pre>
 * <p>模板全局（engine.getTemplateData）作为可选独立层保留 API，不强制进入主链。
 *
 * <h3>KeepAlive 缓存</h3>
 * <p>切换计分板时，旧 view 调用 {@link ScoreboardView#deactivate}（保留数据和渲染状态），
 * 而非销毁。切回时调用 {@link ScoreboardView#reactivate} 恢复显示。
 * 玩家退出时才调用 {@link ScoreboardView#dispose} 彻底释放（唯一 dispose 时机）。
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li><b>模板注册</b>：{@code loadTemplate} / {@code getTemplate} / {@code removeTemplate}</li>
 *   <li><b>显示控制</b>：{@code show}（指定玩家）/ {@code showIf}（条件筛选）/ {@code showAll}（全体）</li>
 *   <li><b>隐藏控制</b>：{@code hide}（deactivate 保留缓存）/ {@code hideAll}</li>
 *   <li><b>数据更新</b>：{@code updatePlayer}（玩家全局）/ {@code update}（单计分板局部）/ {@code updateGlobal} / {@code updateTemplate}</li>
 *   <li><b>生命周期</b>：{@code onPlayerQuit} 玩家退出时强制清理全部缓存 + 玩家全局</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>使用 {@link ConcurrentHashMap} 存储模板、缓存视图和激活状态，线程安全。
 * 发包操作由 {@link ScoreboardView} 自动检测主线程并调度。
 *
 * @see ScoreboardTemplate
 * @see ScoreboardView
 * @see TemplateEngine
 */
public class ScoreboardManager {

    private final TemplateEngine engine;

    /** 模板注册表：模板名称 → ScoreboardTemplate */
    private final ConcurrentHashMap<String, ScoreboardTemplate> templates = new ConcurrentHashMap<>();

    /**
     * KeepAlive 缓存：玩家 UUID →（模板名 → ScoreboardView）。
     * <p>每个玩家可缓存多个模板的 view，切换时 deactivate 旧的、reactivate 新的，
     * 数据和渲染状态保留。玩家退出时整体清理。
     */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, ScoreboardView>> cachedViews = new ConcurrentHashMap<>();

    /** 每个玩家当前激活的模板名（null 表示无激活计分板） */
    private final ConcurrentHashMap<UUID, String> activeTemplate = new ConcurrentHashMap<>();

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
     * 移除已注册的模板，并清理所有玩家该模板的缓存 view。
     *
     * @param name 模板名称
     */
    public void removeTemplate(String name) {
        templates.remove(name);
        // 清理模板全局数据上下文（委托 TemplateEngine），解除对引擎全局数据的监听，防止内存泄漏
        engine.removeTemplateData(name);
        // dispose 所有玩家该模板的缓存 view
        for (Map.Entry<UUID, ConcurrentHashMap<String, ScoreboardView>> entry : cachedViews.entrySet()) {
            UUID uuid = entry.getKey();
            ScoreboardView view = entry.getValue().remove(name);
            if (view != null && !view.isDisposed()) {
                Player player = Server.getInstance().getOnlinePlayers().get(uuid);
                view.dispose(player); // player 可能为 null（已离线），dispose 内部安全处理
            }
            // 如果移除的是当前激活模板，清除激活标记
            if (name.equals(activeTemplate.get(uuid))) {
                activeTemplate.remove(uuid);
            }
        }
    }

    // ==================== 显示控制 ====================

    /**
     * 给指定玩家显示计分板。
     * <p>如果玩家已有其他计分板，先 deactivate 旧的（KeepAlive 保留），再显示新的。
     * 切回旧模板时自动 reactivate，数据和渲染状态保留。
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
     * <p>切换流程：
     * <ol>
     *   <li>获取玩家全局 DataContext（Session 作用域）</li>
     *   <li>检查目标模板是否已缓存：是 → reactivate；否 → 新建（parent 为玩家全局）</li>
     *   <li>deactivate 旧的激活 view（如果有且不同）</li>
     *   <li>更新激活模板标记</li>
     * </ol>
     *
     * @param player     目标玩家
     * @param sbTemplate 模板配置
     * @return 对应的 {@link ScoreboardView}
     */
    public ScoreboardView show(Player player, ScoreboardTemplate sbTemplate) {
        UUID uuid = player.getUniqueId();
        String templateName = sbTemplate.getName();

        // 如果切换的是当前已激活的模板，直接返回（无需操作）
        String currentActive = activeTemplate.get(uuid);
        if (templateName.equals(currentActive)) {
            ConcurrentHashMap<String, ScoreboardView> playerCache = cachedViews.get(uuid);
            return playerCache != null ? playerCache.get(templateName) : null;
        }

        // 获取或创建该玩家的缓存映射
        ConcurrentHashMap<String, ScoreboardView> playerCache =
                cachedViews.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());

        // 获取或创建目标模板的 view
        ScoreboardView targetView = playerCache.get(templateName);
        if (targetView != null && !targetView.isDisposed()) {
            // 已缓存 → reactivate（KeepAlive 恢复，数据和渲染状态保留）
            targetView.reactivate(player);
        } else {
            // 未缓存 → 新建，dataContext parent 为玩家全局（Session），构成单计分板局部层（Request）
            DataContext playerGlobal = engine.getPlayerData(uuid);
            HierarchicalDataContext data = HierarchicalDataContext.of(playerGlobal);
            targetView = new ScoreboardView(uuid, sbTemplate, data, engine);
            targetView.show(player);
            playerCache.put(templateName, targetView);
        }

        // deactivate 旧的激活 view（KeepAlive 保留，不销毁）
        if (currentActive != null) {
            ScoreboardView oldView = playerCache.get(currentActive);
            if (oldView != null && oldView.isShown()) {
                oldView.deactivate(player);
            }
        }

        activeTemplate.put(uuid, templateName);
        return targetView;
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
     * 隐藏指定玩家的计分板（deactivate，保留缓存）。
     * <p>调用 {@link ScoreboardView#deactivate}，数据和渲染状态保留。
     * 后续可通过 {@link #show} 恢复（自动 reactivate）。
     *
     * @param player 目标玩家
     */
    public void hide(Player player) {
        UUID uuid = player.getUniqueId();
        String templateName = activeTemplate.remove(uuid);
        if (templateName != null) {
            ConcurrentHashMap<String, ScoreboardView> playerCache = cachedViews.get(uuid);
            if (playerCache != null) {
                ScoreboardView view = playerCache.get(templateName);
                if (view != null && view.isShown()) {
                    view.deactivate(player);
                }
            }
        }
    }

    /**
     * 隐藏所有玩家的计分板（deactivate，保留缓存）。
     */
    public void hideAll() {
        for (UUID uuid : activeTemplate.keySet()) {
            Player player = Server.getInstance().getOnlinePlayers().get(uuid);
            if (player != null) {
                hide(player);
            }
        }
    }

    // ==================== 数据更新 ====================

    /**
     * 更新指定玩家的<b>玩家全局数据</b>（Session 作用域），自动触发重新渲染。
     * <p>玩家全局数据对该玩家所有模板、所有下游模块共享。适合存放玩家核心数据
     * （coins、level、player.name 等），切换计分板时不会丢失。
     *
     * @param player 目标玩家
     * @param key    数据键名
     * @param value  数据值
     */
    public void updatePlayer(Player player, String key, Object value) {
        engine.setPlayerData(player.getUniqueId(), key, value);
    }

    /**
     * 批量更新指定玩家的玩家全局数据（只触发一次变更通知）。
     *
     * @param player  目标玩家
     * @param entries 键值对
     */
    public void updatePlayerAll(Player player, Map<String, Object> entries) {
        engine.setPlayerDataAll(player.getUniqueId(), entries);
    }

    /**
     * 更新指定玩家<b>当前激活计分板</b>的局部数据（Request 作用域），自动触发增量渲染。
     * <p>局部数据仅对该 view 可见，切换计分板后不保留（除非 KeepAlive 缓存命中）。
     *
     * @param player 目标玩家
     * @param key    数据键名
     * @param value  数据值
     */
    public void update(Player player, String key, Object value) {
        ScoreboardView view = getActiveView(player.getUniqueId());
        if (view != null) {
            view.set(key, value);
        }
    }

    /**
     * 批量更新指定玩家当前激活计分板的局部数据（只触发一次增量渲染）。
     *
     * @param player  目标玩家
     * @param entries 键值对
     */
    public void update(Player player, Map<String, Object> entries) {
        ScoreboardView view = getActiveView(player.getUniqueId());
        if (view != null) {
            view.setAll(entries);
        }
    }

    /**
     * 更新所有正在显示计分板的玩家的同一<b>局部</b>数据项（各自当前激活 view）。
     *
     * @param key   数据键名
     * @param value 数据值
     */
    public void updateAll(String key, Object value) {
        for (UUID uuid : activeTemplate.keySet()) {
            ScoreboardView view = getActiveView(uuid);
            if (view != null) {
                view.set(key, value);
            }
        }
    }

    /**
     * 批量更新所有正在显示计分板的玩家的同一批局部数据（每个玩家只触发一次增量渲染）。
     *
     * @param entries 键值对
     */
    public void updateAll(Map<String, Object> entries) {
        for (UUID uuid : activeTemplate.keySet()) {
            ScoreboardView view = getActiveView(uuid);
            if (view != null) {
                view.setAll(entries);
            }
        }
    }

    /**
     * 更新<b>引擎全局数据</b>——所有正在显示计分板的玩家都会自动收到变更并增量刷新。
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
     * 批量更新引擎全局数据——所有正在显示计分板的玩家都会自动收到变更并增量刷新。
     *
     * @param entries 键值对
     * @see #updateGlobal(String, Object)
     */
    public void updateGlobalAll(Map<String, Object> entries) {
        engine.setGlobalAll(entries);
    }

    /**
     * 更新<b>模板全局数据</b>——所有正在使用该模板的玩家都会自动收到变更并增量刷新。
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
     * 获取指定玩家当前激活的计分板视图。
     *
     * @param player 目标玩家
     * @return 视图，不存在返回 {@code null}
     */
    public ScoreboardView getView(Player player) {
        return getActiveView(player.getUniqueId());
    }

    /**
     * 获取指定玩家当前激活计分板的<b>局部</b>数据上下文（Request 作用域）。
     * <p>变更会自动触发该 view 的增量渲染。
     *
     * @param player 目标玩家
     * @return 局部数据上下文，不存在返回 {@code null}
     */
    public DataContext getDataContext(Player player) {
        ScoreboardView view = getActiveView(player.getUniqueId());
        return view != null ? view.getDataContext() : null;
    }

    /**
     * 获取指定玩家的<b>玩家全局</b>数据上下文（Session 作用域）。
     * <p>该玩家所有模板、所有下游模块共享同一实例。变更会自动传播到所有以它为 parent 的 view。
     *
     * @param player 目标玩家
     * @return 玩家全局数据上下文
     */
    public DataContext getPlayerDataContext(Player player) {
        return engine.getPlayerData(player.getUniqueId());
    }

    /** 当前正在显示计分板的玩家数量 */
    public int getActiveCount() {
        return activeTemplate.size();
    }

    /**
     * 获取<b>引擎全局</b>数据上下文（Application 作用域）。
     * <p>变更会自动传播到所有玩家视图。
     *
     * @return 引擎全局数据上下文
     */
    public DataContext getGlobalDataContext() {
        return engine.getGlobalData();
    }

    /**
     * 获取指定模板的<b>模板全局</b>数据上下文。
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
     * 玩家退出时强制清理全部资源——应在 {@code PlayerQuitEvent} 中调用。
     * <p><b>唯一 dispose 时机</b>：
     * <ol>
     *   <li>dispose 该玩家全部缓存 view（摘除各 view 对玩家全局的渲染监听）；</li>
     *   <li>{@code engine.removePlayerData} dispose 玩家全局（摘除对引擎全局的监听）；</li>
     *   <li>清空该玩家缓存映射和激活标记。</li>
     * </ol>
     *
     * @param player 退出的玩家
     */
    public void onPlayerQuit(Player player) {
        UUID uuid = player.getUniqueId();
        activeTemplate.remove(uuid);
        ConcurrentHashMap<String, ScoreboardView> playerCache = cachedViews.remove(uuid);
        if (playerCache != null) {
            for (ScoreboardView view : playerCache.values()) {
                view.dispose(player);
            }
        }
        // dispose 玩家全局 Session（摘除对引擎全局的监听，防止内存泄漏）
        engine.removePlayerData(uuid);
    }

    // ==================== 内部方法 ====================

    /**
     * 获取指定玩家当前激活的 view（内部工具方法）。
     *
     * @param uuid 玩家 UUID
     * @return 激活的 view，无激活返回 {@code null}
     */
    private ScoreboardView getActiveView(UUID uuid) {
        String templateName = activeTemplate.get(uuid);
        if (templateName == null) {
            return null;
        }
        ConcurrentHashMap<String, ScoreboardView> playerCache = cachedViews.get(uuid);
        return playerCache != null ? playerCache.get(templateName) : null;
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
