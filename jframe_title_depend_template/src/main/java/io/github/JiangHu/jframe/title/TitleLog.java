package io.github.JiangHu.jframe.title;

import io.github.JiangHu.jframe.core.JFrameLog;

/**
 * title 模块安全日志工具——{@link JFrameLog} 的降级封装。
 *
 * <h3>设计动机</h3>
 * <p>{@link JFrameLog} 依赖 {@code Server.getInstance()}，在 Nukkit Server
 * 尚未初始化时（Spring 容器启动阶段、单元测试环境）调用会抛出
 * {@link IllegalStateException}。title 模块的告警路径（未知元数据 key、
 * 未注册模板等）在 Server 就绪前即可能被触发（如模板预编译），
 * 不应因日志基础设施未就绪而中断业务流程。</p>
 *
 * <p>本工具在 Server 未就绪时降级输出到 {@code System.err}，
 * 保证日志调用始终安全；Server 就绪后行为与 {@link JFrameLog} 完全一致。</p>
 */
public final class TitleLog {

    private TitleLog() {
    }

    /**
     * 输出 WARNING 级别日志（Server 未就绪时降级 stderr）
     *
     * @param tag     日志标签
     * @param message 日志消息
     */
    public static void warning(String tag, String message) {
        try {
            JFrameLog.warning(tag, message);
        } catch (IllegalStateException e) {
            System.err.println("[title][" + tag + "] " + message);
        }
    }
}
