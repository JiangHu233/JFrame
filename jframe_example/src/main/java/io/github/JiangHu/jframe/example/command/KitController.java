package io.github.JiangHu.jframe.example.command;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.command.CommandSender;
import cn.nukkit.item.Item;
import io.github.JiangHu.jframe.command.annotation.CommandController;
import io.github.JiangHu.jframe.command.annotation.CommandMapping;
import io.github.JiangHu.jframe.command.annotation.CommandParam;
import io.github.JiangHu.jframe.command.annotation.PathVariable;
import io.github.JiangHu.jframe.command.annotation.Sender;
import io.github.JiangHu.jframe.example.wrapper.ThunderSwordWrapper;

/**
 * 命令模块示例控制器（根命令 {@code /kit}）。
 * <p>
 * 本类演示如何用 <b>声明式注解</b>（类似 Spring MVC）替代经典 {@code onCommand} 里的
 * {@code if/else if} 分支与手动 {@code split/parseInt}。每条子命令是独立方法，
 * 路径与参数全部声明在注解里，框架自动完成路由匹配、参数解析、类型转换与权限校验。
 *
 * <h3>覆盖的能力点</h3>
 * <ul>
 *   <li>{@link CommandMapping @CommandMapping("")}：空路径匹配根命令本身（{@code /kit} 显示帮助）</li>
 *   <li>{@link PathVariable @PathVariable} 单值 + {@code int} 自动类型转换：{@code give {item} {count}}</li>
 *   <li>{@link PathVariable @PathVariable} 贪婪捕获 {@code {*msg}}：{@code broadcast {*msg}}</li>
 *   <li>{@link CommandParam @CommandParam} 命名参数 + 默认值：{@code heal --amount N}</li>
 *   <li>布尔标记：{@code fly --on}（出现即为 true）</li>
 *   <li>{@link Sender @Sender} {@link Player} 类型 → 强制「仅玩家可用」</li>
 *   <li>{@link CommandMapping#permission()} 权限校验</li>
 *   <li><b>特异性路由</b>：输入 {@code /kit give ...} 时，{@code give {item} {count}}
 *       （静态段多）会优先于 {@code /kit}（根命令帮助）命中</li>
 * </ul>
 *
 * <p>根命令 {@code kit} 由 {@link io.github.JiangHu.jframe.command.CommandEngine}
 * 在插件启用时<b>动态注册</b>到 Nukkit，无需在 {@code plugin.yml} 中声明。
 */
@CommandController("kit")
public class KitController {

    /**
     * {@code /kit} —— 空路径匹配根命令本身，显示帮助。
     * <p>
     * 当玩家只输入 {@code /kit}（无子命令）时命中本方法。
     * 若输入 {@code /kit give ...} 等子命令，特异性排序会让对应子命令优先命中。
     */
    @CommandMapping("")
    public void help(@Sender CommandSender sender) {
        sender.sendMessage("§a===== §f/kit 命令帮助 §a=====");
        sender.sendMessage("§7/kit give <物品ID> <数量> §8→ §f发放物品（路径变量+类型转换）");
        sender.sendMessage("§7/kit sword §8→ §f获得闪电钻石剑");
        sender.sendMessage("§7/kit heal [--amount 数量] §8→ §f回血（命名参数+默认值）");
        sender.sendMessage("§7/kit fly [--on] §8→ §f切换飞行（布尔标记）");
        sender.sendMessage("§7/kit broadcast <消息...> §8→ §f全服广播（贪婪变量）");
        sender.sendMessage("§7/kit whoami §8→ §f查看自己（仅玩家+权限）");
    }

    /**
     * {@code /kit give 264 64} —— 路径变量 {@code {item}} + {@code int} 自动类型转换。
     * <p>
     * {@code count} 声明为 {@code int}，框架自动把路径中的 {@code "64"} 转成数字；
     * 若输入非数字（如 {@code /kit give 264 abc}），框架返回 {@code §c参数错误}。
     *
     * @param player 命令发送者（声明 Player → 仅玩家可用）
     * @param item   物品 ID（字符串，方法内手动解析为数字）
     * @param count  数量（框架自动转换为 int）
     */
    @CommandMapping("give {item} {count}")
    public void give(@Sender Player player,
                     @PathVariable("item") String item,
                     @PathVariable("count") int count) {
        try {
            int itemId = Integer.parseInt(item);
            Item stack = Item.get(itemId, 0, Math.max(1, count));
            if (stack == null || stack.getId() == 0) {
                player.sendMessage("§c无效的物品ID: " + itemId);
                return;
            }
            player.getInventory().addItem(stack);
            player.sendMessage("§a已发放: §f" + stack.getName() + " §ax" + count);
        } catch (NumberFormatException e) {
            player.sendMessage("§c物品ID必须是数字，例如: §f/kit give 264 64");
        }
    }

