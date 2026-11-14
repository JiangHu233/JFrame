package io.github.JiangHu.jframe.engine.ecs.registry;

import cn.nukkit.event.Event;
import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;
import io.github.JiangHu.jframe.engine.ecs.resolver.EcsEventResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宿主解析器注册表 — 注册名 ↔ 解析器实例（无状态单例），并按事件类型建立索引。
 *
 * <p>同一事件类型允许多个解析器（OR 语义：任一解析器提取的候选命中即分发）。
 * 解析器声明的 {@code eventType()} 可为父类型，分发时按
 * 「解析器事件类型 isAssignableFrom 实际触发事件类型」宽匹配。
 */
public class ResolverRegistry {

    /** 注册名 → 解析器实例 */
    private final Map<String, EcsEventResolver> byName = new LinkedHashMap<>();

    /** 解析器声明的事件类型 → 解析器列表 */
    private final Map<Class<? extends Event>, List<EcsEventResolver>> byType = new HashMap<>();

    /**
     * 注册解析器类（实例化一次并缓存，无状态复用）。
     *
     * @param name 注册名
     * @param type 解析器类（公共无参构造）
     * @throws EcsConfigException 重名或实例化失败
     */
    public synchronized void register(String name, Class<? extends EcsEventResolver> type) {
        if (name == null || name.isBlank()) {
            throw new EcsConfigException("解析器名不得为空");
        }
        if (byName.containsKey(name)) {
            throw new EcsConfigException("解析器名重复注册: '" + name + "'（已有 "
                    + byName.get(name).getClass().getName() + "）");
        }
        ComponentRegistry.requireNoArgConstructor(type);
        EcsEventResolver resolver;
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.trySetAccessible();
            resolver = constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new EcsConfigException("解析器实例化失败: " + type.getName(), e);
        }
        byName.put(name, resolver);
        byType.computeIfAbsent(resolver.eventType(), k -> new ArrayList<>()).add(resolver);
    }

    /**
     * 取适用于「实际触发事件类型」的解析器（宽匹配：解析器声明类型是触发类型的父类或相同）。
     *
     * @param firedType 实际触发的事件类型
     * @return 解析器列表（不可变；无则空）
     */
    public synchronized List<EcsEventResolver> resolversFor(Class<? extends Event> firedType) {
        List<EcsEventResolver> result = new ArrayList<>();
        for (Map.Entry<Class<? extends Event>, List<EcsEventResolver>> entry : byType.entrySet()) {
            if (entry.getKey().isAssignableFrom(firedType)) {
                result.addAll(entry.getValue());
            }
        }
        return List.copyOf(result);
    }

    /**
     * 已注册的全部解析器声明事件类型（事件桥计算需要监听的类型用）。
     *
     * @return 事件类型集合快照
     */
    public synchronized List<Class<? extends Event>> declaredEventTypes() {
        return List.copyOf(byType.keySet());
    }

    /**
     * 已注册解析器快照。
     *
     * @return 注册名 → 实例
     */
    public synchronized Map<String, EcsEventResolver> all() {
        return Map.copyOf(byName);
    }
}
