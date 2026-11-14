package io.github.JiangHu.jframe.command.example;

import cn.nukkit.command.CommandSender;
import io.github.JiangHu.jframe.command.annotation.CommandController;
import io.github.JiangHu.jframe.command.annotation.CommandMapping;
import io.github.JiangHu.jframe.command.annotation.Sender;

/**
 * 示例控制器③：大小写回归测试（根命令故意用<b>首字母大写</b> {@code GameItem}）。
 * <p>
 * 用于验证 jframe 命令路由与 Nukkit 的「命令名小写规范 + 大小写不敏感匹配」对齐：
 * <ul>
 *   <li>注册时根命令 {@code "GameItem"} 应被归一化为小写 {@code "gameitem"}；</li>
 *   <li>Nukkit 运行时回传的 {@code command.getName()} 恒为小写，应能命中路由；</li>
 *   <li>玩家输入 {@code /GameItem}、{@code /gameitem}、{@code /GAMEITEM} 均应命中同一方法。</li>
 * </ul>
 * 复刻真实插件场景：根命令用驼峰命名（如 {@code GameItem}），方法路径含子命令（如 {@code give}）。
 */
@CommandController("GameItem")
public class CaseController {

    /** 最近一次触发的标记（main=打开表单，give=获取物品），由测试读取校验 */
    public static String lastTriggered;

    /** {@code /gameitem}（无参数）：打开管理表单 — 方法路径为空，处理根命令本身 */
    @CommandMapping(desc = "打开游戏物品管理表单")
    public void main(@Sender CommandSender sender) {
        lastTriggered = "main";
        sender.sendMessage("§a已打开表单");
    }

    /** {@code /gameitem give <标识符>}：获取物品到背包 */
    @CommandMapping(value = "give", desc = "获取游戏物品到背包", usage = "<标识符>")
    public void give(@Sender CommandSender sender, String identifier) {
        lastTriggered = "give:" + identifier;
        sender.sendMessage("§a已获得物品：" + identifier);
    }
}
