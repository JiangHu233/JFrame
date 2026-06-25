package io.github.JiangHu.jframe.command.annotation;

import io.github.JiangHu.jframe.command.CommandAPI;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 命令控制器声明注解（类级别）— 类似 Spring MVC 的 {@code @Controller}。
 * <p>
 * 标记一个类为<b>命令控制器</b>，使其可被
 * {@link io.github.JiangHu.jframe.command.scan.CommandScanner} 自动发现并注册。
 * 类中被 {@link CommandMapping @CommandMapping} 标注的方法会被解析为命令处理器。
 *
 * <h3>与 Spring MVC 的类比</h3>
 * <pre>
 *   Spring MVC:  @Controller  +  @RequestMapping   →  HTTP 请求路由
 *   JFrame:      @CommandController + @CommandMapping → 命令文本路由
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @CommandController("home")   // 基础路径 "home"
 * public class HomeController {
 *
 *     // 完整命令路径 = 基础路径 + 方法路径 = "home set"
 *     @CommandMapping("set")
 *     public void onSet(@Sender Player player, @CommandParam("name") String name) { ... }
 *
 *     @CommandMapping("del")
 *     public void onDel(@Sender Player player, String name) { ... }
 * }
 *
 * // 在插件主类中：扫描整个 controller 包，自动注册所有 @CommandController 类
 * commandService.scan("com.myplugin.command");
 * }</pre>
 *
 * <h3>基础路径拼接规则</h3>
 * <ul>
 *   <li>类级 {@code @CommandController("home")} + 方法级 {@code @CommandMapping("set")}
 *       → 完整命令路径 {@code "home set"}</li>
 *   <li>类级 {@code @CommandController("home")} + 方法级 {@code @CommandMapping("")}
 *       → 完整命令路径 {@code "home"}（方法直接处理根命令）</li>
 *   <li>方法级路径也可写完整：{@code @CommandMapping("home set")}，此时忽略类级基础路径</li>
 * </ul>
 * <p>
 * 完整路径以空格分段，第一段为<b>根命令</b>（需注册到 Nukkit），后续段为<b>子命令</b>。
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>{@code @CommandController} 仅作为<b>发现标记 + 基础路径声明</b></li>
 *   <li>被标记的类需有无参构造函数（或仅依赖可注入类型，见 {@link CommandMapping}）</li>
 *   <li>接口、抽象类、注解、枚举即使标记了本注解也会被扫描器跳过</li>
 * </ul>
 *
 * @see CommandMapping
 * @see io.github.JiangHu.jframe.command.scan.CommandScanner
 * @see CommandAPI#scan
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CommandController {

    /**
     * 基础命令路径（可选）。
     * <p>
     * 类似 Spring MVC {@code @RequestMapping} 在类级别的路径前缀。
     * 方法级 {@link CommandMapping @CommandMapping} 的路径会拼接到此基础路径之后。
     * <p>
     * 默认为空字符串，表示无基础路径（方法路径即完整路径）。
     *
     * @return 基础命令路径，默认空
     */
    String value() default "";
}
