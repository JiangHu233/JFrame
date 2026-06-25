package io.github.JiangHu.jframe.core.event.routing;

import cn.nukkit.event.Event;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 路由规格：为包装类指定<b>自定义 Key 提取器</b>，覆盖全局提取器。
 * <p>
 * 默认情况下，{@link ObjectEventRouter} 使用 {@link KeyExtractorRegistry} 中的全局提取器
 * 来提取事件的"身份 Key"。例如 {@code PlayerInteractEvent} 的默认 Key 是 {@code Player} 对象。
 * <p>
 * 但有些场景需要用<b>不同的维度</b>来路由：
 * <ul>
 *   <li>一把特定的剑 → 按 {@code Item} 路由，而不是按 {@code Player}</li>
 *   <li>一个坐标区域 → 按 {@code 区域ID} 路由，而不是按 {@code Entity}</li>
 *   <li>一个箱子的某个格子 → 按 {@code Inventory} 路由</li>
 * </ul>
 * {@code RoutingSpec} 允许为每个事件类型指定自定义提取器，注册时传入即可。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 场景：一把魔法剑，按物品路由（而不是按玩家）
 * MagicSwordWrapper swordWrapper = new MagicSwordWrapper(swordItem);
 *
 * RoutingSpec spec = RoutingSpec.create()
 *     .extract(PlayerInteractEvent.class, PlayerInteractEvent::getItem);  // 按物品提取Key
 *
 * router.register(swordWrapper, swordItem, spec);
 * //                         ^^^^^^^^  注册时传入的 Key 必须和提取器返回的 Key 匹配
 * }</pre>
 *
 * <h3>多维度混合</h3>
 * 一个 wrapper 可以同时有"全局提取器事件"和"自定义提取器事件"：
 * <pre>{@code
 * RoutingSpec spec = RoutingSpec.create()
 *     .extract(PlayerInteractEvent.class, PlayerInteractEvent::getItem);
 *     // 只覆盖 PlayerInteractEvent，其他事件类型仍用全局提取器
 *
 * router.register(wrapper, someKey, spec);
 * // wrapper 中 @NukkitEvent(PlayerInteractEvent.class) → 用自定义提取器
 * // wrapper 中 @NukkitEvent(PlayerQuitEvent.class)    → 用全局提取器（Player）
 * }</pre>
 *
 * <h3>区域路由示例</h3>
 * <pre>{@code
 * // 场景：一个 PvP 区域，按区域ID路由
 * RegionWrapper regionWrapper = new RegionWrapper("spawn_pvp");
 *
 * RoutingSpec spec = RoutingSpec.create()
 *     .extract(EntityDamageByEntityEvent.class, event -> {
 *         Position pos = event.getEntity().getPosition();
 *         return isInSpawnRegion(pos) ? "spawn_pvp" : null;  // 在区域内返回区域ID
 *     });
 *
 * router.register(regionWrapper, "spawn_pvp", spec);
 * }</pre>
 *
 * @see ObjectEventRouter#register(Object, Object, RoutingSpec)
 * @see EventKeyExtractor
 */
public class RoutingSpec {

    private final Map<Class<? extends Event>, EventKeyExtractor<?>> extractors = new HashMap<>();

    private RoutingSpec() {
    }

    /**
     * 创建一个空的 RoutingSpec。
     *
     * @return 新的 RoutingSpec 实例
     */
    public static RoutingSpec create() {
        return new RoutingSpec();
    }

    /**
     * 为指定事件类型设置自定义 Key 提取器。
     * <p>
     * 注册时，该事件类型的处理器将使用此提取器（而非全局提取器）来提取 Key。
     *
     * @param eventType 事件类型
     * @param extractor 自定义提取器
     * @param <T>       事件泛型
     * @return this（链式调用）
     */
    public <T extends Event> RoutingSpec extract(Class<T> eventType, EventKeyExtractor<T> extractor) {
        extractors.put(eventType, extractor);
        return this;
    }

    /**
     * 获取指定事件类型的自定义提取器。
     *
     * @param eventType 事件类型
     * @return 自定义提取器，或 {@code null} 表示该类型使用全局提取器
     */
    public EventKeyExtractor<?> getExtractor(Class<? extends Event> eventType) {
        return extractors.get(eventType);
    }

    /**
     * 是否包含任何自定义提取器。
     *
     * @return 如果没有任何自定义提取器，返回 {@code false}
     */
    public boolean isEmpty() {
        return extractors.isEmpty();
    }

    /**
     * 返回不可修改的提取器映射（内部使用）。
     *
     * @return 事件类型 → 提取器 的映射
     */
    Map<Class<? extends Event>, EventKeyExtractor<?>> getExtractors() {
        return Collections.unmodifiableMap(extractors);
    }
}
