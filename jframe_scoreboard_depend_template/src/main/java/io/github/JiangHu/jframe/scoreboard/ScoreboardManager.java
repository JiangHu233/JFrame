package io.github.JiangHu.jframe.scoreboard;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.scoreboard.manager.IScoreboardManager;
import io.github.JiangHu.jframe.content_template.TemplateEngine;
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
 * 但 Nukkit 计分板 API 的调用应在主线程执行。
 *
 * @see ScoreboardTemplate
 * @see ScoreboardView
 */
public class ScoreboardManager {

    private final TemplateEngine engine;

    /** Nukkit 计分板管理器（延迟初始化：首次 show 时从 Server 获取） */
    private volatile IScoreboardManager nukkitManager;

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

    /**
     * 延迟获取 Nukkit 计分板管理器（首次调用时从 {@code Server.getInstance()} 获取）。
     * <p>采用延迟初始化避免 Spring 容器启动阶段 Server 尚未就绪的问题。
     *
     * @return Nukkit 计分板管理器
     */
    private IScoreboardManager getNukkitManager() {
        if (nukkitManager == null) {
            synchronized (this) {
                if (nukkitManager == null) {
                    nukkitManager = Server.getInstance().getScoreboardManager();
                }
            }
        }
        return nukkitManager;
    }

    // ==================== 模板管理 ====================

    /**
     * 注册计分板模板。
     *
     * @param template 模板配置
     */
    public void loadTemplate(ScoreboardTemplate template) {
        templates.put(template.getName(), template);
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
        DataContext data = DataContext.of();
        ScoreboardView view = new ScoreboardView(uuid, sbTemplate, data, engine, getNukkitManager());
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

    // ==================== 生命周期 ====================

    /**
     * 玩家退出时清理资源——应在 {@code PlayerQuitEvent} 中调用。
     *
     * @param player 退出的玩家
     */
    public void onPlayerQuit(Player player) {
        hide(player);
    }
}
