package io.github.JiangHu.jframe.engine.behavior;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行为树黑板 — 树的工作记忆（对齐 UE Blackboard 的一等公民抽象）。
 *
 * <p>每宿主一份（如每只 AI 一个黑板实例），条件叶读、行为叶写；
 * 键为字符串、值类型化读取（取错类型返回缺省，不抛异常——决策链要的是
 * "条件不满足"而非崩溃）。树模板是多宿主共享单例，<b>一切每宿主状态
 * （冷却计时/目标引用/计时起点）必须存黑板而非节点字段</b>。
 */
public final class BtBlackboard {

    /** 键值存储 */
    private final Map<String, Object> values = new ConcurrentHashMap<>();

    /**
     * 写键值。
     *
     * @param key   键
     * @param value 值（null 等价移除）
     */
    public void put(String key, Object value) {
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
    }

    /**
     * 类型化读取。
     *
     * @param key          键
     * @param type         期望类型
     * @param defaultValue 键缺失/类型不符时的缺省
     * @return 值或缺省
     */
    public <T> T get(String key, Class<T> type, T defaultValue) {
        Object value = values.get(key);
        return type.isInstance(value) ? type.cast(value) : defaultValue;
    }

    /**
     * 读长整型（tick 计时类常用）。
     *
     * @param key 键
     * @param dft 缺省
     * @return 值或缺省
     */
    public long getLong(String key, long dft) {
        return get(key, Long.class, dft);
    }

    /**
     * 读双精度。
     *
     * @param key 键
     * @param dft 缺省
     * @return 值或缺省
     */
    public double getDouble(String key, double dft) {
        return get(key, Double.class, dft);
    }

    /**
     * 读布尔。
     *
     * @param key 键
     * @param dft 缺省
     * @return 值或缺省
     */
    public boolean getBoolean(String key, boolean dft) {
        return get(key, Boolean.class, dft);
    }

    /**
     * 读对象引用（目标实体等）。
     *
     * @param key 键
     * @return 值；缺失返回 null
     */
    public Object get(String key) {
        return values.get(key);
    }

    /**
     * 移除键。
     *
     * @param key 键
     */
    public void remove(String key) {
        values.remove(key);
    }

    /**
     * 清空（宿主重置/树热载时）。
     */
    public void clear() {
        values.clear();
    }

    /**
     * 键值条目快照（诊断遍历）。
     *
     * @return 条目集合
     */
    public java.util.Set<Map.Entry<String, Object>> entries() {
        return values.entrySet();
    }
}
