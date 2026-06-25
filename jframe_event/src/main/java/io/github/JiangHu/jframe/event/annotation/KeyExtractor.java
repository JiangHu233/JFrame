package io.github.JiangHu.jframe.event.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.github.JiangHu.jframe.event.annotation.InstanceProvider;

/**
 * 身份提取方法标记注解。
 * <p>
 * 标记一个 <b>static 方法</b>为身份提取器。框架在分发事件时调用此方法，
 * 从事件中提取身份标识，用于路由到正确的包装类实例。
 *
 * <h3>规则</h3>
 * <ul>
 *   <li><b>必须为 static 方法</b>（提取身份时实例尚未创建）</li>
 *   <li>方法签名：{@code static IdentityType extract(EventType event)}</li>
 *   <li>返回值 = 身份标识（与 {@link InstanceProvider} 参数类型一致）</li>
 *   <li>返回 {@code null} = 该事件不包含此槽位，跳过此提取器</li>
 *   <li>无优先级、无条件 — 纯粹的身份提取，总是执行</li>
 * </ul>
 *
 * <h3>同一事件多槽位</h3>
 * <p>
 * 一个事件可能包含多个同类型对象（如 {@code EntityDamageByEntityEvent} 同时有攻击者和受害者）。
 * 可以为同一事件类型声明多个 {@code @KeyExtractor}，框架逐一尝试，任一匹配即路由（OR 语义）。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * public class PlayerWrapper {
 *     private final Player player;
 *
 *     public PlayerWrapper(Player player) { this.player = player; }
 *
 *     // 从事件提取 Player 身份
 *     @KeyExtractor
 *     public static Player extract(PlayerMoveEvent event) {
 *         return event.getPlayer();
 *     }
 *
 *     @EventRoute @EventHandler
 *     public void onMove(PlayerMoveEvent event) { ... }
 * }
 *
 * // 多槽位示例：攻击者或受害者
 * public class CombatantWrapper {
 *     @KeyExtractor  // 槽位1：作为攻击者
 *     public static Entity asDamager(EntityDamageByEntityEvent event) {
 *         return event.getDamager();
 *     }
 *
 *     @KeyExtractor  // 槽位2：作为受害者
 *     public static Entity asVictim(EntityDamageByEntityEvent event) {
 *         return event.getEntity();
 *     }
 *
 *     @EventRoute @EventHandler
 *     public void onCombat(EntityDamageByEntityEvent event) { ... }
 * }
 * }</pre>
 *
 * @see InstanceProvider
 * @see EventRoute
 * @see EventHandler
 */

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface KeyExtractor {
}
