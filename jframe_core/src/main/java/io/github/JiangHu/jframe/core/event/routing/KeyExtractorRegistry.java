package io.github.JiangHu.jframe.core.event.routing;

import cn.nukkit.event.Event;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 身份Key提取器注册表。
 * <p>
 * 维护"事件类型 → 提取器"的映射，并预置了 Nukkit 常见事件的提取器：
 * <ul>
 *   <li>{@code PlayerEvent} → {@code getPlayer()}</li>
 *   <li>{@code BlockEvent} → {@code getBlock()}</li>
 *   <li>{@code EntityEvent} → {@code getEntity()}</li>
 * </ul>
 * <p>
 * <b>类层次查找</b>：当具体事件（如 {@code PlayerMoveEvent}）没有直接注册的提取器时，
 * 会沿继承链向上查找（{@code PlayerMoveEvent} → {@code PlayerEvent}），找到第一个匹配的提取器。
 * 查找结果会被缓存，避免重复反射。
 *
 * <h3>自定义提取器</h3>
 * <pre>{@code
 * registry.register(MyCustomEvent.class, event -> event.getOwner());
 * }</pre>
 *
 * @see EventKeyExtractor
 */
@Component
public class KeyExtractorRegistry {

    /**
     * 直接映射表：事件类型 → 提取器（仅存储精确注册的类型）。
     */
    private final Map<Class<? extends Event>, EventKeyExtractor<?>> extractors = new ConcurrentHashMap<>();

    /**
     * 查找缓存：具体事件类 → 解析后的提取器（含继承链查找结果）。
     */
    private final Map<Class<? extends Event>, EventKeyExtractor<?>> cache = new ConcurrentHashMap<>();

    public KeyExtractorRegistry() {
        registerDefaults();
    }

    /**
     * 注册提取器。
     *
     * @param eventType 事件类型（通常是基类，如 {@code PlayerEvent.class}）
     * @param extractor 提取器
     * @param <T>       事件类型泛型
     */
    public <T extends Event> void register(Class<T> eventType, EventKeyExtractor<T> extractor) {
        extractors.put(eventType, extractor);
        cache.clear(); // 清除缓存，因为新的注册可能改变查找结果
    }

    /**
     * 查找指定事件类型的提取器。
     * <p>
     * 先查缓存，未命中则沿继承链查找，找到后缓存结果。
     *
     * @param eventClass 具体事件类型
     * @return 提取器，或 {@code null} 表示该事件类型无身份Key（全局事件）
     */
    @SuppressWarnings("unchecked")
    public EventKeyExtractor<Event> findExtractor(Class<? extends Event> eventClass) {
        return (EventKeyExtractor<Event>) cache.computeIfAbsent(eventClass, this::resolveExtractor);
    }

    /**
     * 沿继承链查找提取器。
     */
    private EventKeyExtractor<?> resolveExtractor(Class<?> eventClass) {
        Class<?> current = eventClass;
        while (current != null && Event.class.isAssignableFrom(current)) {
            EventKeyExtractor<?> extractor = extractors.get(current);
            if (extractor != null) {
                return extractor;
            }
            current = current.getSuperclass();
        }
        return null; // 无提取器，视为全局事件
    }

    /**
     * 预置 Nukkit 常见事件的提取器。
     * <p>
     * 使用反射调用 getter 方法，兼容不同 Nukkit 版本。
     * 如果类不存在（极少见），则跳过该提取器。
     */
    @SuppressWarnings("unchecked")
    private void registerDefaults() {
        // PlayerEvent → getPlayer()
        registerByReflection("cn.nukkit.event.player.PlayerEvent", "getPlayer");

        // BlockEvent → getBlock()
        registerByReflection("cn.nukkit.event.block.BlockEvent", "getBlock");

        // EntityEvent → getEntity()
        registerByReflection("cn.nukkit.event.entity.EntityEvent", "getEntity");

        // InventoryEvent → getInventory()（可选）
        registerByReflection("cn.nukkit.event.inventory.InventoryEvent", "getInventory");
    }

    /**
     * 通过反射注册提取器。
     * <p>
     * 查找指定类中名为 {@code methodName} 的无参方法，创建提取器。
     * 如果类或方法不存在，则静默跳过。
     *
     * @param className  Nukkit 事件基类的全限定名
     * @param methodName getter 方法名
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
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
