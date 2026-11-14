package io.github.JiangHu.jframe.engine.ecs.engine;

import io.github.JiangHu.jframe.core.JFrameLog;

/**
 * ECS 模块日志工具 — 包装 {@link JFrameLog} 并提供<b>无服务器环境降级</b>。
 *
 * <h3>设计动机</h3>
 * <p>
 * {@code JFrameLog} 依赖 {@code Server.getInstance()}，在单元测试（无 Nukkit 服务器）
 * 环境下调用会抛出 {@code IllegalStateException}。ECS 引擎的错误路径（组件回调异常等）
 * 只应记录不应中断，因此本工具在服务器未就绪时降级为 {@code System.err} 输出，
 * 保证引擎核心逻辑可在纯 JVM 环境下测试。
 */
public final class EcsLog {

    private static final String TAG = "ECS";

    private EcsLog() {
    }

    /**
     * 输出 INFO 级别日志（服务器未就绪时降级 stdout）。
     *
     * @param message 日志消息
     */
    public static void info(String message) {
        try {
            JFrameLog.info(TAG, message);
        } catch (IllegalStateException e) {
            System.out.println("[" + TAG + "] " + message);
        }
    }

    /**
     * 输出 WARNING 级别日志（服务器未就绪时降级 stderr）。
     *
     * @param message 日志消息
     */
    public static void warning(String message) {
        try {
            JFrameLog.warning(TAG, message);
        } catch (IllegalStateException e) {
            System.err.println("[" + TAG + "][WARN] " + message);
        }
    }

    /**
     * 输出 ERROR 级别日志（服务器未就绪时降级 stderr）。
     *
     * @param message 日志消息
     * @param error   异常
     */
    public static void error(String message, Throwable error) {
        try {
            JFrameLog.error(TAG, message, error);
        } catch (IllegalStateException e) {
            System.err.println("[" + TAG + "] " + message);
            error.printStackTrace(System.err);
        }
    }
}
