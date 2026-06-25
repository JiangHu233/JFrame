package io.github.JiangHu.jframe.event.routing;

import cn.nukkit.event.Event;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局身份提取器注册表。
 * <p>
 * 维护"事件基类 → 提取器"映射，预置 Nukkit 常见事件的提取器：
 * <ul>
 *   <li>{@code PlayerEvent} → {@code getPlayer()}</li>
 *   <li>{@code BlockEvent} → {@code getBlock()}</li>
 *   <li>{@code EntityEvent} → {@code getEntity()}</li>
 *   <li>{@code InventoryEvent} → {@code getInventory()}</li>
 * </ul>
 * <p>
 * 当具体事件（如 {@code PlayerMoveEvent}）没有直接注册的提取器时，
 * 沿继承链向上查找（{@code PlayerMoveEvent} → {@code PlayerEvent}），找到第一个匹配的提取器。
 *
 * <h3>NO_EXTRACTOR 哨兵</h3>
 * 对于确实没有提取器的事件类型，使用 {@link #NO_EXTRACTOR} 哨兵缓存 null 结果，
 * 避免每次事件触发都重新遍历继承链。
 *
 * @see HandlerRegistry
 */
public class KeyExtractorRegistry {

    /**
     * 身份提取器函数式接口。
     */
    @FunctionalInterface
    public interface Extractor {
        /**
         * 从事件中提取身份标识。
         *
         * @param event 事件对象
         * @return 身份标识，或 null 表示该事件无身份
         */
        Object extract(Event event);
    }

    /** 哨兵：表示"已查找过，确认无提取器"，避免重复遍历继承链 */
    private static final Extractor NO_EXTRACTOR = event -> null;

    /** 直接映射表：事件类型 → 提取器（仅存储精确注册的类型） */
    private final Map<Class<? extends Event>, Extractor> extractors = new ConcurrentHashMap<>();

    /** 查找缓存：具体事件类 → 解析后的提取器（含继承链查找结果） */
    private final Map<Class<? extends Event>, Extractor> cache = new ConcurrentHashMap<>();

    public KeyExtractorRegistry() {
        registerDefaults();
    }

    /**
     * 注册全局提取器。
     *
     * @param eventType 事件类型（通常是基类，如 {@code PlayerEvent.class}）
     * @param extractor 提取器
     */
    public void register(Class<? extends Event> eventType, Extractor extractor) {
        extractors.put(eventType, extractor);
        cache.clear();
    }

    /**
     * 查找指定事件类型的提取器。
     * <p>
     * 先查缓存，未命中则沿继承链查找，找到后缓存结果。
     * 无提取器时缓存 {@link #NO_EXTRACTOR} 哨兵。
     *
     * @param eventClass 具体事件类型
     * @return 提取器，或 null（表示该事件类型无提取器，且未缓存）
     */
    public Extractor findExtractor(Class<? extends Event> eventClass) {
        Extractor extractor = cache.computeIfAbsent(eventClass, this::resolveExtractor);
        return extractor == NO_EXTRACTOR ? null : extractor;
    }

    /**
     * 沿继承链查找提取器。
     */
    private Extractor resolveExtractor(Class<?> eventClass) {
        Class<?> current = eventClass;
        while (current != null && Event.class.isAssignableFrom(current)) {
            Extractor extractor = extractors.get(current);
            if (extractor != null) {
                return extractor;
            }
            current = current.getSuperclass();
        }
        return NO_EXTRACTOR;
    }

    /**
     * 预置 Nukkit 常见事件的提取器。
     * 使用反射兼容不同 Nukkit 版本，类不存在则静默跳过。
     */
    @SuppressWarnings("unchecked")
    private void registerDefaults() {
        registerByReflection("cn.nukkit.event.player.PlayerEvent", "getPlayer");
        registerByReflection("cn.nukkit.event.block.BlockEvent", "getBlock");
        registerByReflection("cn.nukkit.event.entity.EntityEvent", "getEntity");
        registerByReflection("cn.nukkit.event.inventory.InventoryEvent", "getInventory");
    }

    /**
     * 通过反射注册提取器。
     */
    @SuppressWarnings("unchecked")
    private void registerByReflection(String className, String methodName) {
        try {
            Class<?> clazz = Class.forName(className);
            java.lang.reflect.Method getter = clazz.getMethod(methodName);
            Class<? extends Event> eventClass = (Class<? extends Event>) clazz;

            extractors.put(eventClass, event -> {
                try {
                    return getter.invoke(event);
                } catch (Exception e) {
                    return null;
                }
            });
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            // 该 Nukkit 版本中不存在此类/方法，跳过
        }
    }
}
