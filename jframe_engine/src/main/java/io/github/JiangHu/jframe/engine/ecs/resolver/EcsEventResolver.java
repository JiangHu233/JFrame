package io.github.JiangHu.jframe.engine.ecs.resolver;

import cn.nukkit.event.Event;

import java.util.List;

/**
 * 宿主解析器 — 从任意 Nukkit 事件中提取"当事实体"候选（参考 listener 模块 KeyExtractor 多槽位思想）。
 *
 * <h3>职责</h3>
 * <p>
 * Nukkit 事件的当事对象不一定能 {@code getEntity()} 直接拿到（方块点击、容器持有者等
 * "歪门邪道对象"）。解析器负责从事件中提取宿主<b>候选</b>，引擎再统一换算并查绑定索引：
 * <ul>
 *   <li>{@code Entity} / {@code BlockEntity} — 直接命中绑定索引</li>
 *   <li>{@code Position} / {@code Block} — 引擎换算为该位置的方块实体后查索引</li>
 *   <li>{@code EcsEntity} — 直接采用（便于自定义解析器复用引擎实体）</li>
 * </ul>
 * 多槽位候选全部尝试（OR 语义）；返回空列表 = 该事件与本解析器无关。
 *
 * <h3>性能门控（不会跑在所有事件上）</h3>
 * <ul>
 *   <li>引擎只对「有订阅的事件类型」注册 Nukkit 监听——未订阅类型的事件与 ECS 零交集</li>
 *   <li>事件触发后仅<b>宽匹配命中</b>的解析器运行（声明 {@code InventoryOpenEvent} 的不会跑在
 *       {@code EntityDamageEvent} 上；参数为基类 {@code Event} 只是多态）</li>
 *   <li>{@code extractHosts} 契约为 O(1) getter，不做扫描</li>
 *   <li>返回的是"该收到此事件的<b>宿主候选</b>"（通常 0~1 个，多主体可多个），
 *       不是"事件里的全部对象"</li>
 * </ul>
 *
 * <h3>内置解析器</h3>
 * <ul>
 *   <li>{@code entity-events} — {@code EntityEvent.getEntity()}</li>
 *   <li>{@code block-interact} — 点击方块 → 位置 → 方块实体</li>
 * </ul>
 *
 * <h3>使用示例（库存持有者）</h3>
 * <pre>{@code
 * @EcsResolver("chest-holder")
 * public class ChestHolderResolver implements EcsEventResolver {
 *     @Override public Class<? extends Event> eventType() { return InventoryOpenEvent.class; }
 *
 *     @Override
 *     public List<Object> extractHosts(InventoryOpenEvent event) {
 *         return List.ofNullable(event.getInventory().getHolder());
 *     }
 * }
 * }</pre>
 *
 * @see io.github.JiangHu.jframe.engine.ecs.annotation.EcsResolver
 */
public interface EcsEventResolver {

    /**
     * 本解析器关注的事件类型。
     * <p>
     * 可声明为具体事件类型或其父类型（如 {@code EntityEvent.class}）；
     * 引擎按「解析器事件类型 isAssignableFrom 处理方法事件类型」做宽匹配。
     *
     * @return 事件类型
     */
    Class<? extends Event> eventType();

    /**
     * 从事件提取宿主候选（多槽位全试）。
     *
     * @param event 事件对象（实际类型可能是 {@link #eventType()} 的子类）
     * @return 候选列表（Entity/BlockEntity/Position/Block/EcsEntity）；空列表表示跳过
     */
    List<Object> extractHosts(Event event);
}
