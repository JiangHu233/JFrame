package io.github.JiangHu.jframe.command.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 原始参数数组绑定注解（参数级别）。
 * <p>
 * 标注在方法参数上，声明该参数注入命令路径匹配后剩余的<b>全部原始参数</b>
 * （{@code String[]}，未做命名/位置拆分）。
 * <p>
 * 适用于需要自行解析参数、或仅需透传参数的场景。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @CommandMapping("broadcast")
 * // /broadcast hello world  →  rawArgs = ["hello", "world"]
 * public void onBroadcast(@Sender CommandSender sender, @RawArgs String[] rawArgs) {
 *     String msg = String.join(" ", rawArgs);
 *     Server.getInstance().broadcastMessage(msg);
 * }
 * }</pre>
 *
 * <h3>注意</h3>
 * <ul>
 *   <li>注入的是<b>路径匹配之后</b>的剩余参数，不含已用于路由匹配的子命令段</li>
 *   <li>参数类型必须为 {@code String[]}，否则注册时报错</li>
 * </ul>
 *
 * @see CommandMapping
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface RawArgs {
}
