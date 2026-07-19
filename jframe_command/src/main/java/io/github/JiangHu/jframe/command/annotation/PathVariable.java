package io.github.JiangHu.jframe.command.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 路径变量绑定注解（参数级别）— 对应 Spring MVC 的 {@code @PathVariable}。
 * <p>
 * 标注在方法参数上，声明该参数从命令路径模式中的<b>路径变量</b>取值。
 * 路径变量以 {@code {name}} 形式出现在 {@link CommandMapping @CommandMapping} 的路径中，
 * 匹配时会把对应位置的命令 token 捕获并绑定到该参数。
 *
 * <h3>路径变量语法</h3>
 * <ul>
 *   <li>{@code {name}} — 捕获<b>单个</b> token，绑定到名为 {@code name} 的变量</li>
 *   <li>{@code {*rest}} — <b>贪婪</b>捕获，吃掉剩余所有 token（绑定到 {@code String[]} 或 {@code String}）</li>
 * </ul>
 * <p>
 * 同时兼容 {@code #{name}} 写法（等价于 {@code {name}}）。
 *
 * <h3>匹配与特异性</h3>
 * <p>
 * 当多条模式都能匹配同一输入时，框架按<b>特异性</b>选择最优匹配（类似 Spring 的
 * {@code RequestMappingHandlerMapping}）：
 * <ol>
 *   <li>静态段越多越优先（{@code home set} 优于 {@code home {action}}）</li>
 *   <li>模式越长越优先（捕获更多 token）</li>
 *   <li>变量越少越优先</li>
 *   <li>贪婪段最后</li>
 * </ol>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @CommandController("home")
 * public class HomeController {
 *
 *     // /home set tower   →  路径 "home set {name}"，name="tower"
 *     @CommandMapping("set {name}")
 *     public void onSet(@Sender Player player, @PathVariable("name") String name) {
 *         player.sendMessage("设置家: " + name);
 *     }
 *
 *     // /home tp tower 3  →  name="tower", count="3"（自动转 int）
 *     @CommandMapping("tp {name} {count}")
 *     public void onTp(@Sender Player player,
 *                      @PathVariable("name") String name,
 *                      @PathVariable("count") int count) {
 *         player.sendMessage("传送到 " + name + " 第 " + count + " 层");
 *     }
 *
 *     // /broadcast hello world foo  →  msg=[hello, world, foo]（贪婪）
 *     @CommandMapping("broadcast {*msg}")
 *     public void onBroadcast(@Sender CommandSender sender, @PathVariable("msg") String[] msg) {
 *         sender.sendMessage(String.join(" ", msg));
 *     }
 * }
 * }</pre>
 *
 * <h3>自动绑定（便捷）</h3>
 * <p>
 * 若方法参数<b>无任何注解</b>，但其名称（需 {@code -parameters} 编译选项）与某路径变量同名，
 * 框架会自动将其绑定为该路径变量。未启用 {@code -parameters} 时回退为位置参数，因此
 * 重要参数建议显式使用 {@code @PathVariable}。
 *
 * @see CommandMapping
 * @see CommandParam
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface PathVariable {

    /**
     * 路径变量名称。
     * <p>
     * 必须与 {@link CommandMapping @CommandMapping} 路径中 {@code {name}} 的 {@code name} 一致。
     * 为空时回退到编译参数名（需 {@code -parameters}）。
     *
     * @return 路径变量名称
     */
    String value() default "";
}
