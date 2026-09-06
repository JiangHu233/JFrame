package io.github.JiangHu.jframe.core.data.map;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 线程安全的 LRU（最近最少使用）缓存映射，继承 {@link AbstractFunctionalMap}。
 *
 * <p><b>后端</b>为 access-order 的 {@link LinkedHashMap}（通过基类构造器注入）：
 * 每次 {@link #get} / {@link #put} 都会把条目移到末尾（标记为最近使用），
 * 超过 {@link #maxSize} 时淘汰头部的最久未访问条目（经 {@link #onEvicted} 记录统计并触发回调）。
 *
 * <h3>容量淘汰</h3>
 * 最大容量可运行时动态调整（{@link #setMaxSize(int)}），调小会立即淘汰多余的最久未访问条目。
 *
 * <h3>自动加载</h3>
 * {@link #getOrCreate} 在缓存未命中时调用 {@link #load} 加载并放入缓存；
 * {@link #load} 返回 {@code null} 时不缓存（避免缓存穿透），下次仍重试。
 * {@link #load} 的默认实现委托基类 {@link #setLoader(Function) loader} 槽位——
 * 通过 {@code setLoader(fn)} 注入即可启用自动加载，无需子类化。
 *
 * <h3>键源与内部键（S = K）</h3>
 * 本类取 {@code S=K}：键即源，对外读写入口直接用键 {@code K}（{@link #get} / {@link #put} /
 * {@link #remove} / {@link #containsKey} / {@link #getOrCreate} 均以 {@code K} 为参数）。
 * 构造时已默认注入 {@code keyExtractor = Function.identity()}，一般无需手动设置。
 *
 * <h3>继承自基类的能力</h3>
 * 本类同时拥有 {@link AbstractFunctionalMap} 的功能槽位：
 * <ul>
 *   <li>{@link #setExpiryChecker} —— 启用过期惰性淘汰与 {@link #evictExpired()}；</li>
 *   <li>{@link #setOnEvict} —— 淘汰回调（容量超限 / 过期均会触发）；</li>
 *   <li>{@link #setLoader} —— 缓存缺失加载器，驱动 {@link #load}（进而驱动 {@link #getOrCreate}）。</li>
 * </ul>
 * （{@code keyExtractor} 已默认为 identity，一般无需再设。）
 *
 * <pre>{@code
 * public class ResourceCache extends LruCacheMap<String, Resource> {
 *     public ResourceCache() {
 *         super(100);
 *         setOnEvict((key, res) -> res.close());   // 淘汰时释放资源
 *     }
 *     @Override protected Resource load(String key) { return database.query(key); }
 * }
 * }</pre>
 *
 * <h3>线程安全</h3>
 * 所有公开方法均 {@code synchronized}（后端 {@link LinkedHashMap} 非线程安全）。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
@Accessors(chain = true)
public class LruCacheMap<K, V> extends AbstractFunctionalMap<K, K, V> {

    /** 最大容量（可运行时通过 {@link #setMaxSize(int)} 动态调整）。 */
    @Getter
    private volatile int maxSize;

    /**
     * 构造一个指定最大容量的 LRU 缓存。
     *
     * @param maxSize 最大容量（必须 > 0）
     * @throws IllegalArgumentException 若 {@code maxSize <= 0}
     */
    public LruCacheMap(int maxSize) {
        // accessOrder=true：按访问顺序排序，get/put 都会把条目移到末尾
        super(new LinkedHashMap<>(16, 0.75f, true));
        setKeyExtractor(Function.identity());   // S=K：键即源
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive: " + maxSize);
        }
        this.maxSize = maxSize;
    }

    /**
     * 缓存未命中时加载对应键的值。
     *
     * <p>在 {@link #getOrCreate} 首次访问某键且缓存未命中时调用。
     * 返回 {@code null} 表示无法加载，此时<b>不</b>缓存（下次 {@link #getOrCreate} 仍会重试）。
     *
     * <p><b>默认实现</b>委托基类 {@link #setLoader(Function) loader} 槽位：
     * 若已通过 {@link #setLoader(Function)} 注入加载函数则调用之，否则返回 {@code null}。
     * 因此<b>外部注入</b>（{@code setLoader(fn)}）与<b>子类重写</b>两种方式任选其一即可启用自动加载；
     * 二者皆未提供时返回 {@code null}（不缓存）。重写本方法后 loader 槽位不再生效。
     *
     * <p>在 {@code getOrCreate} 持有的同步锁内执行，重写或注入的加载逻辑应避免长时间阻塞
     * （如耗时 IO），否则会阻塞其他线程访问本缓存。
     *
     * @param key 键
     * @return 加载的值，或 {@code null}（表示不存在/加载失败，不缓存）
     */
    protected V load(K key) {
        Function<K, V> l = getLoader();
        return l != null ? l.apply(key) : null;
    }

    /**
     * 取值并标记为最近使用；缓存未命中时通过 {@link #load} 加载并放入缓存。
     *
     * <p>若 {@link #load} 返回 {@code null}（表示不存在/加载失败），则<b>不</b>缓存，
     * 直接返回 {@code null}，下次调用仍会重试加载（避免缓存穿透）。
     *
     * @param key 键
     * @return 值，或 {@code null}（当 {@link #load} 返回 null）
     */
    public synchronized V getOrCreate(K key) {
        V existing = data.get(key);
        if (existing != null) {
            return existing;
        }
        V loaded = load(key);
        if (loaded == null) {
            return null;
        }
        V prev = data.putIfAbsent(key, loaded);
        if (prev != null) {
            return prev;
        }
        trimToSize();
        return loaded;
    }

    /**
     * 获取值并标记为最近使用（不存在时返回 {@code null}，不自动加载）。
     *
     * @param key 键
     * @return 值，或 {@code null}
     */
    @Override
    public synchronized V get(K key) {
        V value = data.get(key);
        if (value != null && isExpired(key, value)) {
            evictEntry(key, value);
            value = null;
        }
        return value;
    }

    /**
     * 获取值并标记为最近使用，不存在时返回默认值。
     *
     * @param key    键
     * @param defVal 默认值
     * @return 值或默认值
     */
    public synchronized V getOrDefault(K key, V defVal) {
        return data.getOrDefault(key, defVal);
    }

    /**
     * 放入键值对（覆盖已有值），返回旧值。可能触发容量淘汰。
     */
    @Override
    public synchronized V put(K key, V value) {
        V old = data.put(key, value);
        trimToSize();
        return old;
    }

    /**
     * 仅当键不存在时放入，返回已存在的值或 {@code null}。可能触发容量淘汰。
     */
    public synchronized V putIfAbsent(K key, V value) {
        V prev = data.putIfAbsent(key, value);
        if (prev == null) {
            trimToSize();
        }
        return prev;
    }

    /** 移除键值对，返回旧值。 */
    @Override
    public synchronized V remove(K key) {
        return data.remove(key);
    }

    /** 是否包含指定键。 */
    @Override
    public synchronized boolean containsKey(K key) {
        V value = data.get(key);
        if (value == null) {
            return false;
        }
        if (isExpired(key, value)) {
            evictEntry(key, value);
            return false;
        }
        return true;
    }

    /** 当前条目数。 */
    @Override
    public synchronized int size() {
        return data.size();
    }

    /** 是否为空。 */
    public synchronized boolean isEmpty() {
        return data.isEmpty();
    }

    /**
     * 动态调整最大容量。
     *
     * <p>若新容量小于当前条目数，会立即淘汰多余的最久未访问条目（逐条经 {@link #onEvicted} 记录）。
     *
     * @param maxSize 新的最大容量（必须 > 0）
     * @return 当前对象（便于链式调用）
     * @throws IllegalArgumentException 若 {@code maxSize <= 0}
     */
    public synchronized LruCacheMap<K, V> setMaxSize(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive: " + maxSize);
        }
        this.maxSize = maxSize;
        trimToSize();
        return this;
    }

    /**
     * 淘汰多余条目，直到 {@code size() <= maxSize}（从最久未访问开始）。
     * 必须在持有本对象同步锁时调用。
     */
    @SuppressWarnings("unchecked")
    private void trimToSize() {
        LinkedHashMap<K, V> map = (LinkedHashMap<K, V>) data;
        var it = map.entrySet().iterator();
        while (map.size() > maxSize && it.hasNext()) {
            Map.Entry<K, V> eldest = it.next();
            it.remove();
            onEvicted(eldest.getKey(), eldest.getValue());
        }
    }

    /**
     * 清空所有条目（<b>不</b>触发淘汰回调与统计）。
     */
    @Override
    public synchronized void clear() {
        data.clear();
    }

    /**
     * 所有键的快照副本（按从最久未访问到最近访问的顺序）。
     */
    public synchronized Set<K> keys() {
        return new HashSet<>(data.keySet());
    }

    /**
     * 所有值的快照副本（按从最久未访问到最近访问的顺序）。
     */
    @Override
    public synchronized Collection<V> values() {
        return new ArrayList<>(data.values());
    }

    /**
     * 遍历所有条目（按从最久未访问到最近访问的顺序）。
     *
     * @param action 对每个条目执行的动作
     */
    public synchronized void forEach(BiConsumer<K, V> action) {
        data.forEach(action);
    }
}
