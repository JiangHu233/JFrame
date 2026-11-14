package io.github.JiangHu.jframe.engine.ecs.registry;

import io.github.JiangHu.jframe.engine.ecs.despawner.Despawner;
import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 回收器注册表 — 注册名 ↔ 回收器类；定义加载时按名实例化并注入参数（与 SpawnerRegistry 同构）。
 */
public class DespawnerRegistry {

    /** 注册名 → 回收器类 */
    private final Map<String, Class<? extends Despawner>> byName = new LinkedHashMap<>();

    /**
     * 注册回收器类。
     *
     * @param name 注册名（定义文件 despawn.use 引用）
     * @param type 回收器类（无参构造）
     * @throws EcsConfigException 重名或缺少无参构造
     */
    public synchronized void register(String name, Class<? extends Despawner> type) {
        if (name == null || name.isBlank()) {
            throw new EcsConfigException("回收器名不得为空");
        }
        if (byName.containsKey(name)) {
            throw new EcsConfigException("回收器名重复注册: '" + name + "'（已有 " + byName.get(name).getName() + "）");
        }
        ComponentRegistry.requireNoArgConstructor(type);
        byName.put(name, type);
    }

    /**
     * 按注册名取回收器类（不存在即抛异常）。
     *
     * @param name 注册名
     * @return 回收器类
     * @throws EcsConfigException 未注册
     */
    public synchronized Class<? extends Despawner> require(String name) {
        Class<? extends Despawner> type = byName.get(name);
        if (type == null) {
            throw new EcsConfigException("未注册的回收器名: '" + name + "'（已注册: " + byName.keySet() + "）");
        }
        return type;
    }

    /**
     * 按注册名实例化回收器并注入参数（定义加载时调用一次，每条定义引用独立实例）。
     *
     * @param name   注册名
     * @param params 参数表
     * @return 已注入参数的回收器实例
     * @throws EcsConfigException 实例化或注入失败
     */
    public synchronized Despawner create(String name, Map<String, Object> params) {
        Class<? extends Despawner> type = require(name);
        Despawner despawner;
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.trySetAccessible();
            despawner = constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new EcsConfigException("回收器实例化失败: " + type.getName(), e);
        }
        ParamBinder.bind(despawner, params);
        return despawner;
    }

    /**
     * 已注册回收器快照。
     *
     * @return 注册名 → 类
     */
    public synchronized Map<String, Class<? extends Despawner>> all() {
        return Map.copyOf(byName);
    }
}
