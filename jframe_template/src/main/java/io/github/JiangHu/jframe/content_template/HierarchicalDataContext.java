package io.github.JiangHu.jframe.content_template;

import io.github.JiangHu.jframe.core.data.reactive.ChangeSet;
import io.github.JiangHu.jframe.core.data.reactive.DataContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 分层响应式键值容器——继承 {@link DataContext}，支持 <b>parent（全局）+ local（玩家）</b> 两层数据。
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li><b>读取合并</b>：{@code get()} / {@code asMap()} / {@code snapshot()} 先读 parent（全局），
 *       再用 local（玩家）覆盖同名 key —— <b>玩家优先，全局兜底</b></li>
 *   <li><b>写入隔离</b>：{@code put()} / {@code putAll()} 只写入 local，不影响 parent</li>
 *   <li><b>变更传播</b>：{@code onChange()} 同时捕获 local 变更（super.fireChange）和
 *       parent 变更（转发），注册一次即可感知两层数据变化</li>
 *   <li><b>生命周期管理</b>：{@code dispose()} 解除 parent 监听，避免内存泄漏</li>
 * </ul>
 *
 * <h3>典型场景</h3>
 * <pre>{@code
 * // 全局数据（所有玩家共享）
 * DataContext global = DataContext.of();
 * global.put("serverName", "我的服务器");
 * global.put("onlineCount", 42);
 *
 * // 玩家数据（继承全局，同名 key 玩家优先）
 * HierarchicalDataContext player = HierarchicalDataContext.of(global);
 * player.put("coins", 1000);
 * player.put("serverName", "我的专属服务器"); // 玩家覆盖全局
 *
 * player.get("serverName");   // → "我的专属服务器"（玩家优先）
 * player.get("onlineCount");  // → 42（全局兜底）
 * player.get("coins");        // → 1000（玩家私有）
 *
 * // 全局变更 → 自动传播到所有子 context 的 onChange 监听器
 * global.put("onlineCount", 43);
 * }</pre>
 *
 * <h3>线程安全</h3>
 * <ul>
 *   <li>继承 {@link DataContext} 的线程安全保证（ConcurrentHashMap + CopyOnWriteArrayList）</li>
 *   <li>全局监听器转发列表使用独立的 {@link CopyOnWriteArrayList}</li>
 *   <li>回调在锁外执行，单个异常不影响其他监听器</li>
 * </ul>
 *
 * <h3>内存管理</h3>
 * <p>构造时在 parent 上注册监听器，parent 生命周期通常为应用级。
 * 子 context 不再使用时（如玩家退出），<b>必须</b>调用 {@link #dispose()} 解除监听，
 * 否则 parent 会持有子 context 的强引用导致无法 GC。
 *
 * @see DataContext
 */
public class HierarchicalDataContext extends DataContext {

    /** 父级（全局）DataContext，提供兜底数据 */
    private final DataContext parent;

    /**
     * parent 变更转发监听器（存储为字段，确保注册和移除使用同一个对象引用）。
     * <p>注意：Java 中每次写 {@code this::onParentChange} 可能创建不同对象，
     * 导致 {@code removeListener} 无法匹配，因此必须缓存为字段。
     */
    private final Consumer<ChangeSet> parentChangeListener = this::onParentChange;

    /** 全局变更转发列表：parent 变更时通知这些监听器 */
    private final CopyOnWriteArrayList<Consumer<ChangeSet>> globalListeners = new CopyOnWriteArrayList<>();

    /** dispose 标记，防止重复清理 */
    private volatile boolean disposed = false;

    /**
     * 构造一个绑定指定 parent 的分层 DataContext。
     *
     * @param parent 父级（全局）DataContext，提供兜底数据；为 null 时退化为普通 DataContext
     */
    public HierarchicalDataContext(DataContext parent) {
        super();
        this.parent = parent;
        if (parent != null) {
            parent.onChange(parentChangeListener);
        }
    }

    /**
     * 工厂方法：创建绑定指定 parent 的分层 DataContext。
     *
     * @param parent 父级（全局）DataContext
     * @return 新建的 HierarchicalDataContext
     */
    public static HierarchicalDataContext of(DataContext parent) {
        return new HierarchicalDataContext(parent);
    }

    // ==================== 读取：玩家优先，全局兜底 ====================

    /**
     * 读取值（支持嵌套路径），查找顺序：local → parent。
     * <p>同名 key <b>玩家优先</b>，local 不存在时回退到 parent（全局）。
     *
     * @param key 键名或嵌套路径（如 {@code "player.name"}）
     * @return 值，local 和 parent 都不存在时返回 {@code null}
     */
    @Override
    public Object get(String key) {
        Object local = super.get(key);
        if (local != null) {
            return local;
        }
        return parent != null ? parent.get(key) : null;
    }

    /**
     * 返回合并后的可变副本（供 SpEL 求值 / 渲染使用）。
     * <p>合并策略：先放入 parent（全局），再用 local（玩家）覆盖同名 key。
     *
     * @return 合并后的可变 Map（global + local，local 优先）
     */
    @Override
    public Map<String, Object> asMap() {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (parent != null) {
            merged.putAll(parent.asMap());
        }
        merged.putAll(super.asMap());
        return merged;
    }

    /**
     * 返回合并后的不可变快照（供渲染时安全读取）。
     * <p>合并策略同 {@link #asMap()}：parent 先放入，local 覆盖。
     *
     * @return 合并后的不可变 Map
     */
    @Override
    public Map<String, Object> snapshot() {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (parent != null) {
            merged.putAll(parent.snapshot());
        }
        merged.putAll(super.snapshot());
        return Collections.unmodifiableMap(merged);
    }

    // ==================== 监听：同时捕获 local + parent 变更 ====================

    /**
     * 注册变更监听器，同时感知 <b>local 变更</b>（玩家 put/putAll）和
     * <b>parent 变更</b>（全局 put/putAll 转发）。
     * <p>注册一次即可捕获两层数据变化，无需分别注册。
     *
     * @param listener 变更监听器
     * @return this（链式调用）
     */
    @Override
    public HierarchicalDataContext onChange(Consumer<ChangeSet> listener) {
        super.onChange(listener);                  // 捕获 local 变更（super.fireChange）
        globalListeners.addIfAbsent(listener);     // 捕获 parent 变更（onParentChange 转发）
        return this;
    }

    /**
     * 移除已注册的监听器（同时从 local 和 parent 转发列表中移除）。
     *
     * @param listener 要移除的监听器
     * @return this（链式调用）
     */
    @Override
    public HierarchicalDataContext removeListener(Consumer<ChangeSet> listener) {
        super.removeListener(listener);
        globalListeners.remove(listener);
        return this;
    }

    // ==================== 生命周期管理 ====================

    /**
     * 销毁此 context，解除对 parent 的监听并清空全局转发列表。
     * <p><b>必须在子 context 不再使用时调用</b>（如玩家退出计分板时），
     * 否则 parent（全局 DataContext，通常为应用级生命周期）会持有此 context 的强引用，
     * 导致无法被 GC 回收。
     * <p>多次调用安全（幂等）。
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (parent != null) {
            parent.removeListener(parentChangeListener);
        }
        globalListeners.clear();
    }

    // ==================== 内部方法 ====================

    /**
     * parent 变更回调：将全局 ChangeSet 转发给所有注册的监听器。
     * <p>回调在锁外执行，单个监听器异常不影响其他监听器（与 {@link DataContext#fireChange} 策略一致）。
     */
    private void onParentChange(ChangeSet changeSet) {
        if (disposed || changeSet == null || changeSet.isEmpty()) {
            return;
        }
        for (Consumer<ChangeSet> listener : globalListeners) {
            try {
                listener.accept(changeSet);
            } catch (Exception e) {
                // 单个监听器异常不影响其他监听器
                Thread.currentThread().interrupt();
            }
        }
    }
}
