package io.github.JiangHu.jframe.form.data;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 视图数据总线。
 * <p>
 * 这是新架构解决「窗口间数据塞入与通知更新」的核心组件。
 * 每个 {@link io.github.JiangHu.jframe.form.ViewManager 单玩家管理器}持有一个本类实例，
 * 该玩家打开的所有视图共享同一个数据总线，从而实现：
 * <ul>
 *   <li><strong>数据塞入</strong> —— 一个视图通过 {@link #put} 写入数据，
 *       其他视图可通过 {@link #get} 读取，无需互相持有引用</li>
 *   <li><strong>通知更新</strong> —— 基于「键」的观察者模式：
 *       视图通过 {@link #subscribe} 订阅某个键，当该键被 {@link #put} 更新时，
 *       所有订阅者会被自动回调，从而实现「数据变化即刷新」</li>
 * </ul>
 * <p>
 * 典型场景：主菜单展示金币，子菜单（商店）购买后通知主菜单刷新：
 * <pre>{@code
 * // 主菜单：订阅金币变化
 * subscribe("coins", v -> refresh());
 *
 * // 商店：购买后塞入新金币，自动触发主菜单刷新
 * put("coins", newCoins);
 * }</pre>
 * <p>
 * 线程安全：内部使用 {@link ConcurrentHashMap} 与 {@link CopyOnWriteArrayList}，
 * 允许在异步线程中安全地 {@link #put}（但回调执行仍在调用线程，
 * 若需操作主线程 API，应自行调度回主线程）。
 *
 * @see io.github.JiangHu.jframe.form.ViewManager
 */
public class ViewDataBus {

    /** 数据存储：键 -> 值。 */
    private final Map<String, Object> data = new ConcurrentHashMap<>();

    /** 订阅者：键 -> 回调列表。 */
    private final Map<String, List<Consumer<Object>>> listeners = new ConcurrentHashMap<>();

    /**
     * 塞入（更新）一个数据项，并自动通知该键的所有订阅者。
     *
     * @param key   数据键
     * @param value 数据值（可为 {@code null}，表示清除该键）
     */
    public void put(String key, Object value) {
        if (value == null) {
            data.remove(key);
        } else {
            data.put(key, value);
        }
        notify(key, value);
    }

    /**
     * 读取一个数据项。
     *
     * @param key 数据键
     * @param <T> 值类型（由调用方断言）
     * @return 数据值，不存在时为 {@code null}
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) data.get(key);
    }

    /**
     * 读取一个数据项，不存在时返回默认值。
     *
     * @param key    数据键
     * @param defVal 默认值
     * @param <T>    值类型
     * @return 数据值或默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defVal) {
        Object v = data.get(key);
        return v == null ? defVal : (T) v;
    }

    /**
     * 判断是否包含某个键。
     *
     * @param key 数据键
     * @return 包含返回 {@code true}
     */
    public boolean contains(String key) {
        return data.containsKey(key);
    }

    /**
     * 移除一个数据项，并通知订阅者（值为 {@code null}）。
     *
     * @param key 数据键
     */
    public void remove(String key) {
        data.remove(key);
        notify(key, null);
    }

    /**
     * 订阅某个键的变化。
     * <p>
     * 当该键被 {@link #put} / {@link #remove} 更新时，回调会被触发，
     * 参数为最新的值（移除时为 {@code null}）。
     *
     * @param key      数据键
     * @param listener 回调
     */
    public void subscribe(String key, Consumer<Object> listener) {
        listeners.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * 取消订阅某个键的某个回调。
     *
     * @param key      数据键
     * @param listener 要移除的回调
     */
    public void unsubscribe(String key, Consumer<Object> listener) {
        List<Consumer<Object>> list = listeners.get(key);
        if (list != null) {
            list.remove(listener);
        }
    }

    /**
     * 主动通知某个键的所有订阅者。
     * <p>
     * 通常由 {@link #put} 内部调用，也可在「数据未变但需强制刷新」时手动调用。
     *
     * @param key   数据键
     * @param value 通知给订阅者的值
     */
    public void notify(String key, Object value) {
        List<Consumer<Object>> list = listeners.get(key);
        if (list == null || list.isEmpty()) return;
        for (Consumer<Object> listener : list) {
            listener.accept(value);
        }
    }

    /**
     * 清空全部数据与订阅者。
     * <p>
     * 通常在玩家退出游戏、管理器销毁时调用，避免内存泄漏。
     */
    public void clear() {
        data.clear();
        listeners.clear();
    }
}
