package io.github.JiangHu.jframe.logger;

import cn.nukkit.command.CommandSender;

import java.util.Locale;

/**
 * {@code /jlog} 指令逻辑 —— 与 Nukkit 分发解耦（由 {@code JFrameMain.onCommand} 委托），
 * 便于脱离服务器单测。
 *
 * <h3>命令一览</h3>
 * <pre>
 *   /jlog                  显示帮助
 *   /jlog start            探测并启动：config 端口空闲则本机监听；被本机 JFrame logger 占用则挂靠
 *   /jlog start &lt;端口&gt;    指定端口（1~65535），同上探测逻辑
 *   /jlog start auto       先探测 config 端口（是 host 则挂靠）；否则 OS 分配未占用端口独立监听
 *   /jlog stop             停止（host 释放端口 / agent 注销挂靠）
 *   /jlog status           角色与运行状态
 * </pre>
 */
public final class JLogCommand {

    private static final String USAGE = "§7用法: /jlog <start [端口|auto] | stop | status>";

    private JLogCommand() {
    }

    /**
     * 执行指令。
     *
     * @param api    LoggerAPI
     * @param sender 指令发送者
     * @param args   参数（不含 /jlog 本身）
     * @return 已处理（恒 true）
     */
    public static boolean execute(LoggerAPI api, CommandSender sender, String[] args) {
        if (api == null || sender == null) {
            return true;
        }
        if (args == null || args.length == 0) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> handleStart(api, sender, args);
            case "stop" -> {
                boolean ok = api.stopWeb();
                sender.sendMessage(ok
                        ? "§a已停止 Web 日志展示（端口已释放 / 挂靠已注销）"
                        : "§c当前未在运行");
            }
            case "status" -> {
                for (String line : api.webStatus().split("\n")) {
                    sender.sendMessage("§7" + line);
                }
            }
            default -> help(sender);
        }
        return true;
    }

    private static void handleStart(LoggerAPI api, CommandSender sender, String[] args) {
        int port;
        if (args.length >= 2) {
            String a = args[1];
            if ("auto".equalsIgnoreCase(a)) {
                port = 0;
            } else {
                try {
                    port = Integer.parseInt(a);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§c端口参数非法: " + a);
                    sender.sendMessage(USAGE);
                    return;
                }
                if (port < 1 || port > 65535) {
                    sender.sendMessage("§c端口需在 1~65535 之间（auto 表示自动分配）");
                    sender.sendMessage(USAGE);
                    return;
                }
            }
        } else {
            port = api.defaultWebPort();
        }

        WebStartResult r = api.startWeb(port);
        if (!r.success()) {
            sender.sendMessage("§c启动失败: " + r.message());
            if (port > 0) {
                sender.sendMessage("§7提示: 可用 §f/jlog start auto §7自动分配未占用端口");
            }
            return;
        }
        switch (r.role()) {
            case HOST -> sender.sendMessage("§a已启动（HOST，本机监听）: §f" + r.url()
                    + "§r §7- " + r.message());
            case AGENT -> sender.sendMessage("§a已挂靠（AGENT）: 本机其它 JFrame logger 已开端，"
                    + "本实例日志见 §f" + r.url());
            default -> sender.sendMessage("§c启动失败: " + r.message());
        }
        sender.sendMessage("§7浏览器打开上述地址：根路由汇总全部子服，/s/<子服> 分面板查看");
    }

    private static void help(CommandSender sender) {
        sender.sendMessage("§a===== §f/jlog JFrame 统一日志 §a=====");
        sender.sendMessage("§7/jlog start §8→ §f启动（config 端口；被本机其它 JFrame logger 占用则自动挂靠）");
        sender.sendMessage("§7/jlog start <端口> §8→ §f指定端口启动");
        sender.sendMessage("§7/jlog start auto §8→ §f自动分配未占用端口");
        sender.sendMessage("§7/jlog stop §8→ §f停止并释放资源");
        sender.sendMessage("§7/jlog status §8→ §f查看角色与状态");
    }
}
