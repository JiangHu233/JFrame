package io.github.JiangHu.jframe.core.data.map;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * 功能可插拔的 Map 抽象基类，是 {@link LruCacheMap} 与 {@link PlayerDataMap} 的共同父类。
 *
 * <h3>键源 S 与内部键 K（K 对使用者不透明）</h3>
 * 本类以「键源」{@code S} 作为对外读写入口的参数类型，内部通过
 * {@link #setKeyExtractor(Function) keyExtractor}（{@code Function<S,K>}）将源对象转换为真正的存储键 {@code K}。
 * 因此 {@code K} 对使用者<b>不透明</b>——所有读写操作均以 {@code S} 为参数，使用者无需（也不应）直接操作 {@code K}：
 * <ul>
 *   <li>{@link #get(Object) get(s)} / {@link #put(Object, Object) put(s, v)} /
 *       {@link #remove(Object) remove(s)} / {@link #containsKey(Object) containsKey(s)} /
 *       {@link #getOrCreate(Object) getOrCreate(s)}。</li>
 * </ul>
 * 两个子类的 {@code S} 取值：
 * <ul>
 *   <li>{@link LruCacheMap}：{@code S=K}，键即源（通用缓存，直接用键读写）；</li>
 *   <li>{@link PlayerDataMap}：{@code S=Player}，键来自玩家（{@code K} 可为玩家名 / UUID 等）。</li>
 * </ul>
 *
 * <p><b>后端存储</b>：默认 {@link ConcurrentHashMap}；子类可通过
 * {@link #AbstractFunctionalMap(Map)} 传入自定义 Map 实现（例如 {@link LruCacheMap}
 * 传入 access-order 的 {@link java.util.LinkedHashMap}）。子类通过 {@link #data} 字段访问后端。
 *
 * <p><b>4 个功能槽位</b>（注入函数即启用，{@code null} 即关闭）：
 * <ul>
 *   <li>{@link #setKeyExtractor(Function) keyExtractor} {@code Function<S,K>} ——
 *       从键源对象提取内部键 {@code K}，是所有读写操作的前提（未设置时读写会抛异常）。</li>
 *   <li>{@link #setLoader(Function) loader} {@code Function<S,V>} ——
 *       缓存缺失时从键源自动加载，支持 {@link #getOrCreate(Object) getOrCreate(s)}。</li>
 *   <li>{@link #setExpiryChecker(BiPredicate) expiryChecker} {@code BiPredicate<K,V>} ——
 *       过期判断（基于内部键与值），读取时惰性淘汰，也可 {@link #evictExpired()} 主动扫描。</li>
 *   <li>{@link #setOnEvict(BiConsumer) onEvict} {@code BiConsumer<K,V>} ——
 *       淘汰回调：任何条目被淘汰时触发（释放资源 / 回写磁盘等）。</li>
 * </ul>
 *
 * @param <S> 键源类型：对外读写入口的参数类型（「和键有关的对象」）
 * @param <K> 内部存储键类型（对使用者不透明，由 keyExtractor 从 S 转换而来）
 * @param <V> 值类型
 */
@Getter
@Accessors(chain = true)
public abstract class AbstractFunctionalMap<S, K, V> {

    /**
     * 后端存储。默认 {@link ConcurrentHashMap}；子类可在构造时通过
     * {@link #AbstractFunctionalMap(Map)} 指定（如 access-order {@link java.util.LinkedHashMap}）。
     */
    @Getter(AccessLevel.NONE)
    protected final Map<K, V> data;

    /** 从键源对象提取内部键（所有读写操作的前提）。 */
    @Setter private Function<S, K> keyExtractor;
    /** 缓存缺失加载器（从键源加载），启用 {@link #getOrCreate(Object)}。 */
    @Setter private Function<S, V> loader;
    /** 过期判断（基于内部键与值），启用读取时惰性淘汰与 {@link #evictExpired()}。 */
    @Setter private BiPredicate<K, V> expiryChecker;
    /** 淘汰回调：任何条目被淘汰时触发。 */
    @Setter private BiConsumer<K, V> onEvict;

    /** 默认构造：使用 {@link ConcurrentHashMap} 后端（线程安全，无访问顺序）。 */
    protected AbstractFunctionalMap() {
        this(new ConcurrentHashMap<>());
    }

    /**
     * 自定义后端 Map。
     *
     * @param backend 后端 Map（如 access-order {@link java.util.LinkedHashMap}），非 null
     */
    protected AbstractFunctionalMap(Map<K, V> backend) {
        this.data = Objects.requireNonNull(backend);
    }

    // ==================== 淘汰回调（供子类调用） ====================

    /**
     * 触发淘汰回调：调用注入的 {@code onEvict}。
     *
     * <p>子类在执行实际移除（容量淘汰 / 过期淘汰 / 玩家退出清理等）后调用本方法。
     * 本方法<b>不</b>执行移除，仅触发回调。
     *
     * @param key   被淘汰条目的内部键
     * @param value 被淘汰条目的值
     */
    protected void onEvicted(K key, V value) {
        if (onEvict != null) {
            onEvict.accept(key, value);
        }
    }

    // ==================== 键提取 ====================

    /**
     * 从键源对象提取内部键（委托注入的 {@code keyExtractor}）。
     *
     * <p>「键源」{@code S} 是对外读写入口的参数类型，与真正的存储键 {@code K} 解耦：
     * {@link LruCacheMap} 中 {@code S=K}（键即源），{@link PlayerDataMap} 中 {@code S=Player}。
     *
     * @param source 键源对象
     * @return 提取出的内部键
     * @throws IllegalStateException 未设置 keyExtractor 时
     */
    protected K extractKey(S source) {
        if (keyExtractor == null) {
            throw new IllegalStateException(
                    "keyExtractor 未设置，无法从键源提取内部键；请先 setKeyExtractor");
        }
        return keyExtractor.apply(source);
    }

    // ==================== 加载 ====================

    /**
     * 取值，缺失时用 {@code loader} 自动加载并写入。
     *
     * <p>命中但已过期则先淘汰再走未命中流程；loader 返回 {@code null} 不缓存。
     *
     * @param source 键源
     * @return 命中/加载到的值，加载失败为 {@code null}
     */
    public V getOrCreate(S source) {
        K key = extractKey(source);
        V value = data.get(key);
        if (value != null && isExpired(key, value)) {
            evictEntry(key, value);
            value = null;
        }
        if (value != null) {
            return value;
        }
        if (loader == null) {
            return null;
        }
        V loaded = loader.apply(source);
        if (loaded == null) {
            return null;
        }
        V prev = data.putIfAbsent(key, loaded);
        return prev != null ? prev : loaded;
    }

    // ==================== 过期淘汰 ====================

    /** 是否过期（委托注入的 {@code expiryChecker}，未设置则永不过期）。 */
    protected boolean isExpired(K key, V value) {
        return expiryChecker != null && expiryChecker.test(key, value);
    }

    /**
     * 原子淘汰单个条目：仅当值未被并发修改时 {@code remove(key, value)} 并触发回调。
     * 适用于后端支持 {@code remove(key, value)} 的实现（如 {@link ConcurrentHashMap}）。
     */
    protected void evictEntry(K key, V value) {
        if (data.remove(key, value)) {
            onEvicted(key, value);
        }
    }

    /**
     * 主动扫描并淘汰所有过期项（依赖 {@code expiryChecker}）。
     *
     * @return 本次淘汰的数量
     */
    public int evictExpired() {
        if (expiryChecker == null) {
            return 0;
        }
        int n = 0;
        for (Map.Entry<K, V> e : data.entrySet()) {
            if (expiryChecker.test(e.getKey(), e.getValue())) {
                evictEntry(e.getKey(), e.getValue());
                n++;
            }
        }
        return n;
    }

    // ==================== 基础操作（均以键源 S 为参数，K 不透明） ====================

    /**
     * 取值，命中但已过期则淘汰并返回 {@code null}。
     * 不会自动加载，需要加载请用 {@link #getOrCreate(Object)}。
     *
     * @param source 键源
     * @return 值，或 {@code null}
     */
    public V get(S source) {
        K key = extractKey(source);
        V value = data.get(key);
        if (value != null && isExpired(key, value)) {
            evictEntry(key, value);
            value = null;
        }
        return value;
    }

    /**
     * 放入键值对（覆盖已有值），返回旧值。
     *
     * @param source 键源
     * @param value  值
     * @return 该键源原来的旧值（无则为 {@code null}）
     */
    public V put(S source, V value) {
        return data.put(extractKey(source), value);
    }

    /**
     * 移除键源对应的条目，返回旧值。
     *
     * @param source 键源
     * @return 被移除的值，或 {@code null}
     */
    public V remove(S source) {
        return data.remove(extractKey(source));
    }

    /**
     * 是否包含且未过期（过期项会被惰性淘汰）。
     *
     * @param source 键源
     * @return {@code true} 表示存在且未过期
     */
    public boolean containsKey(S source) {
        K key = extractKey(source);
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

    public int size() {
        return data.size();
    }

    public void clear() {
        data.clear();
    }

    public Collection<V> values() {
        return data.values();
    }
}
