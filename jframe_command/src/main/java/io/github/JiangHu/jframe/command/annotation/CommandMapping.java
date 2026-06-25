package io.github.JiangHu.jframe.command.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 命令映射注解（方法级别）— 类似 Spring MVC 的 {@code @RequestMapping}。
 * <p>
 * 声明一个方法处理哪条命令。命令路径以空格分段，第一段为<b>根命令</b>，
 * 后续段为<b>子命令</b>。框架按「最长前缀匹配」将命令文本路由到对应方法，
 * 匹配路径之后剩余的参数段会自动绑定到方法参数。
 *
 * <h3>路径匹配示例</h3>
 * <pre>
 *   注册:  @CommandMapping("home set")
 *   输入:  /home set myhouse
 *          └─路径 "home set" 匹配，剩余 ["myhouse"] 作为参数绑定
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @CommandController("home")
 * public class HomeController {
 *
 *     // 完整路径 "home set"，需要一个命名参数 name
 *     @CommandMapping(value = "set", desc = "设置家", permission = "home.set")
 *     public void onSet(@Sender Player player, @CommandParam("name") String name) {
 *         player.sendMessage("已设置家: " + name);
 *     }
 *
 *     // 完整路径 "home list"，无参数
 *     @CommandMapping("list")
 *     public void onList(@Sender CommandSender sender) {
 *         sender.sendMessage("家列表: ...");
 *     }
 *
 *     // 完整路径 "home"，直接处理根命令（方法路径为空）
 *     @CommandMapping("")
 *     public void onHome(@Sender Player player) {
 *         player.sendMessage("用法: /home <set|del|list>");
 *     }
 * }
 * }</pre>
 *
 * <h3>方法签名约定</h3>
 * <ul>
 *   <li>返回值：任意类型皆可（{@code void} 最常见），非 void 返回值当前被忽略</li>
 *   <li>参数：通过 {@link Sender}、{@link CommandParam}、{@link RawArgs} 绑定，
 *       或使用无注解参数按位置绑定（支持自动类型转换）</li>
 * </ul>
 *
 * @see CommandController
 * @see Sender
 * @see CommandParam
 * @see RawArgs
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CommandMapping {

    /**
     * 命令路径（相对类级基础路径）。
     * <p>
     * 以空格分段。若类级 {@link CommandController @CommandController} 声明了基础路径，
     * 则完整命令路径 = 基础路径 + 本路径；否则本路径即完整路径。
     * <p>
     * 空字符串表示方法直接处理类级基础路径本身（根命令）。
     *
     * @return 命令路径
     */
    String value() default "";

    /**
     * 命令描述。
     * <p>
     * 用于注册到 Nukkit 命令时的描述信息，以及帮助提示。
     *
     * @return 描述，默认空
     */
    String desc() default "";

    /**
     * 命令用法提示。
     * <p>
     * 当参数绑定失败（如必需参数缺失）时，会向发送者展示此用法。
     * 为空时框架自动生成默认用法。
     *
     * @return 用法提示，默认空
     */
    String usage() default "";

    /**
     * 所需权限。
     * <p>
     * 执行前会校验发送者是否拥有此权限。为空表示无权限限制。
     * 同时也会注册到 Nukkit 命令的 permission 字段（客户端侧权限提示）。
     *
     * @return 权限节点，默认空
     */
    String permission() default "";

    /**
     * 命令别名。
     * <p>
     * 仅对<b>根命令</b>生效（即路径第一段）。为根命令注册额外的触发名称。
     *
     * @return 别名数组，默认空
     */
    String[] aliases() default {};
}
