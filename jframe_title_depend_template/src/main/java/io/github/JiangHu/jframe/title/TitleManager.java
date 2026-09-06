package io.github.JiangHu.jframe.title;

import cn.nukkit.Player;
import cn.nukkit.Server;
import io.github.JiangHu.jframe.content_template.HierarchicalDataContext;
import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.content_template.TemplateEngine;
import io.github.JiangHu.jframe.title.nukkit.DefaultTitleSender;
import io.github.JiangHu.jframe.title.nukkit.TitleScheduler;
import io.github.JiangHu.jframe.title.nukkit.TitleSender;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 标题管理器——模板注册表 + TITLE / ACTIONBAR 双槽位视图管理 + KeepAlive
 *
 * <h3>双槽位模型</h3>
 * <p>每位玩家维护两个独立槽位：</p>
 * <ul>
 *   <li><b>TITLE 槽</b>：title / subtitle 通道（副标题区间非空的模板占用）；</li>
 *   <li><b>ACTIONBAR 槽</b>：动作栏通道（actionbarLine &ge; 0 的模板占用）。</li>
 * </ul>
 * <p>show 时按目标模板的占用情况先停用（deactivate）同槽位的当前视图再激活目标视图，
 * 双槽互不干扰——常驻动作栏信息（仅占 ACTIONBAR 槽）与瞬时公告（仅占 TITLE 槽）可共存；
 * 同一模板可同时占用双槽。</p>
 *
 * <h3>视图缓存与 KeepAlive</h3>
 * <p>每玩家按模板名缓存 {@link TitleView}（{@code cachedViews}）；
 * 切换槽位视图时旧视图 deactivate（保留数据与渲染状态），
 * 再次 show 同名模板时 reactivate 复用缓存。</p>
 *
 * <h3>瞬时模式到期回收</h3>
 * <p>{@link TitleView} 到期 dispose 后经回调 {@link #onTransientExpired(UUID, String)}
 * 移除缓存与槽位标记。</p>
 */
public class TitleManager {

    private static final String TAG = "TitleManager";

    private final TemplateEngine engine;

    /** 玩家 → 标题发送器工厂（测试可注入录制桩） */
    private final Function<Player, TitleSender> senderFactory;

    /** 调度器（测试可注入手动时钟桩） */
    private final TitleScheduler scheduler;

    /** 模板注册表（模板名 → 标题模板） */
    private final ConcurrentHashMap<String, TitleTemplate> templates = new ConcurrentHashMap<>();

    /** 视图缓存（玩家 UUID → 模板名 → 视图） */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, TitleView>> cachedViews = new ConcurrentHashMap<>();

    /** TITLE 槽位占用（玩家 UUID → 模板名） */
    private final ConcurrentHashMap<UUID, String> activeTitleView = new ConcurrentHashMap<>();

    /** ACTIONBAR 槽位占用（玩家 UUID → 模板名） */
    private final ConcurrentHashMap<UUID, String> activeActionBarView = new ConcurrentHashMap<>();

    /** 瞬时模式到期回调（视图 dispose 后移除缓存与槽位标记） */
    private final java.util.function.Consumer<TitleView> transientExpireCallback =
            view -> onTransientExpired(view.getPlayerId(), view.getTemplate().getName());

    /**
     * 以默认 Nukkit 适配创建管理器
     *
     * @param engine 模板引擎
     */
    public TitleManager(TemplateEngine engine) {
        this(engine, DefaultTitleSender::new, TitleScheduler.defaultScheduler());
    }

    /**
     * 全参构造（测试注入桩用）
     *
     * @param engine        模板引擎
     * @param senderFactory 玩家 → 标题发送器工厂
     * @param scheduler     调度器
     */
    public TitleManager(TemplateEngine engine,
                        Function<Player, TitleSender> senderFactory,
                        TitleScheduler scheduler) {
        this.engine = engine;
        this.senderFactory = senderFactory;
        this.scheduler = scheduler;
    }

    /** 模板引擎 */
    public TemplateEngine getEngine() {
        return engine;
    }

    // ===== 模板注册 =====

    /**
     * 编译并注册模板（默认三级融合）
     *
     * @param name       模板名
     * @param xmlSource XML 模板源码
     * @return 标题模板
     */
    public TitleTemplate loadTemplate(String name, String xmlSource) {
        Template compiled = engine.compile(xmlSource);
        TitleTemplate template = TitleTemplate.of(name, compiled);
        templates.put(name, template);
        return template;
    }

    /**
     * 从引擎加载并注册模板（经引擎 loader 链按名取源码）
     *
     * @param name 模板名
     * @return 标题模板；引擎无此模板时返回 null
     */
    public TitleTemplate loadTemplate(String name) {
        Template compiled = engine.getTemplate(name);
        if (compiled == null) {
            TitleLog.warning(TAG, "模板[" + name + "]在引擎中不存在，加载失败");
            return null;
        }
        TitleTemplate template = TitleTemplate.of(name, compiled);
        templates.put(name, template);
        return template;
    }

    /**
     * 注册已打包的标题模板（Builder 定制产物）
     *
     * @param template 标题模板
     * @return 同一模板（链式）
     */
    public TitleTemplate loadTemplate(TitleTemplate template) {
        templates.put(template.getName(), template);
        return template;
    }

    /** 按名取已注册模板（未注册返回 null） */
    public TitleTemplate getTemplate(String name) {
        return templates.get(name);
    }

    /**
     * 移除模板：销毁全部玩家的对应视图并清理槽位标记、模板级数据
     *
     * @param name 模板名
     */
    public void removeTemplate(String name) {
        templates.remove(name);
        engine.removeTemplateData(name);
        for (ConcurrentHashMap<String, TitleView> cache : cachedViews.values()) {
            TitleView view = cache.remove(name);
            if (view != null) {
                view.dispose(null);
            }
        }
        activeTitleView.values().removeIf(name::equals);
        activeActionBarView.values().removeIf(name::equals);
    }

    // ===== 显示 / 隐藏（双槽位管理） =====

    /**
     * 向玩家显示模板（双槽位切换）
     *
     * @param player       目标玩家
     * @param templateName 模板名
     * @return 激活的视图；模板未注册时返回 null
     */
    public TitleView show(Player player, String templateName) {
        return show(player.getUniqueId(), templateName, senderFactory.apply(player));
    }

    /**
     * 给满足条件的在线玩家显示模板
     *
     * @param predicate    玩家筛选条件
     * @param templateName 模板名
     * @return 成功显示的玩家数量
     */
    public int showIf(Predicate<Player> predicate, String templateName) {
        int count = 0;
        for (Player player : Server.getInstance().getOnlinePlayers().values()) {
            if (predicate.test(player) && show(player, templateName) != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * 向全部在线玩家显示模板
     *
     * @param templateName 模板名
     * @return 成功显示的玩家数量
     */
    public int showAll(String templateName) {
        int count = 0;
        for (Player player : Server.getInstance().getOnlinePlayers().values()) {
            if (show(player, templateName) != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * 显示核心逻辑（包级，供测试以 UUID + 桩发送器驱动）
     *
     * @param playerId     玩家 UUID
     * @param templateName 模板名
     * @param sender       已绑定的标题发送器
     * @return 激活的视图；模板未注册时告警并返回 null
     */
    TitleView show(UUID playerId, String templateName, TitleSender sender) {
        TitleTemplate template = templates.get(templateName);
        if (template == null) {
            TitleLog.warning(TAG, "模板[" + templateName + "]未注册，无法显示");
            return null;
        }

        ConcurrentHashMap<String, TitleView> playerCache =
                cachedViews.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());

        // 1. 停用占用目标槽位的当前视图（KeepAlive：deactivate 而非 dispose）
        evictSlot(playerId, playerCache, template.occupiesTitleSlot(), activeTitleView);
        evictSlot(playerId, playerCache, template.occupiesActionBarSlot(), activeActionBarView);

        // 2. 获取或创建视图
        TitleView view = playerCache.get(templateName);
        if (view != null && !view.isDisposed()) {
            if (view.isShown()) {
                view.deactivate(null); // 重复 show 同一模板：强制重建（清屏重发）
            }
            view.reactivate(null);
        } else {
            HierarchicalDataContext data = HierarchicalDataContext.of(engine.getPlayerData(playerId));
            view = new TitleView(playerId, template, data, engine, sender, scheduler,
                    transientExpireCallback);
            view.show(null);
            playerCache.put(templateName, view);
        }

        // 3. 更新槽位占用标记
        if (template.occupiesTitleSlot()) {
            activeTitleView.put(playerId, templateName);
        }
        if (template.occupiesActionBarSlot()) {
            activeActionBarView.put(playerId, templateName);
        }
        return view;
    }

    /** 停用槽位当前视图（不清理标记，由调用方决定） */
    private void evictSlot(UUID playerId,
                           ConcurrentHashMap<String, TitleView> playerCache,
                           boolean occupy,
                           ConcurrentHashMap<UUID, String> slotMap) {
        if (!occupy) {
            return;
        }
        String current = slotMap.get(playerId);
        if (current == null) {
            return;
        }
        TitleView oldView = playerCache.get(current);
        if (oldView != null && oldView.isShown()) {
            oldView.deactivate(null);
        }
    }

    /**
     * 隐藏玩家当前激活的双槽视图（KeepAlive：deactivate，可再 show 恢复）
     *
     * @param player 目标玩家
     */
    public void hide(Player player) {
        hide(player.getUniqueId());
    }

    /** 隐藏全部在线玩家的双槽视图 */
    public void hideAll() {
        for (Player player : Server.getInstance().getOnlinePlayers().values()) {
            hide(player);
        }
    }

    /** 隐藏核心逻辑（包级，供测试以 UUID 驱动） */
    void hide(UUID playerId) {
        ConcurrentHashMap<String, TitleView> playerCache = cachedViews.get(playerId);
        if (playerCache != null) {
            deactivateIfShown(playerCache, activeTitleView.get(playerId));
            deactivateIfShown(playerCache, activeActionBarView.get(playerId));
        }
        activeTitleView.remove(playerId);
        activeActionBarView.remove(playerId);
    }

    private void deactivateIfShown(ConcurrentHashMap<String, TitleView> playerCache, String templateName) {
        if (templateName == null) {
            return;
        }
        TitleView view = playerCache.get(templateName);
        if (view != null && view.isShown()) {
            view.deactivate(null);
        }
    }

    // ===== 数据更新 =====

    /**
     * 更新玩家全局层数据（该玩家全部视图经父层传播自动刷新）
     *
     * @param player 玩家
     * @param key    键名
     * @param value  值
     * @return this（链式）
     */
    public TitleManager updatePlayer(Player player, String key, Object value) {
        engine.setPlayerData(player.getUniqueId(), key, value);
        return this;
    }

    /** 批量更新玩家全局层数据（只触发一次变更通知） */
    public TitleManager updatePlayerAll(Player player, Map<String, Object> data) {
        engine.setPlayerDataAll(player.getUniqueId(), data);
        return this;
    }

    /**
     * 更新玩家当前激活视图的局部数据（遍历双槽激活视图，同一视图去重）
     *
     * @param player 玩家
     * @param key    键名
     * @param value  值
     * @return this（链式）
     */
    public TitleManager update(Player player, String key, Object value) {
        update(player.getUniqueId(), key, value);
        return this;
    }

    /** 批量更新玩家当前激活视图的局部数据 */
    public TitleManager update(Player player, Map<String, Object> data) {
        TitleView titleView = getView(player);
        if (titleView != null) {
            titleView.setAll(data);
        }
        TitleView actionBarView = getActionBarView(player);
        if (actionBarView != null && actionBarView != titleView) {
            actionBarView.setAll(data);
        }
        return this;
    }

    /** 局部更新核心逻辑（包级，供测试以 UUID 驱动） */
    void update(UUID playerId, String key, Object value) {
        TitleView titleView = findActiveView(playerId, activeTitleView);
        if (titleView != null) {
            titleView.set(key, value);
        }
        TitleView actionBarView = findActiveView(playerId, activeActionBarView);
        if (actionBarView != null && actionBarView != titleView) {
            actionBarView.set(key, value);
        }
    }

    /**
     * 更新全体玩家的全局层数据
     *
     * @param key   键名
     * @param value 值
     * @return this（链式）
     */
    public TitleManager updateAll(String key, Object value) {
        for (UUID playerId : cachedViews.keySet()) {
            engine.setPlayerData(playerId, key, value);
        }
        return this;
    }

    /** 批量更新全体玩家的全局层数据 */
    public TitleManager updateAll(Map<String, Object> data) {
        for (UUID playerId : cachedViews.keySet()) {
            engine.setPlayerDataAll(playerId, data);
        }
        return this;
    }

    /**
     * 更新引擎全局层数据（模板无关，全部视图经父层传播自动刷新）
     *
     * @param key   键名
     * @param value 值
     * @return this（链式）
     */
    public TitleManager updateGlobal(String key, Object value) {
        engine.setGlobal(key, value);
        return this;
    }

    /** 批量更新引擎全局层数据 */
    public TitleManager updateGlobalAll(Map<String, Object> data) {
        engine.setGlobalAll(data);
        return this;
    }

    /**
     * 更新模板全局层数据（该模板全部视图经父层传播自动刷新）
     *
     * @param templateName 模板名
     * @param key          键名
     * @param value        值
     * @return this（链式）
     */
    public TitleManager updateTemplate(String templateName, String key, Object value) {
        engine.setTemplateData(templateName, key, value);
        return this;
    }

    /** 批量更新模板全局层数据 */
    public TitleManager updateTemplateAll(String templateName, Map<String, Object> data) {
        engine.setTemplateDataAll(templateName, data);
        return this;
    }

    // ===== 视图查询 =====

    /** 玩家当前 TITLE 槽激活视图（无则 null） */
    public TitleView getView(Player player) {
        return findActiveView(player.getUniqueId(), activeTitleView);
    }

    /** 玩家当前 ACTIONBAR 槽激活视图（无则 null） */
    public TitleView getActionBarView(Player player) {
        return findActiveView(player.getUniqueId(), activeActionBarView);
    }

    /**
     * 玩家全局层数据上下文（Session 作用域，该玩家全部视图共享，切换不丢）
     *
     * @param player 玩家
     * @return 玩家全局上下文
     */
    public io.github.JiangHu.jframe.core.data.reactive.DataContext getPlayerDataContext(Player player) {
        return engine.getPlayerData(player.getUniqueId());
    }

    /**
     * 玩家当前 TITLE 槽激活视图的局部数据上下文（Request 作用域）
     *
     * @param player 玩家
     * @return 局部上下文；无激活视图时返回 null
     */
    public io.github.JiangHu.jframe.core.data.reactive.DataContext getDataContext(Player player) {
        TitleView view = getView(player);
        return view != null ? view.getDataContext() : null;
    }

    /** 引擎全局层数据上下文（全部玩家共享） */
    public io.github.JiangHu.jframe.core.data.reactive.DataContext getGlobalDataContext() {
        return engine.getGlobalData();
    }

    /** 模板全局层数据上下文（同模板玩家共享，不同模板隔离） */
    public io.github.JiangHu.jframe.core.data.reactive.DataContext getTemplateDataContext(String templateName) {
        return engine.getTemplateData(templateName);
    }

    private TitleView findActiveView(UUID playerId, ConcurrentHashMap<UUID, String> slotMap) {
        String templateName = slotMap.get(playerId);
        if (templateName == null) {
            return null;
        }
        ConcurrentHashMap<String, TitleView> playerCache = cachedViews.get(playerId);
        if (playerCache == null) {
            return null;
        }
        TitleView view = playerCache.get(templateName);
        return (view != null && view.isShown()) ? view : null;
    }

    // ===== 生命周期清理 =====

    /**
     * 玩家退出清理：销毁该玩家全部视图、清理槽位标记与玩家全局数据
     *
     * @param player 退出玩家
     */
    public void onPlayerQuit(Player player) {
        onPlayerQuit(player.getUniqueId());
    }

    /** 退出清理核心逻辑（包级，供测试以 UUID 驱动） */
    void onPlayerQuit(UUID playerId) {
        ConcurrentHashMap<String, TitleView> playerCache = cachedViews.remove(playerId);
        if (playerCache != null) {
            for (TitleView view : playerCache.values()) {
                view.dispose(null);
            }
        }
        activeTitleView.remove(playerId);
        activeActionBarView.remove(playerId);
        engine.removePlayerData(playerId);
    }

    /**
     * 瞬时模式视图到期回调（包级）：移除缓存与槽位标记
     */
    void onTransientExpired(UUID playerId, String templateName) {
        ConcurrentHashMap<String, TitleView> playerCache = cachedViews.get(playerId);
        if (playerCache != null) {
            playerCache.remove(templateName);
        }
        if (templateName.equals(activeTitleView.get(playerId))) {
            activeTitleView.remove(playerId);
        }
        if (templateName.equals(activeActionBarView.get(playerId))) {
            activeActionBarView.remove(playerId);
        }
    }

    /**
     * 销毁全部视图并清空缓存与槽位标记（模块停用入口；不动引擎玩家数据）
     */
    public void disposeAll() {
        for (ConcurrentHashMap<String, TitleView> playerCache : cachedViews.values()) {
            for (TitleView view : playerCache.values()) {
                view.dispose(null);
            }
        }
        cachedViews.clear();
        activeTitleView.clear();
        activeActionBarView.clear();
    }
}
