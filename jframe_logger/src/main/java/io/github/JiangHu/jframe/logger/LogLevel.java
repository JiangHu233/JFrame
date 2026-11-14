package io.github.JiangHu.jframe.logger;

/**
 * 统一日志级别。
 * <p>
 * 序数即严重度：{@code DEBUG < INFO < WARN < ERROR}。
 * {@link #atLeast(LogLevel)} 用于级别阈值过滤。
 */
public enum LogLevel {

    DEBUG,
    INFO,
    WARN,
    ERROR;

    /**
     * 本级别是否达到给定下限级别。
     *
     * @param min 下限级别（null 视为不过滤）
     * @return {@code level.ordinal() >= min.ordinal()}
     */
    public boolean atLeast(LogLevel min) {
        return min == null || ordinal() >= min.ordinal();
    }

    /**
     * 宽松解析级别名（大小写不敏感，兼容 WARNING/ERR 等别名）。
     *
     * @param name 级别名
     * @param def  解析失败时的默认值
     * @return 解析结果
     */
    public static LogLevel parse(String name, LogLevel def) {
        if (name == null || name.isBlank()) {
            return def;
        }
        return switch (name.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "DEBUG" -> DEBUG;
            case "INFO" -> INFO;
            case "WARN", "WARNING" -> WARN;
            case "ERROR", "ERR" -> ERROR;
            default -> def;
        };
    }
}
