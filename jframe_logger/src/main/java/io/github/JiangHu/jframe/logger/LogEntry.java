package io.github.JiangHu.jframe.logger;

/**
 * 一条统一日志记录（不可变）。
 *
 * @param seq        本 JVM 内单调递增序号（{@code LogStore.nextSeq()} 分配）
 * @param timeMillis 产生时间（毫秒epoch；跨子服聚合时按此字段排序）
 * @param server     子服标识（本 JVM 自身 id，或 host 侧为 agent 覆盖后的注册 id）
 * @param source     日志来源（通常是插件名）
 * @param level      级别
 * @param thread     产生日志的线程名
 * @param message    消息正文（不为 null）
 * @param stackTrace 异常堆栈渲染结果（无异常时为 null）
 */
public record LogEntry(
        long seq,
        long timeMillis,
        String server,
        String source,
        LogLevel level,
        String thread,
        String message,
        String stackTrace) {

    public LogEntry {
        message = message == null ? "" : message;
        if (stackTrace != null && stackTrace.isBlank()) {
            stackTrace = null;
        }
    }

    /**
     * 复制本条记录并替换子服标识（host 侧 ingestion 时用注册 id 覆盖 agent 上报值，防伪造）。
     *
     * @param newServer 新子服标识
     * @return 新记录
     */
    public LogEntry withServer(String newServer) {
        return new LogEntry(seq, timeMillis, newServer, source, level, thread, message, stackTrace);
    }
}
