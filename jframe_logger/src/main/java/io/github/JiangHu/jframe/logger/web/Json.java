package io.github.JiangHu.jframe.logger.web;

import io.github.JiangHu.jframe.logger.LogEntry;
import io.github.JiangHu.jframe.logger.core.LogStore;

import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 输出工具 —— 仅覆盖 logger Web API 需要的编码场景
 * （手写避免依赖服务端 Gson 版本对 record 的支持差异）。
 */
public final class Json {

    private Json() {
    }

    /**
     * JSON 字符串字面量（含引号与转义）。
     *
     * @param s 原始字符串（null → "null" 不带引号）
     * @return JSON 片段
     */
    public static String str(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * 编码单条日志。
     *
     * @param e 日志条目
     * @return JSON 对象字符串
     */
    public static String entry(LogEntry e) {
        return "{\"seq\":" + e.seq()
                + ",\"time\":" + e.timeMillis()
                + ",\"server\":" + str(e.server())
                + ",\"source\":" + str(e.source())
                + ",\"level\":" + str(e.level().name())
                + ",\"thread\":" + str(e.thread())
                + ",\"message\":" + str(e.message())
                + ",\"stack\":" + (e.stackTrace() == null ? "null" : str(e.stackTrace()))
                + "}";
    }

    /**
     * 编码日志数组。
     *
     * @param entries 日志列表
     * @return JSON 数组字符串
     */
    public static String entries(List<LogEntry> entries) {
        StringBuilder sb = new StringBuilder(entries.size() * 128 + 2);
        sb.append('[');
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(entry(entries.get(i)));
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * 编码子服统计数组。
     *
     * @param stats 子服 → 统计
     * @return JSON 数组字符串
     */
    public static String servers(Map<String, LogStore.ServerStats> stats) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        for (LogStore.ServerStats s : stats.values()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"server\":").append(str(s.server()))
                    .append(",\"sources\":").append(s.sources())
                    .append(",\"entries\":").append(s.entries())
                    .append(",\"latestTime\":").append(s.latestTime())
                    .append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * 编码来源统计数组。
     *
     * @param stats 来源统计列表
     * @return JSON 数组字符串
     */
    public static String sources(List<LogStore.SourceStats> stats) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        for (LogStore.SourceStats s : stats) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"server\":").append(str(s.server()))
                    .append(",\"source\":").append(str(s.source()))
                    .append(",\"entries\":").append(s.entries())
                    .append(",\"latestTime\":").append(s.latestTime())
                    .append('}');
        }
        sb.append(']');
        return sb.toString();
    }
}
