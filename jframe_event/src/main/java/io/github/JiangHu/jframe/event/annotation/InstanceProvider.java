package io.github.JiangHu.jframe.event.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 实例工厂方法标记注解。
 * <p>
 * 标记一个 <b>static 方法</b>为实例工厂。框架在分发事件时，
 * 从事件中提取身份后调用此方法，获取或创建对应的包装类实例。
 *
 * <h3>规则</h3>
 * <ul>
 *   <li><b>必须为 static 方法</b></li>
 *   <li>方法签名：{@code static WrapperClass getOrCreate(IdentityType identity)}</li>
 *   <li>参数类型 = 身份标识类型（与 {@link KeyExtractor} 返回类型一致）</li>
 *   <li>返回值 = 包装类实例（或子类实例）</li>
 *   <li>返回 {@code null} = 无对应实例，跳过此事件</li>
 *   <li><b>可选</b>：无此注解时框架使用默认缓存（构造函数 + ConcurrentHashMap）</li>
 * </ul>
 *
 * <h3>三种实例策略</h3>
 *
 * <h4>策略1：默认缓存（无 @InstanceProvider）</h4>
 * <pre>{@code
 * public class PlayerWrapper {
 *     public PlayerWrapper(Player player) { ... }
 *     // 无 @InstanceProvider → 框架用构造函数创建，缓存到内部 Map
 * }
 * }</pre>
 *
 * <h4>策略2：对接外部缓存容器</h4>
 * <pre>{@code
 * public class PlayerWrapper {
 *     @InstanceProvider
 *     public static PlayerWrapper getOrCreate(Player player) {
 *         return ExternalPlayerManager.getWrapper(player);
 *     }
 * }</pre>
 *
 * <h4>策略3：一次性实例（处理完丢弃）</h4>
 * <pre>{@code
 * public class OneTimeHandler {
 *     @InstanceProvider
 *     public static OneTimeHandler create(Player player) {
 *         return new OneTimeHandler(player);  // 每次新建，GC 自动回收
 *     }
 * }
 * }</pre>
 *
 * @see KeyExtractor
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface InstanceProvider {
}
