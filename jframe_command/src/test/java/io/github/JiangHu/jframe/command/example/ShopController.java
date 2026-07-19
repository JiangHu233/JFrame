package io.github.JiangHu.jframe.command.example;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import io.github.JiangHu.jframe.command.annotation.CommandController;
import io.github.JiangHu.jframe.command.annotation.CommandMapping;
import io.github.JiangHu.jframe.command.annotation.CommandParam;
import io.github.JiangHu.jframe.command.annotation.PathVariable;
import io.github.JiangHu.jframe.command.annotation.RawArgs;
import io.github.JiangHu.jframe.command.annotation.Sender;

/**
 * 示例控制器②：商店命令（根命令 {@code shop}）。
 * <p>
 * 演示以下能力：
 * <ul>
 *   <li>多路径变量 + 类型转换：{@code buy {item} {count}}（count 自动转 int）</li>
 *   <li>布尔标记命名参数：{@code sell {item} --all}（{@code --all} 出现即为 true）</li>
 *   <li>{@link RawArgs @RawArgs} 原始参数透传：{@code echo}</li>
 *   <li>{@link Sender @Sender} {@link Player} 类型 → 强制「仅玩家可用」</li>
 *   <li>{@link CommandMapping#permission()} 权限与 {@link CommandMapping#aliases()} 别名</li>
 * </ul>
 */
@CommandController("shop")
public class ShopController {

    public static String lastBuy;
    public static String lastSell;
    public static String lastEcho;
    public static boolean spawnInvoked;

    /** /shop buy diamond 64  →  item=diamond, count=64 */
    @CommandMapping("buy {item} {count}")
    public void buy(@Sender CommandSender sender,
                    @PathVariable("item") String item,
                    @PathVariable("count") int count) {
        lastBuy = item + " x" + count;
        sender.sendMessage("§a购买 " + item + " x" + count);
    }

    /** /shop sell sword --all   或   /shop sell sword（默认 all=false） */
    @CommandMapping("sell {item}")
    public void sell(@Sender CommandSender sender,
                     @PathVariable("item") String item,
                     @CommandParam(value = "all", defaultValue = "false") boolean all) {
        lastSell = item + " (all=" + all + ")";
        sender.sendMessage("§e出售 " + item + (all ? "（全部）" : "（一个）"));
    }

    /** /shop echo foo bar  →  rawArgs 透传（自行解析） */
    @CommandMapping("echo")
    public void echo(@Sender CommandSender sender, @RawArgs String[] rawArgs) {
        lastEcho = String.join(" ", rawArgs);
        sender.sendMessage("§7" + lastEcho);
    }

    /**
     * /shop spawn  →  仅玩家可用（声明 {@link Player} 类型）。
     * 控制台/FakeSender 执行将返回失败。
     */
    @CommandMapping(value = "spawn", desc = "传送到商店", permission = "shop.spawn")
    public void spawn(@Sender Player player) {
        spawnInvoked = true;
        player.sendMessage("§d已传送到商店");
    }
}
