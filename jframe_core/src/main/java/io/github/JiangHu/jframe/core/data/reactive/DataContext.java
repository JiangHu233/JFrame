package io.github.JiangHu.jframe.core.data.reactive;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 轻量响应式键值容器——模板引擎的数据源。
 *
 * <p>与 {@link EntangledValue} 互补：
 * <ul>
 *   <li>{@link EntangledValue} 解决「一处数据 → 多处纠缠感知」（量子纠缠模型）</li>
 *   <li>{@code DataContext} 解决「一组键值数据变化 → 触发监听」（观察者模型）</li>
 * </ul>
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li><b>键值存储</b>：{@code Map<String, Object>}，支持任意类型值</li>
 *   <li><b>嵌套路径访问</b>：{@code get("player.name")} 自动按 {@code .} 分隔逐层取属性
 *       （先按 flat key 查找，找不到再按嵌套路径查找，支持 Map / POJO 字段 / getter）</li>
 *   <li><b>变更监听</b>：{@code put} 时通知所有监听器，携带 {@link ChangeSet}</li>
 *   <li><b>批量更新</b>：{@code putAll} 只触发一次通知（避免抖动）</li>
 *   <li><b>静默写入</b>：{@code putSilently} 不触发通知</li>
 *   <li><b>不可变快照</b>：{@code snapshot()} 返回只读副本，供渲染时安全读取</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <ul>
 *   <li>内部 {@link ConcurrentHashMap} 存储</li>
 *   <li>监听器列表 {@link CopyOnWriteArrayList}</li>
 *   <li>监听器回调在锁外执行，单个异常不影响其他监听器</li>
 * </ul>
 *
 * <h3>快速上手</h3>
 * <pre>{@code
 * DataContext data = DataContext.of(Map.of(
 *     "serverName", "我的服务器",
 *     "player", Map.of("name", "Steve", "level", 10),
 *     "coins", 5000
 * ));
 *
 * data.get("player.name");   // → "Steve"（嵌套路径访问）
 * data.get("coins");         // → 5000
 *
 * data.onChange(change -> {
 *     if (change.containsKey("coins")) {
 *         System.out.println("金币变了！");
 *     }
 * });
 *
 * data.put("coins", 6000);   // 触发监听器
 * }</pre>
 *
 * @see ChangeSet
 * @see EntangledValue
 */
public class DataContext {

    private final ConcurrentHashMap<String, Object> data = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<ChangeSet>> listeners = new CopyOnWriteArrayList<>();

    private DataContext() {}

    private DataContext(Map<String, Object> initial) {
        if (initial != null) {
            data.putAll(initial);
        }
    }

    // ===== 工厂 =====

    /** 创建空的 DataContext */
    public static DataContext of() {
        return new DataContext();
    }

    /** 创建并预填数据的 DataContext */
    public static DataContext of(Map<String, Object> data) {
        return new DataContext(data);
    }

    // ===== 写入 =====

    /**
     * 写入单个键值，触发变更通知。
     *
     * @return this（链式调用）
     */
    public DataContext put(String key, Object value) {
        Object oldValue = data.put(key, value);
        fireChange(Map.of(key, new ChangeSet.Change(oldValue, value)));
        return this;
    }

    /**
     * 批量写入，只触发<b>一次</b>变更通知（携带所有变更的 key）。
     *
     * @return this（链式调用）
     */
    public DataContext putAll(Map<String, Object> entries) {
        if (entries == null || entries.isEmpty()) {
            return this;
        }
        Map<String, ChangeSet.Change> changes = new LinkedHashMap<>();
        for (var e : entries.entrySet()) {
            Object oldValue = data.put(e.getKey(), e.getValue());
            changes.put(e.getKey(), new ChangeSet.Change(oldValue, e.getValue()));
        }
        fireChange(changes);
        return this;
    }

    /**
     * 静默写入——更新值但<b>不触发</b>变更通知。
     * <p>适用于初始化阶段批量填充数据，避免触发不必要的渲染。
     *
     * @return this（链式调用）
     */
    public DataContext putSilently(String key, Object value) {
        data.put(key, value);
        return this;
    }

    // ===== 读取 =====

