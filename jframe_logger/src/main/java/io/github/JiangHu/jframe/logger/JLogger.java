package io.github.JiangHu.jframe.logger;

import io.github.JiangHu.jframe.logger.core.LogStore;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 统一日志器 —— 业务插件经 {@code LoggerAPI.forPlugin(this)} 取得后写入统一日志库。
 * <p>
 * 默认<b>静默</b>：写入只进统一日志库（内存环形缓冲 / 可选落盘 / Web 展示 / agent 转发），
 * <b>不</b>输出到 Nukkit 控制台（即不进 MCSM 面板）——这是给面板降噪的核心语义。
 * 需要镜像回控制台的插件调用 {@link #setConsoleMirror(boolean)}。
 * <p>
 * 线程安全，可在任意线程（含异步线程）调用，不要求 Nukkit 主线程。
 */
public final class JLogger {

    private final LogStore store;
    private final String source;
    private final java.util.function.Supplier<String> serverId;
    private final LongSupplier clock;

    private volatile boolean consoleMirror = false;
    private volatile LogLevel minLevel = LogLevel.INFO;

    JLogger(LogStore store, String source, Supplier<String> serverId, LongSupplier clock) {
        this.store = store;
        this.source = source;
        this.serverId = serverId;
        this.clock = clock;
    }

    /**
     * 本日志器的来源名（插件名）。
     *
     * @return 来源名
     */
    public String source() {
        return source;
    }

    /**
     * DEBUG 级日志。
     *
     * @param message 消息
     */
    public void debug(String message) {
        log(LogLevel.DEBUG, message, null);
    }

    /**
     * INFO 级日志。
     *
     * @param message 消息
     */
    public void info(String message) {
        log(LogLevel.INFO, message, null);
    }

    /**
     * WARN 级日志。
     *
     * @param message 消息
     */
    public void warn(String message) {
        log(LogLevel.WARN, message, null);
    }

    /**
     * ERROR 级日志。
     *
     * @param message 消息
     */
    public void error(String message) {
        log(LogLevel.ERROR, message, null);
    }

    /**
     * ERROR 级日志（带异常，堆栈渲染为文本随条目保存）。
     *
     * @param message 消息
     * @param t       异常
     */
    public void error(String message, Throwable t) {
        log(LogLevel.ERROR, message, t);
    }

    /**
     * 是否镜像到 Nukkit 控制台（默认 false，即静默）。
     *
     * @param mirror true = 同时输出到控制台
     */
    public void setConsoleMirror(boolean mirror) {
        this.consoleMirror = mirror;
    }

    /**
     * @return 是否镜像控制台
     */
    public boolean consoleMirror() {
        return consoleMirror;
    }

    /**
     * 设置级别下限（低于该级别的日志被丢弃，默认 INFO）。
     *
     * @param minLevel 下限级别
     */
    public void setLevel(LogLevel minLevel) {
        this.minLevel = minLevel == null ? LogLevel.DEBUG : minLevel;
    }

    /**
     * @return 当前级别下限
     */
    public LogLevel level() {
        return minLevel;
    }

    private void log(LogLevel level, String message, Throwable t) {
        if (!level.atLeast(minLevel)) {
            return;
        }
        String stack = t == null ? null : renderStackTrace(t);
        LogEntry entry = new LogEntry(
                store.nextSeq(),
                clock.getAsLong(),
                serverId.get(),
                source,
                level,
                Thread.currentThread().getName(),
                message,
                stack);
        store.append(entry);
        if (consoleMirror) {
            mirrorToConsole(entry, t);
        }
    }

    private void mirrorToConsole(LogEntry entry, Throwable t) {
        try {
            cn.nukkit.Server server = cn.nukkit.Server.getInstance();
            if (server == null) {
                return; // 测试环境 / 服务端未就绪：静默跳过
            }
            cn.nukkit.utils.Logger logger = server.getLogger();
            String line = "[" + source + "/" + entry.level() + "] " + entry.message();
            switch (entry.level()) {
                case DEBUG -> logger.debug(line);
                case INFO -> logger.info(line);
                case WARN -> logger.warning(line);
                case ERROR -> {
                    if (t != null) {
                        logger.error(line, t);
                    } else {
                        logger.error(line);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 镜像失败不影响统一日志库写入
        }
    }

    private static String renderStackTrace(Throwable t) {
        StringBuilder sb = new StringBuilder(256);
        renderThrowable(sb, t, "");
        return sb.toString();
    }

    private static void renderThrowable(StringBuilder sb, Throwable t, String indent) {
        sb.append(indent).append(t.toString());
        for (StackTraceElement e : t.getStackTrace()) {
            sb.append("\n").append(indent).append("\tat ").append(e);
        }
        Throwable[] suppressed = t.getSuppressed();
        for (Throwable s : suppressed) {
            sb.append("\n").append(indent).append("\tSuppressed: ");
            renderThrowable(sb, s, indent + "\t");
        }
        Throwable cause = t.getCause();
        if (cause != null) {
            sb.append("\n").append(indent).append("Caused by: ");
            renderThrowable(sb, cause, indent);
        }
    }
}
