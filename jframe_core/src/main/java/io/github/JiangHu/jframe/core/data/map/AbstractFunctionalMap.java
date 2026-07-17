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
 * <p><b>后端存储</b>：默认 {@link ConcurrentHashMap}；子类可通过
 * {@link #AbstractFunctionalMap(Map)} 传入自定义 Map 实现（例如 {@link LruCacheMap}
 * 传入 access-order 的 {@link java.util.LinkedHashMap}）。子类通过 {@link #data} 字段访问后端。
 *
 * <p><b>4 个功能槽位</b>（注入函数即启用，{@code null} 即关闭）：
 * <ul>
 *   <li>{@link #setKeyExtractor(Function) keyExtractor} {@code Function<V,K>} ——
 *       从值提取键，支持 {@link #putValue(Object) putValue(v)} 免手写 key。</li>
 *   <li>{@link #setLoader(Function) loader} {@code Function<K,V>} ——
 *       缓存缺失自动加载，支持 {@link #getOrCreate(Object) getOrCreate(k)}。</li>
 *   <li>{@link #setExpiryChecker(BiPredicate) expiryChecker} {@code BiPredicate<K,V>} ——
 *       过期判断，读取时惰性淘汰，也可 {@link #evictExpired()} 主动扫描。</li>
 *   <li>{@link #setOnEvict(BiConsumer) onEvict} {@code BiConsumer<K,V>} ——
 *       淘汰回调：任何条目被淘汰时触发（释放资源 / 回写磁盘等）。</li>
 * </ul>
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
@Getter
@Accessors(chain = true)
public abstract class AbstractFunctionalMap<K, V> {

    /**
     * 后端存储。默认 {@link ConcurrentHashMap}；子类可在构造时通过
     * {@link #AbstractFunctionalMap(Map)} 指定（如 access-order {@link java.util.LinkedHashMap}）。
     */
    @Getter(AccessLevel.NONE)
    protected final Map<K, V> data;

    /** 从值提取键，启用 {@link #putValue(Object)}。 */
    @Setter private Function<V, K> keyExtractor;
    /** 缓存缺失加载器，启用 {@link #getOrCreate(Object)}。 */
    @Setter private Function<K, V> loader;
    /** 过期判断，启用读取时惰性淘汰与 {@link #evictExpired()}。 */
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
     * @param key   被淘汰条目的键
     * @param value 被淘汰条目的值
     */
    protected void onEvicted(K key, V value) {
        if (onEvict != null) {
            onEvict.accept(key, value);
        }
    }

    // ==================== 键提取 ====================

    /**
     * 从值提取键（委托注入的 {@code keyExtractor}）。
     *
     * @throws IllegalStateException 未设置 keyExtractor 时
     */
    protected K extractKey(V value) {
        if (keyExtractor == null) {
            throw new IllegalStateException(
                    "keyExtractor 未设置，无法从值提取键；请先 setKeyExtractor 或改用 put(key, value)");
        }
        return keyExtractor.apply(value);
    }

    /**
     * 用 keyExtractor 从值提取键后存入，免手写 key。
     * 需先 {@link #setKeyExtractor(Function)}。
     *
     * @return 该键原来的旧值（无则为 {@code null}）
     */
    public V putValue(V value) {
        return data.put(extractKey(value), value);
    }

    // ==================== 加载 ====================

    /**
     * 取值，缺失时用 {@code loader} 自动加载并写入。
     *
     * <p>命中但已过期则先淘汰再走未命中流程；loader 返回 {@code null} 不缓存。
     *
     * @return 命中/加载到的值，加载失败为 {@code null}
     */
    public V getOrCreate(K key) {
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
        V loaded = loader.apply(key);
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

    // ==================== 基础操作 ====================

    /**
     * 取值，命中但已过期则淘汰并返回 {@code null}。
     * 不会自动加载，需要加载请用 {@link #getOrCreate(Object)}。
     */
    public V get(K key) {
        V value = data.get(key);
        if (value != null && isExpired(key, value)) {
            evictEntry(key, value);
            value = null;
        }
        return value;
    }

    public V put(K key, V value) {
        return data.put(key, value);
    }

    public V remove(K key) {
        return data.remove(key);
    }

    /** 是否包含且未过期（过期项会被惰性淘汰）。 */
    public boolean containsKey(K key) {
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
