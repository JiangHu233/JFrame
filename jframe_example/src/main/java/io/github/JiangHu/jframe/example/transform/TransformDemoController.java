package io.github.JiangHu.jframe.example.transform;

import cn.nukkit.command.CommandSender;
import io.github.JiangHu.jframe.command.annotation.CommandController;
import io.github.JiangHu.jframe.command.annotation.CommandMapping;
import io.github.JiangHu.jframe.command.annotation.Sender;
import io.github.JiangHu.jframe.transform.HookHandle;
import io.github.JiangHu.jframe.transform.HookStatus;
import io.github.JiangHu.jframe.transform.TransformPluginScope;

import java.util.List;

/**
 * 动态类增强模块<b>实机演示控制器</b>（根命令 {@code /jtransform}）。
 * <p>
 * 演示"服务器正常启动后"运行中动态增强已加载的 Nukkit 类：
 * <ul>
 *   <li>{@code /jtransform on} —— 扫描 {@link TransformPatches} 的 {@code @HookOn}
 *       注解方法并注册（首次注册触发向 JVM 自附加 agent，零启动参数）</li>
 *   <li>{@code /jtransform off} —— 撤销全部钩子并还原原始字节码</li>
 *   <li>{@code /jtransform status} —— 查看句柄状态 / 已织入类 / 相位省略形状</li>
 * </ul>
 * 可直接观察：{@code on} 后服务器列表 Motd 带 {@code [JFrame 增强]} 前缀
 * （{@code Server#getMotd} 的 RETURN 相位钩子）；{@code off} 后恢复原样。
 */
@CommandController("jtransform")
public class TransformDemoController {

    /** 本插件的 transform 作用域（由 ExamplePlugin 在 scan 前静态注入） */
    private static TransformPluginScope transform;

    /** 命令式演示句柄（on 注册 / off 撤销，status 展示） */
    private static HookHandle imperativeHandle;

    /** 注解式演示是否已开启 */
    private static boolean annotationOn;

    /**
     * 注入 transform 作用域。
     *
     * @param scope 作用域
     */
    public static void setTransform(TransformPluginScope scope) {
        transform = scope;
    }

    /** {@code /jtransform}：帮助。 */
    @CommandMapping(value = "", desc = "动态类增强演示：on 开启 / off 还原 / status 查看状态")
    public void help(@Sender CommandSender sender) {
        sender.sendMessage("§7用法：§f/jtransform on §7| §f/jtransform off §7| §f/jtransform status");
    }

    /** {@code /jtransform on}：① 注解式扫描演示切面 ② 命令式 REPLACE 演示（Server#getMotd 签名镜像）。 */
    @CommandMapping(value = "on", desc = "开启演示钩子（注解式 RETURN + 命令式 REPLACE）")
    public void on(@Sender CommandSender sender) {
        if (annotationOn || imperativeHandle != null) {
            sender.sendMessage("§7演示钩子已开启（先 /jtransform off 还原后再开）");
            return;
        }
        try {
            // ① 注解声明式：扫描本包 @HookOn 方法（TransformPatches 的 motdPrefixHook）
            transform.scan("io.github.JiangHu.jframe.example.transform");
            annotationOn = true;
            // ② 命令式：REPLACE 接管 Server#getMaxPlayers（演示整方法接管 + 恢复）
            imperativeHandle = transform.replace("cn.nukkit.Server", "getMaxPlayers",
                    ctx -> 999);
        } catch (Exception e) {
            sender.sendMessage("§c动态增强不可用：" + e.getMessage());
            return;
        }
        sender.sendMessage("§a已开启：Motd 带 [JFrame 增强] 前缀（RETURN 相位）");
        sender.sendMessage("§a已开启：最大人数显示 999（命令式 REPLACE 整方法接管）");
        sender.sendMessage("§7刷新服务器列表观察；/jtransform off 还原原始字节码");
    }

    /** {@code /jtransform off}：撤销全部演示钩子并还原。 */
    @CommandMapping(value = "off", desc = "撤销全部演示钩子并还原原始字节码")
    public void off(@Sender CommandSender sender) {
        if (!annotationOn && imperativeHandle == null) {
            sender.sendMessage("§7演示钩子未开启");
            return;
        }
        transform.unhookAll();
        imperativeHandle = null;
        annotationOn = false;
        sender.sendMessage("§a已撤销：Motd / 最大人数恢复原样（字节码已还原）");
    }

    /** {@code /jtransform status}：句柄状态 / 已织入类 / 形状。 */
    @CommandMapping(value = "status", desc = "查看演示钩子状态与织入形状")
    public void status(@Sender CommandSender sender) {
        sender.sendMessage("§7注解式: " + (annotationOn ? "已开启" : "未开启")
                + " §7| 命令式: " + (imperativeHandle == null ? "未开启" : "已开启"));
        if (imperativeHandle != null) {
            HookStatus st = imperativeHandle.status();
            List<String> classes = imperativeHandle.touchedClassNames();
            sender.sendMessage("§fREPLACE 句柄: " + st + " §7| 已织入类: " + classes
                    + " §7| 形状: " + imperativeHandle.shape());
        }
    }
}