    /**
     * {@code /kit sword} —— 发放闪电钻石剑（复用事件模块示例的 {@link ThunderSwordWrapper}）。
     * <p>
     * 演示：声明式命令可以与现有业务工具类无缝结合，替代 ExamplePlugin 中
     * 经典 {@code onCommand} 里的 {@code /sword} 写法。
     */
    @CommandMapping("sword")
    public void sword(@Sender Player player) {
        Item sword = ThunderSwordWrapper.createSword();
        player.getInventory().addItem(sword);
        player.sendMessage("§b⚡ 你获得了一把 §f[aaa] §b闪电钻石剑！拿在手上右键方块即可召唤闪电。");
    }

    /**
     * {@code /kit heal --amount 20} 或 {@code /kit heal}（使用默认值）。
     * <p>
     * 演示命名参数 {@code --amount}：声明了 {@code defaultValue} 后参数<b>隐式可选</b>，
     * 缺失时回退默认值（与 Spring MVC 语义一致）。
     *
     * @param amount 回血量（命名参数，默认 20）
     */
    @CommandMapping("heal")
    public void heal(@Sender Player player,
                     @CommandParam(value = "amount", defaultValue = "20") int amount) {
        int max = player.getMaxHealth();
        int target = Math.max(0, Math.min(amount, max));
        player.setHealth(target);
        player.sendMessage("§a已恢复生命值至 §e" + target + " §a/ §e" + max);
    }

    /**
     * {@code /kit fly --on} 或 {@code /kit fly --off} 或 {@code /kit fly}（默认开启）。
     * <p>
     * 演示<b>布尔标记</b>：{@code --on} 后不跟值时绑定为 {@code "true"}；
     * 声明 {@code defaultValue = "false"} 使其缺失时为关闭。
     *
     * @param on 是否开启飞行（布尔标记）
     */
    @CommandMapping("fly")
    public void fly(@Sender Player player,
                    @CommandParam(value = "on", defaultValue = "true") boolean on) {
        player.setAllowFlight(on);
        player.sendMessage(on ? "§b飞行已启用，双击跳跃即可起飞！" : "§7飞行已禁用");
    }

    /**
     * {@code /kit broadcast hello world foo} —— 贪婪变量 {@code {*msg}} 捕获剩余所有参数。
     * <p>
     * {@code {*msg}} 会把 {@code broadcast} 之后的所有 token 合并为 {@code String[]}。
     * 方法参数声明为 {@code String[]} 直接接收；若声明为 {@code String}，框架会用空格连接。
     *
     * @param msg 剩余所有参数（贪婪捕获）
     */
    @CommandMapping("broadcast {*msg}")
    public void broadcast(@Sender CommandSender sender,
                          @PathVariable("msg") String[] msg) {
        if (msg.length == 0) {
            sender.sendMessage("§c请输入要广播的内容: §f/kit broadcast <消息...>");
            return;
        }
        String text = String.join(" ", msg);
        Server.getInstance().broadcastMessage("§d[广播] §f" + sender.getName() + ": §e" + text);
    }

    /**
     * {@code /kit whoami} —— 仅玩家可用（{@code @Sender Player}）+ 权限校验。
     * <p>
     * <ul>
     *   <li>控制台执行 → 返回 {@code §c该命令只能由玩家在游戏内执行}</li>
     *   <li>无权限玩家执行 → 返回 {@code §c你没有权限执行此命令}</li>
     * </ul>
     * 权限 {@code jframe.example.use} 在 {@code plugin.yml} 中默认对所有玩家开放。
     */
    @CommandMapping(value = "whoami", desc = "查看自己的信息", permission = "jframe.example.use")
    public void whoami(@Sender Player player) {
        player.sendMessage("§a===== §f你的信息 §a=====");
        player.sendMessage("§7名字: §f" + player.getName());
        player.sendMessage("§7世界: §f" + player.getLevel().getName());
        player.sendMessage("§7坐标: §f" + (int) player.x + ", " + (int) player.y + ", " + (int) player.z);
        player.sendMessage("§7生命: §f" + (int) player.getHealth() + " §7/ §f" + player.getMaxHealth());
    }
}