    /**
     * 读取值，支持嵌套路径访问。
     * <p>查找顺序：
     * <ol>
     *   <li>先按完整 key 作为 flat key 直接查找</li>
     *   <li>找不到时，按 {@code .} 分割逐层查找（支持 Map / POJO 字段 / getter 方法）</li>
     * </ol>
     *
     * @param key 键名或嵌套路径（如 {@code "player.name"}）
     * @return 值，不存在返回 {@code null}
     */
    public Object get(String key) {
        // 1. 先按 flat key 查找
        if (data.containsKey(key)) {
            return data.get(key);
        }
        // 2. key 不含 "."，无需嵌套查找
        if (!key.contains(".")) {
            return null;
        }
        // 3. 按嵌套路径查找
        return resolveNestedPath(key);
    }

    /**
     * 读取值，不存在或类型不匹配时返回默认值。
     *
     * @param key          键名或嵌套路径
     * @param defaultValue 默认值
     * @return 值或默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        Object value = get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return (T) value;
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }

    /**
     * 返回内部数据的<b>不可变快照</b>，供渲染时安全读取。
     */
    public Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    /**
     * 返回内部数据的<b>可变副本</b>，供 SpEL 求值使用。
     * <p>SpEL 以此 Map 作为 root object，{@code {{player.name}}} 会自动按嵌套属性访问。
     */
    public Map<String, Object> asMap() {
        return new LinkedHashMap<>(data);
    }

    // ===== 监听 =====

    /**
     * 注册全局变更监听器。每次 {@code put} / {@code putAll} 都会回调。
     *
     * @return this（链式调用）
     */
    public DataContext onChange(Consumer<ChangeSet> listener) {
        listeners.addIfAbsent(listener);
        return this;
    }

    /**
     * 移除已注册的监听器。
     *
     * @return this（链式调用）
     */
    public DataContext removeListener(Consumer<ChangeSet> listener) {
        listeners.remove(listener);
        return this;
    }

    // ===== 内部方法 =====

    /** 触发变更通知（在锁外执行，单个异常不影响其他监听器） */
    private void fireChange(Map<String, ChangeSet.Change> changes) {
        if (changes.isEmpty() || listeners.isEmpty()) {
            return;
        }
        ChangeSet changeSet = ChangeSet.of(changes);
        for (var listener : listeners) {
            try {
                listener.accept(changeSet);
            } catch (Exception e) {
                // 单个监听器异常不影响其他监听器
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 嵌套路径查找：按 {@code .} 分割，逐层取属性。
     * <p>支持 Map（{@code map.get(key)}）和 POJO（反射取字段，再尝试 getter）。
     */
    private Object resolveNestedPath(String path) {
        int dot = path.indexOf('.');
        String firstKey = path.substring(0, dot);
        Object current = data.get(firstKey);
        if (current == null) {
            return null;
        }
        return resolveProperty(current, path.substring(dot + 1));
    }

    /** 递归解析剩余路径 */
    private Object resolveProperty(Object obj, String path) {
        if (obj == null) {
            return null;
        }
        int dot = path.indexOf('.');
        if (dot < 0) {
            return getPropertyValue(obj, path);
        }
        String firstPart = path.substring(0, dot);
        Object next = getPropertyValue(obj, firstPart);
        return resolveProperty(next, path.substring(dot + 1));
    }

    /** 从对象中取属性值：Map 按键取，POJO 反射取字段或 getter */
    @SuppressWarnings("unchecked")
    private Object getPropertyValue(Object obj, String name) {
        // Map：按键取值
        if (obj instanceof Map) {
            return ((Map<String, Object>) obj).get(name);
        }
        // POJO：反射取字段
        try {
            Field field = findField(obj.getClass(), name);
            if (field != null) {
                field.setAccessible(true);
                return field.get(obj);
            }
        } catch (IllegalAccessException e) {
            // ignore
        }
        // POJO：尝试 getter 方法
        String getterName = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        try {
            var method = obj.getClass().getMethod(getterName);
            return method.invoke(obj);
        } catch (Exception e) {
            return null;
        }
    }

    /** 在类及其父类中查找字段 */
    private Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                // 继续往父类找
            }
        }
        return null;
    }
}
