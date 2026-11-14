package io.github.JiangHu.jframe.engine.ecs.registry;

import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;
import io.github.JiangHu.jframe.engine.ecs.selector.EntitySelector;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 选择器注册表 — 注册名 ↔ 选择器类；定义加载时按名实例化并注入参数。
 */
public class SelectorRegistry {

    /** 注册名 → 选择器类 */
    private final Map<String, Class<? extends EntitySelector>> byName = new LinkedHashMap<>();

    /**
     * 注册选择器类。
     *
     * @param name 注册名（定义文件 selector.use 引用）
     * @param type 选择器类（公共无参构造）
     * @throws EcsConfigException 重名或缺少公共无参构造
     */
    public synchronized void register(String name, Class<? extends EntitySelector> type) {
        if (name == null || name.isBlank()) {
            throw new EcsConfigException("选择器名不得为空");
        }
        if (byName.containsKey(name)) {
            throw new EcsConfigException("选择器名重复注册: '" + name + "'（已有 " + byName.get(name).getName() + "）");
        }
        ComponentRegistry.requireNoArgConstructor(type);
        byName.put(name, type);
    }

    /**
     * 按注册名取选择器类（不存在即抛异常）。
     *
     * @param name 注册名
     * @return 选择器类
     * @throws EcsConfigException 未注册
     */
    public synchronized Class<? extends EntitySelector> require(String name) {
        Class<? extends EntitySelector> type = byName.get(name);
        if (type == null) {
            throw new EcsConfigException("未注册的选择器名: '" + name + "'（已注册: " + byName.keySet() + "）");
        }
        return type;
    }

    /**
     * 按注册名实例化选择器并注入参数（定义加载时调用一次，每条定义引用独立实例）。
     *
     * @param name   注册名
     * @param params 参数表
     * @return 已注入参数的选择器实例
     * @throws EcsConfigException 实例化或注入失败
     */
    public synchronized EntitySelector create(String name, Map<String, Object> params) {
        Class<? extends EntitySelector> type = require(name);
        EntitySelector selector;
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.trySetAccessible();
            selector = constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new EcsConfigException("选择器实例化失败: " + type.getName(), e);
        }
        ParamBinder.bind(selector, params);
        return selector;
    }

    /**
     * 已注册选择器快照。
     *
     * @return 注册名 → 类
     */
    public synchronized Map<String, Class<? extends EntitySelector>> all() {
        return Map.copyOf(byName);
    }
}
