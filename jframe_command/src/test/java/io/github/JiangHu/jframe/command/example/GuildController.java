package io.github.JiangHu.jframe.command.example;

import cn.nukkit.command.CommandSender;
import io.github.JiangHu.jframe.command.annotation.CommandController;
import io.github.JiangHu.jframe.command.annotation.CommandMapping;
import io.github.JiangHu.jframe.command.annotation.CommandParam;
import io.github.JiangHu.jframe.command.annotation.PathVariable;
import io.github.JiangHu.jframe.command.annotation.Sender;

/**
 * 示例控制器①：公会命令（根命令 {@code guild}）。
 * <p>
 * 演示以下能力（与 Spring MVC 用法一一对应）：
 * <ul>
 *   <li>{@link PathVariable @PathVariable} 单值捕获：{@code create {name}}</li>
 *   <li>{@link PathVariable @PathVariable} 贪婪捕获 {@code {*msg}}：{@code broadcast {*msg}}</li>
 *   <li>兼容 {@code #{id}} 写法：{@code lookup #{id}}</li>
 *   <li>{@link CommandParam @CommandParam} 命名参数 + 默认值：{@code kick {target} --reason}</li>
 *   <li>无注解位置参数 + 自动类型转换：{@code sethome {name} 10 20 30}</li>
 *   <li>{@link Sender @Sender} 注入发送者</li>
 * </ul>
 *
 * <p>为便于在无 Nukkit 服务器的单元测试中断言，各方法把处理结果写入静态字段，
 * 由 {@code CommandLogicTest} 读取校验。生产代码中应直接操作业务对象。
 */
@CommandController("guild")
public class GuildController {

    public static String lastCreateName;
    public static String lastBroadcast;
    public static String lastLookup;
    public static String lastKick;
    public static String lastHome;

    /** /guild create 我的公会  →  name=我的公会 */
    @CommandMapping(value = "create {name}", desc = "创建公会")
    public void create(@Sender CommandSender sender, @PathVariable("name") String name) {
        lastCreateName = name;
        sender.sendMessage("§a已创建公会: " + name);
    }

    /** /guild broadcast hello world foo  →  msg=[hello, world, foo]（贪婪捕获） */
    @CommandMapping("broadcast {*msg}")
    public void broadcast(@Sender CommandSender sender, @PathVariable("msg") String[] msg) {
        lastBroadcast = String.join(" ", msg);
        sender.sendMessage("§b" + lastBroadcast);
    }

    /** /guild lookup 42  →  id=42（#{id} 兼容写法，等价于 {id}） */
    @CommandMapping("lookup #{id}")
    public void lookup(@Sender CommandSender sender, @PathVariable("id") String id) {
        lastLookup = id;
        sender.sendMessage("§e查询到编号: " + id);
    }

    /** /guild kick Steve --reason 违规   或   /guild kick Steve（使用默认值） */
    @CommandMapping("kick {target}")
    public void kick(@Sender CommandSender sender,
                     @PathVariable("target") String target,
                     @CommandParam(value = "reason", defaultValue = "无") String reason) {
        lastKick = target + ":" + reason;
        sender.sendMessage("§c踢出 " + target + "，原因: " + reason);
    }

    /** /guild sethome tower 10 20 30  →  name=tower, x=10, y=20, z=30（位置参数自动转 int） */
    @CommandMapping("sethome {name}")
    public void sethome(@PathVariable("name") String name, int x, int y, int z) {
        lastHome = name + "@(" + x + "," + y + "," + z + ")";
    }
}
