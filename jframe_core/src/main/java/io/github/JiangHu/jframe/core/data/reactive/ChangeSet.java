package io.github.JiangHu.jframe.core.data.reactive;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 变更集——记录 {@link DataContext} 一次批量更新中涉及的所有键值变化。
 *
 * <p>每次 {@link DataContext#put} 或 {@link DataContext#putAll} 执行后，
 * 会构造一个 {@code ChangeSet} 并传递给所有已注册的监听器。监听器可据此判断
 * 是否需要重新渲染、哪些 key 发生了变化。
 *
 * <p>本类不可变，线程安全。
 *
 * @see DataContext#onChange
 */
public final class ChangeSet {

    private final Map<String, Change> changes;

    private ChangeSet(Map<String, Change> changes) {
        this.changes = Collections.unmodifiableMap(new LinkedHashMap<>(changes));
    }

    /** 包内工厂方法，仅 {@link DataContext} 可调用 */
    static ChangeSet of(Map<String, Change> changes) {
        return new ChangeSet(changes);
    }

    /** 本次变更是否为空 */
    public boolean isEmpty() {
        return changes.isEmpty();
    }

    /** 本次变更涉及的 key 数量 */
    public int size() {
        return changes.size();
    }

    /** 是否包含指定 key 的变更 */
    public boolean containsKey(String key) {
        return changes.containsKey(key);
    }

    /** 获取指定 key 的变更记录，不存在返回 {@code null} */
    public Change getChange(String key) {
        return changes.get(key);
    }

    /** 获取所有发生变更的 key */
    public Set<String> changedKeys() {
        return changes.keySet();
    }

    /** 获取所有变更记录（不可变视图） */
    public Map<String, Change> changes() {
        return changes;
    }

    /**
     * 单个 key 的变更记录。
     *
     * @param oldValue 变更前的旧值（首次写入时为 {@code null}）
     * @param newValue 变更后的新值
     */
    public record Change(Object oldValue, Object newValue) {

        /** 值是否实际发生了变化（排除 {@code null}→{@code null}） */
        public boolean hasChanged() {
            return !Objects.equals(oldValue, newValue);
        }
    }
}
