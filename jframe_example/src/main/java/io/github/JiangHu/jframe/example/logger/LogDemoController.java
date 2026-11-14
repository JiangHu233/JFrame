package io.github.JiangHu.jframe.example.logger;

import cn.nukkit.command.CommandSender;
import io.github.JiangHu.jframe.command.annotation.CommandController;
import io.github.JiangHu.jframe.command.annotation.CommandMapping;
import io.github.JiangHu.jframe.command.annotation.Sender;
import io.github.JiangHu.jframe.logger.JLogger;

/**
 * 统一日志模块<b>实机演示控制器</b>（根命令 {@code /logdemo}）。
 * <p>
 * 演示业务插件接入统一日志库的姿势：经 {@code JFrameMain.getLoggerAPI().forPlugin(this)}
 * 取得 {@link JLogger} 后写入——默认静默（不进 MCSM 面板），配合 {@code /jlog start}
 * 开启的 Web 页面查看总量/单插件视图与实时推送。
 *
 * @see JLogger
 */
@CommandController("logdemo")
public class LogDemoController {

    /** 本插件的统一日志器（由 ExamplePlugin 在 scan 前静态注入） */
    private static JLogger log;

    /**
     * 注入统一日志器。
     *
     * @param logger 日志器
     */
    public static void setLogger(JLogger logger) {
        log = logger;
    }

    /** {@code /logdemo}：依次输出各级别统一日志（含一条带异常的 error）。 */
    @CommandMapping(value = "", desc = "写入各级别统一日志（默认不进控制台，/jlog start 后网页查看）")
    public void demo(@Sender CommandSender sender) {
        log.debug("DEBUG 级：调试细节（Web 端级别筛选选 DEBUG 可见）");
        log.info("INFO 级：" + sender.getName() + " 触发了 /logdemo");
        log.warn("WARN 级：示例警告（如余额接近下限）");
        log.error("ERROR 级：示例错误");
        log.error("ERROR 级（带异常）：扣款示例失败", new IllegalStateException("economy-insufficient-funds"));
        sender.sendMessage("§a已写入 5 条统一日志（默认不进控制台/MCSM 面板）");
        sender.sendMessage("§7用 §f/jlog start §7开启 Web 端口，浏览器打开提示地址即可查看（根路由汇总，/s/<子服> 分面板）");
    }
}
