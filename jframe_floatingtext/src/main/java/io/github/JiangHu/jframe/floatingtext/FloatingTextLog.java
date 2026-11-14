package io.github.JiangHu.jframe.floatingtext;

import io.github.JiangHu.jframe.core.JFrameLog;

/**
 * 浮空字模块日志工具 — 包装 {@link JFrameLog} 并提供无服务器环境降级（同 EcsLog 模式）。
 */
public final class FloatingTextLog {

    private static final String TAG = "FloatingText";

    private FloatingTextLog() {
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
