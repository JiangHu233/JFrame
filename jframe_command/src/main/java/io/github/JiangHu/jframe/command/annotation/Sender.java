package io.github.JiangHu.jframe.command.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 命令发送者绑定注解（参数级别）。
 * <p>
 * 标注在方法参数上，声明该参数注入命令的<b>发送者</b>（{@link cn.nukkit.command.CommandSender}）。
 * 类似 Spring MVC 中注入 {@code Principal} 或 {@code HttpServletRequest} 的用法。
 *
 * <h3>类型自动适配</h3>
 * <p>
 * 框架会根据参数声明类型智能注入：
 * <ul>
 *   <li>声明为 {@link cn.nukkit.command.CommandSender}：直接注入（任何发送者）</li>
 *   <li>声明为 {@link cn.nukkit.Player}：仅当发送者是玩家时注入，否则执行失败
 *       （可用于强制「仅玩家可用」约束）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @CommandMapping("spawn")
 * public void onSpawn(@Sender Player player) {        // 强制玩家：控制台执行会失败
 *     player.teleport(player.getLevel().getSpawnLocation());
 * }
 *
 * @CommandMapping("say")
 * public void onSay(@Sender CommandSender sender, String msg) {  // 控制台/玩家均可
 *     sender.sendMessage("你说: " + msg);
 * }
 * }</pre>
 *
 * @see CommandMapping
 * @see cn.nukkit.command.CommandSender
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Sender {
}
