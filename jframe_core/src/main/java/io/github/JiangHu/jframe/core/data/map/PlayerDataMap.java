package io.github.JiangHu.jframe.core.data.map;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.plugin.Plugin;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 以玩家为来源、仅玩家在线时才保留的数据映射基类，继承 {@link AbstractFunctionalMap}。
 *
 * <p>继承本类即可获得一个线程安全的 {@code K -> V} 映射（后端 {@link java.util.concurrent.ConcurrentHashMap}），
 * 并可在玩家退出游戏时<b>自动清理</b>其对应条目，无需业务代码手动监听 {@link PlayerQuitEvent}——
 * 从而避免常见的「{@link Player} 对象与关联数据内存泄漏」问题。
 *
 * <h3>类型参数</h3>
 * <ul>
 *   <li>{@code <K>} —— 映射键类型，默认为 {@link Player}（见 {@link #key}）。</li>
 *   <li>{@code <V>} —— 每位玩家关联的数据类型。</li>
 * </ul>
 *
 * <h3>可重写的键提取</h3>
 * 默认以玩家对象本身作为键。若想用玩家名、UUID 等作为键，重写 {@link #key}：
 * <pre>{@code
 * public class ByName extends PlayerDataMap<String, Foo> {
 *     @Override protected String key(Player p) { return p.getName(); }
 * }
 * }</pre>
 * <b>注意：</b>当 {@code K ≠ Player} 时必须重写 {@link #key}，否则会在运行时抛出
 * {@link ClassCastException}。
 *
 * <h3>调用方法设置是否监听</h3>
 * 默认监听玩家退出（自动清理）、不监听玩家加入。通过调用实例方法明确声明：
 * <pre>{@code
 * // 手动 new：链式调用
 * var map = new MyMap().setListenJoin(true);          // 监听加入（退出清理保持默认）
 * map.bindPlugin(plugin);
 *
 * // 手动 new：分别设置
 * var map = new MyMap();
 * map.setListenQuit(false);                            // 不监听退出（纯映射）
 * map.setListenJoin(true);                             // 监听加入
 * map.bindPlugin(plugin);
 *
 * // 作为 Spring Bean：在子类构造器中设置
 * public class MyMap extends PlayerDataMap<Player, Foo> {
 *     public MyMap() { setListenJoin(true); }          // 构造时声明
 * }
 * }</pre>
 * <b>必须在 {@link #bindPlugin} 之前调用</b>，因为监听器在 {@code bindPlugin} 时注册。
 * 关闭的监听器完全不注册，零开销。
 *
 * <h3>手动激活</h3>
 * 使用前需调用一次 {@code bindPlugin(plugin)}（注册事件监听器、保存插件引用）。
 *
 * <h3>回调钩子</h3>
 * <ul>
 *   <li>{@link #onPlayerQuit} —— 玩家退出时调用（需 {@code listenQuit=true}），默认 {@link #remove}。</li>
 *   <li>{@link #onPlayerJoin} —— 玩家加入时调用（需 {@code listenJoin=true}），默认空。</li>
 *   <li>{@link #onRemove} —— 条目被移除时调用，默认空（可重写做持久化等）。</li>
 * </ul>
 *
 * <h3>继承自基类的能力</h3>
 * 本类同时拥有 {@link AbstractFunctionalMap} 的 4 个功能槽位：
 * {@link #setKeyExtractor}、{@link #setLoader}、{@link #setExpiryChecker}、{@link #setOnEvict}。
 * 此外本类新增 {@link #setCreator(Function) creator} 槽位（{@code Function<Player,V>}）驱动 {@link #create}：
 * {@link #getOrCreate(Player)} 用 {@link #create} 而非基类 {@code loader} 槽位。
 *
 * <h3>使用示例</h3>
 * <p><b>方式一：子类重写</b>
 * <pre>{@code
 * public class CombatStats extends PlayerDataMap<Player, CombatData> {
 *     public CombatStats() { setListenJoin(true); }    // 退出清理 + 进入预加载
 *     @Override protected CombatData create(Player player) { return new CombatData(); }
 *     @Override protected void onRemove(Player key, CombatData data) {
 *         database.save(key.getName(), data);
 *     }
 *     @Override protected void onPlayerJoin(Player player) {
 *         getOrCreate(player);   // 玩家进入时预加载
 *     }
 * }
 * }</pre>
 *
 * <p><b>方式二：外部注入</b>（无需子类化，未注入时 {@link #create} 返回 {@code null}）
 * <pre>{@code
 * var stats = new PlayerDataMap<Player, CombatData>()
 *         .setCreator(player -> new CombatData())
 *         .setListenJoin(true);
 * stats.bindPlugin(plugin);
 * }</pre>
 *
 * @param <K> 映射键类型（默认 {@link Player}）
 * @param <V> 每位玩家关联的数据类型
 */
@Accessors(chain = true)
public class PlayerDataMap<K, V> extends AbstractFunctionalMap<K, V> implements Listener {

    /** 是否监听玩家退出（默认 true：自动清理）。必须在 {@link #bindPlugin} 之前通过 {@link #setListenQuit(boolean)} 设置。 */
    @Getter @Setter
    private boolean listenQuit = true;

    /** 是否监听玩家加入（默认 false：不监听）。必须在 {@link #bindPlugin} 之前通过 {@link #setListenJoin(boolean)} 设置。 */
    @Getter @Setter
    private boolean listenJoin = false;

    /** 已绑定的插件实例（注册事件监听器后可用）。 */
    @Getter(AccessLevel.PROTECTED)
    private Plugin plugin;

    public PlayerDataMap<K, V> bindPlugin(Plugin plugin) {
        this.plugin = plugin;
        var pm = plugin.getServer().getPluginManager();
        if (listenQuit) {
            pm.registerEvents(new QuitListener(), plugin);
        }
        if (listenJoin) {
            pm.registerEvents(new JoinListener(), plugin);
        }
        return this;
    }

    /**
     * 从玩家提取映射键（默认返回玩家本身）。
     *
     * <p>子类可重写为 {@code player.getName()}、{@code player.getUniqueId()} 等。
     * 当 {@code K ≠ Player} 时<b>必须</b>重写本方法。
     *
     * @param player 玩家
     * @return 用作映射键的值
     */
    @SuppressWarnings("unchecked")
    protected K key(Player player) {
        return (K) player;
    }

    /**
     * 玩家退出时的回调（默认自动清理该玩家条目）。
     *
     * <p>仅当 {@link #isListenQuit()} 为 {@code true} 时会被调用。子类可重写以追加逻辑，
     * 但若仍需清理请调用 {@code super.onPlayerQuit(player)} 或 {@link #remove}。
     *
     * @param player 退出的玩家
     */
    protected void onPlayerQuit(Player player) {
        remove(player);
    }

    /**
     * 玩家加入时的回调（默认空实现）。
     *
     * <p>仅当 {@link #isListenJoin()} 为 {@code true} 时会被调用。子类可重写以执行进入逻辑
     * （如预加载数据）。
     *
     * @param player 加入的玩家
     */
    protected void onPlayerJoin(Player player) {
    }

    /**
     * 玩家数据被移除时的回调（默认空实现）。
     *
     * <p>触发时机：玩家退出自动清理、手动调用 {@link #remove} 或 {@link #clear}。
     * 子类可重写以执行额外清理（如持久化、释放资源）。
     *
     * @param key   被移除条目的键（由 {@link #key} 提取）
     * @param value 被移除的数据
     */
    protected void onRemove(K key, V value) {
    }

    /**
     * 为玩家创建默认数据的函数（外部注入，驱动 {@link #create}）。
     * 为 {@code null} 时 {@link #create} 返回 {@code null}。
     */
    @Getter @Setter
    private Function<Player, V> creator;

    /**
     * 为玩家创建默认数据。
     *
     * <p>在 {@link #getOrCreate} 首次访问某玩家时调用。
     *
     * <p><b>默认实现</b>委托 {@link #setCreator(Function) creator} 槽位：
     * 若已通过 {@link #setCreator(Function)} 注入创建函数则调用之，否则返回 {@code null}。
     * 因此<b>外部注入</b>（{@code setCreator(fn)}）与<b>子类重写</b>两种方式任选其一即可；
     * 二者皆未提供时返回 {@code null}。重写本方法后 creator 槽位不再生效。
     *
     * @param player 玩家
     * @return 新建的默认数据，或 {@code null}
     */
    protected V create(Player player) {
        Function<Player, V> c = this.creator;
        return c != null ? c.apply(player) : null;
    }

    /** 退出监听器内部类（仅 {@code listenQuit=true} 时实例化注册）。 */
    private final class QuitListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onPlayerQuit(PlayerQuitEvent event) {
            PlayerDataMap.this.onPlayerQuit(event.getPlayer());
        }
    }

    /** 加入监听器内部类（仅 {@code listenJoin=true} 时实例化注册）。 */
    private final class JoinListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onPlayerJoin(PlayerJoinEvent event) {
            PlayerDataMap.this.onPlayerJoin(event.getPlayer());
        }
    }

    // -------------------- 读取 --------------------

    /**
     * 获取玩家的数据（不存在时返回 {@code null}）。
     *
     * @param player 玩家
     * @return 数据，或 {@code null}
     */
    public V get(Player player) {
        return data.get(key(player));
    }

    /**
     * 获取玩家的数据，不存在时返回默认值。
     *
     * @param player 玩家
     * @param defVal 默认值
     * @return 数据或默认值
     */
    public V get(Player player, V defVal) {
        return data.getOrDefault(key(player), defVal);
    }

    /**
     * 获取玩家的数据，不存在时用 {@link #create} 创建并放入映射。
     *
     * @param player 玩家
     * @return 数据（绝不返回 {@code null}，除非 {@link #create} 返回 null）
     */
    public V getOrCreate(Player player) {
        K k = key(player);
        V existing = data.get(k);
        if (existing != null) {
            return existing;
        }
        return data.computeIfAbsent(k, kk -> create(player));
    }

    /**
     * 是否包含某玩家的数据。
     *
     * @param player 玩家
     * @return {@code true} 表示存在
     */
    public boolean contains(Player player) {
        return data.containsKey(key(player));
    }

    // -------------------- 写入 --------------------

    /**
     * 设置玩家的数据（覆盖已有值）。
     *
     * @param player 玩家
     * @param value  数据
     * @return 被覆盖的旧值，或 {@code null}
     */
    public V put(Player player, V value) {
        return data.put(key(player), value);
    }

    /**
     * 移除玩家的数据，并触发 {@link #onRemove} 回调。
     *
     * @param player 玩家
     * @return 被移除的数据，或 {@code null}
     */
    public V remove(Player player) {
        K k = key(player);
        V removed = data.remove(k);
        if (removed != null) {
            onRemove(k, removed);
        }
        return removed;
    }

    /**
     * 清空所有数据，逐条触发 {@link #onRemove}。
     */
    @Override
    public void clear() {
        data.forEach(this::onRemove);
        data.clear();
    }

    // -------------------- 视图 --------------------

    /** 是否为空。 */
    public boolean isEmpty() {
        return data.isEmpty();
    }

    /** 所有键的集合视图（弱一致，迭代期间结构变更不会抛异常）。 */
    public Set<K> keys() {
        return data.keySet();
    }

    /**
     * 遍历所有键及其数据。
     *
     * @param action 对每个条目执行的动作
     */
    public void forEach(BiConsumer<K, V> action) {
        data.forEach(action);
    }
}
