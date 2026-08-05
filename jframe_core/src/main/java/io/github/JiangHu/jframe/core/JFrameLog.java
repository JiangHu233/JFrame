package io.github.JiangHu.jframe.core;

import cn.nukkit.Server;
import cn.nukkit.utils.Logger;

/**
 * JFrame 日志工具 — 封装 {@code Server.getInstance().getLogger()} 的冗长调用。
 *
 * <h3>设计动机</h3>
 * <p>
 * 项目中有 25+ 处 {@code Server.getInstance().getLogger().xxx()} 调用，散布在
 * command、event、async、ai、inventory 等模块。每次都要写一长串，且日志前缀不统一。
 * <p>
 * 本工具类提供简洁的静态方法，自动添加 {@code [tag]} 前缀：
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 简洁调用
 * JFrameLog.info("CommandScanner", "已注册命令控制器: " + className);
 * JFrameLog.error("CommandScanner", "注册失败: " + className, e);
 * JFrameLog.warning("CommandScanner", "跳过无法加载的类: " + className);
 *
 * // 等价于原来的冗长写法
 * Server.getInstance().getLogger().info("[CommandScanner] 已注册命令控制器: " + className);
 * Server.getInstance().getLogger().error("[CommandScanner] 注册失败: " + className, e);
 * Server.getInstance().getLogger().warning("[CommandScanner] 跳过无法加载的类: " + className);
 * }</pre>
 *
 * <h3>调用时机</h3>
 * 本类依赖 {@link Server#getInstance()}，只能在服务端启动后（如 {@code onEnable}）调用。
 * 在 Spring 容器启动阶段 Server 可能尚未就绪。
 */
public final class JFrameLog {

    private JFrameLog() {
    }

    /**
     * 获取 Nukkit Logger（每次实时获取，避免缓存过期）。
     *
     * @return Nukkit Logger
     * @throws IllegalStateException Server 尚未初始化时抛出
     */
    private static Logger logger() {
        Server server = Server.getInstance();
        if (server == null) {
            throw new IllegalStateException("Nukkit Server 尚未初始化，请在服务端启动后（如 onEnable）调用");
        }
        return server.getLogger();
    }

    /**
     * 输出 INFO 级别日志。
     *
     * @param tag     日志标签（通常是类名或模块名，如 "CommandScanner"）
     * @param message 日志消息
     */
    public static void info(String tag, String message) {
        logger().info("[" + tag + "] " + message);
    }

    /**
     * 输出 WARNING 级别日志。
     *
     * @param tag     日志标签
     * @param message 日志消息
     */
    public static void warning(String tag, String message) {
        logger().warning("[" + tag + "] " + message);
    }

    /**
     * 输出 ERROR 级别日志（不带异常堆栈）。
     *
     * @param tag     日志标签
     * @param message 日志消息
     */
    public static void error(String tag, String message) {
        logger().error("[" + tag + "] " + message);
    }

    /**
     * 输出 ERROR 级别日志（带异常堆栈）。
     *
     * @param tag     日志标签
     * @param message 日志消息
     * @param cause   异常原因
     */
    public static void error(String tag, String message, Throwable cause) {
        logger().error("[" + tag + "] " + message, cause);
    }

    /**
     * 输出 DEBUG 级别日志。
     *
     * @param tag     日志标签
     * @param message 日志消息
     */
    public static void debug(String tag, String message) {
        logger().debug("[" + tag + "] " + message);
    }
}
