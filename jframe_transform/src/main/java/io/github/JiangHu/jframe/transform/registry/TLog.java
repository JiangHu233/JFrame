package io.github.JiangHu.jframe.transform.registry;

import io.github.JiangHu.jframe.core.JFrameLog;

/**
 * 模块日志工具——优先 Nukkit 控制台（经 core JFrameLog），无服务端环境（单元测试）
 * 时降级 stderr，保证任何阶段可用。
 */
public final class TLog {

    private static final String TAG = "JFrame-Transform";

    private TLog() {
    }

    /**
     * INFO 日志。
     *
     * @param message 消息
     */
    public static void info(String message) {
        try {
            JFrameLog.info(TAG, message);
        } catch (Throwable noServer) {
            System.out.println("[" + TAG + "] " + message);
        }
    }

    public static void warn(String message) {
        try {
            JFrameLog.warning(TAG, message);
        } catch (Throwable noServer) {
            System.err.println("[" + TAG + "][WARN] " + message);
        }
    }

    public static void error(String message, Throwable t) {
        try {
            JFrameLog.error(TAG, message + " (" + t + ")");
        } catch (Throwable noServer) {
            System.err.println("[" + TAG + "][ERROR] " + message);
            t.printStackTrace();
        }
    }
}
