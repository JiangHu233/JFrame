package io.github.JiangHu.jframe.core.event.annotation;

import cn.nukkit.event.Event;
import cn.nukkit.event.EventPriority;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Nukkit 事件处理注解（语法糖）。
 * <p>
 * 标注在包装类或 Spring Bean 的方法上，声明该方法处理指定的 Nukkit 事件。
 * 框架会自动扫描此注解，将方法注册为事件处理器。
 * <p>
 * <b>两种使用模式：</b>
 * <ul>
 *   <li><b>全局处理器</b>（Spring 单例 Bean）：{@code EventBeanPostProcessor} 自动扫描注册，
 *       接收该事件类型的<b>所有</b>实例</li>
 *   <li><b>对象级处理器</b>（动态包装类实例）：通过 {@code ObjectEventRouter.register(wrapper, key)}
 *       手动注册，只接收与绑定 Key 匹配的事件</li>
 * </ul>
 *
 * <h3>事件类型自动推断</h3>
 * 如果不填 {@link #value()}，框架会自动从方法的<b>第一个参数类型</b>推断事件类型：
 * <pre>{@code
 * // 以下两种写法等价：
 * @NukkitEvent(PlayerChatEvent.class)
 * public void onChat(PlayerChatEvent event) { ... }
 *
 * @NukkitEvent                          // ← 不填，自动推断为 PlayerChatEvent
 * public void onChat(PlayerChatEvent event) { ... }
 * }</pre>
 *
 * <h3>条件过滤（三种方式）</h3>
 * <ol>
 *   <li><b>SpEL 表达式</b>（{@link #condition()}）：适合简单条件
 *       <pre>{@code condition = "#event.player.gamemode == 1"}</pre></li>
 *   <li><b>SpEL 调用目标方法</b>（condition 中用 {@code #target} 引用当前对象）：适合中等复杂度
 *       <pre>{@code condition = "#target.shouldHandle(#event)"}</pre></li>
 *   <li><b>筛选方法引用</b>（{@link #filter()}）：直接指定方法名，最简洁
 *       <pre>{@code filter = "shouldHandle"}</pre></li>
 * </ol>
 *
 * <h3>示例</h3>
 * <pre>{@code
 * // 全局处理器（Spring Bean）—— 自动推断事件类型
 * @Component
 * public class ChatLogger {
 *     @NukkitEvent
 *     public void onChat(PlayerChatEvent event) {
 *         System.out.println(event.getMessage());
 *     }
 * }
 *
 * // 对象级处理器（包装类）—— 使用 filter 方法
 * public class PlayerWrapper {
 *     @NukkitEvent
 *     public void onMove(PlayerMoveEvent event) { ... }
 *
 *     @NukkitEvent(filter = "isRightClickBlock")
 *     public void onRightClick(PlayerInteractEvent event) { ... }
 *
 *     // 筛选方法：返回 true 才执行上面的 onRightClick
 *     private boolean isRightClickBlock(PlayerInteractEvent event) {
 *         return event.getAction().name().equals("RIGHT_CLICK_BLOCK")
 *             && event.getPlayer().getGamemode() == 1;
 *     }
 * }
 * }</pre>
 *
 * @see io.github.JiangHu.jframe.core.event.routing.ObjectEventRouter
 * @see io.github.JiangHu.jframe.core.event.spring.EventBeanPostProcessor
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NukkitEvent {

    /**
     * 要处理的事件类型，可选。
     * <p>
     * 如果填写，框架会按此类型向 Nukkit 注册。
     * 如果<b>不填</b>（默认 {@link Event}），框架会自动从方法的第一个参数类型推断。
     * <p>
     * 方法必须至少有一个 {@link Event} 子类类型的参数。
     *
     * @return 事件类型，默认 {@link Event} 表示自动推断
     */
    Class<? extends Event> value() default Event.class;

    /**
     * SpEL（Spring Expression Language）过滤条件，可选。
     * <p>
     * 当条件表达式求值结果为 {@code true} 时才调用处理方法。
     * 表达式中可用的变量：
     * <ul>
     *   <li>{@code #event}：事件对象本身</li>
     *   <li>{@code #target}：当前处理器对象（Spring Bean 或包装类实例），
     *       可调用其任意方法</li>
     * </ul>
     * 示例：
     * <pre>{@code
     * // 简单条件
     * condition = "#event.player.gamemode == 1"
     *
     * // 调用当前对象的筛选方法（复杂逻辑写在 Java 方法里）
     * condition = "#target.shouldHandle(#event)"
     * }</pre>
     * <p>
     * <b>注意</b>：{@code condition} 和 {@link #filter()} 不能同时使用。
     * 如果需要复杂判断逻辑，推荐使用 {@link #filter()} 更简洁。
     * <p>
     * 表达式在注册时解析一次并缓存，运行时反复求值，性能开销极小。
     *
     * @return SpEL 条件表达式，默认空字符串表示无条件
     */
    String condition() default "";

    /**
     * 筛选方法名，可选。
     * <p>
     * 指定当前类中一个返回 {@code boolean} 的方法名，该方法接收同类型的事件参数。
     * 只有当筛选方法返回 {@code true} 时，才会调用处理方法。
     * <p>
     * 相比 {@link #condition()} 的 SpEL 表达式，{@code filter} 直接调用 Java 方法，
     * 可以编写任意复杂的判断逻辑（多条件组合、调用外部服务等），且享有 IDE 的代码补全和编译检查。
     * <p>
     * 筛选方法的要求：
     * <ul>
     *   <li>与处理方法在同一个类中（含父类）</li>
     *   <li>接收一个与事件类型兼容的参数</li>
     *   <li>返回 {@code boolean}</li>
     * </ul>
     * 示例：
     * <pre>{@code
     * @NukkitEvent(filter = "isCreativeAndSurvivalWorld")
     * public void onMove(PlayerMoveEvent event) { ... }
     *
     * private boolean isCreativeAndSurvivalWorld(PlayerMoveEvent event) {
     *     return event.getPlayer().getGamemode() == 1
     *         && event.getPlayer().getLevel().getName().equals("survival");
     * }
     * }</pre>
     *
     * @return 筛选方法名，默认空字符串表示无筛选
     */
    String filter() default "";

    /**
     * 事件优先级。
     * <p>
     * Nukkit 按优先级从低到高依次调用处理器：
     * {@link EventPriority#LOWEST} → ... → {@link EventPriority#MONITOR}。
     *
     * @return 优先级，默认 {@link EventPriority#NORMAL}
     */
    EventPriority priority() default EventPriority.NORMAL;
}
