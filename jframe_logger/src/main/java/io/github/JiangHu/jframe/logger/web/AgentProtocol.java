package io.github.JiangHu.jframe.logger.web;

import io.github.JiangHu.jframe.logger.LogEntry;
import io.github.JiangHu.jframe.logger.LogLevel;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * agent ↔ host 内部协议（全部走 127.0.0.1）。
 * <p>
 * 传输层：agent 端点仅接受回环来源；host 配 token 时代理请求需带相同 token。
 * 载荷格式：TSV 行协议（字段转义后以 tab 分隔，一行一条日志），
 * 避免依赖服务端 Gson 版本；探测响应为 JSON 文本（子串匹配识别）。
 */
public final class AgentProtocol {

    /** 探测结果。 */
    public enum ProbeResult {
        /** 端口上是 JFrame logger host */
        JFRAME,
        /** 端口被非 JFrame logger 程序占用 */
        OCCUPIED_OTHER,
        /** 端口无人监听 */
        REFUSED
    }

    /** HTTP 简单结果。 */
    public record HttpResult(int status, String body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    public static final String PING_PATH = "/api/ping";
    public static final String PING_MAGIC = "\"app\":\"jframe-logger\"";

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    private AgentProtocol() {
    }

    /**
     * 探测本机端口是否为 JFrame logger host。
     * <p>
     * 分类依据：连接被拒（ConnectException 链）= 端口无人监听；有响应且含指纹 = JFrame；
     * 其余（超时/无指纹/非 2xx）= 被其它程序占用。
     *
     * @param port  端口
     * @param token token（可为空）
     * @return 探测结果
     */
    public static ProbeResult probe(int port, String token) {
        try {
            HttpResult r = get(port, PING_PATH + queryTail(token, ""), PROBE_TIMEOUT);
            if (r.ok() && r.body().contains(PING_MAGIC)) {
                return ProbeResult.JFRAME;
            }
            return ProbeResult.OCCUPIED_OTHER;
        } catch (Exception e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof java.net.ConnectException) {
                    return ProbeResult.REFUSED;
                }
            }
            return ProbeResult.OCCUPIED_OTHER;
        }
    }

    /**
     * GET 请求（127.0.0.1）。
     *
     * @param port    端口
     * @param pathQuery 路径+查询串
     * @param timeout 超时
     * @return 结果
     * @throws IOException   网络/非 2xx 以外的协议错误
     * @throws InterruptedException 中断
     */
    public static HttpResult get(int port, String pathQuery, Duration timeout)
            throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(PROBE_TIMEOUT)
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + pathQuery))
                .timeout(timeout)
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        return new HttpResult(resp.statusCode(), resp.body());
    }

    /**
     * POST 文本请求（127.0.0.1）。
     *
     * @param port     端口
     * @param pathQuery 路径+查询串
     * @param body     文本体（TSV）
     * @param token    token（可为空）
     * @return 结果
     * @throws IOException          网络错误
     * @throws InterruptedException 中断
     */
    public static HttpResult post(int port, String pathQuery, String body, String token)
            throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(PROBE_TIMEOUT)
                .build();
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + pathQuery))
                .timeout(CALL_TIMEOUT)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null && !token.isBlank()) {
            rb.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        return new HttpResult(resp.statusCode(), resp.body());
    }

    static String queryTail(String token, String extraParams) {
        StringBuilder sb = new StringBuilder();
        if (token != null && !token.isBlank()) {
            sb.append(sb.isEmpty() ? '?' : '&').append("token=").append(urlEncode(token));
        }
        if (extraParams != null && !extraParams.isEmpty()) {
            sb.append(sb.isEmpty() ? '?' : '&').append(extraParams);
        }
        return sb.toString();
    }

    static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ==================== TSV 行协议 ====================

    /**
     * 编码单条日志为 TSV 行。
     *
     * @param e 日志
     * @return 单行字符串（无换行符）
     */
    public static String encodeEntry(LogEntry e) {
        return e.seq() + "\t" + e.timeMillis() + "\t" + escape(e.server()) + "\t" + escape(e.source())
                + "\t" + e.level().name() + "\t" + escape(e.thread()) + "\t" + escape(e.message())
                + "\t" + (e.stackTrace() == null ? "" : escape(e.stackTrace()));
    }

    /**
     * 解析 TSV 行为日志条目。
     *
     * @param line 行
     * @return 条目；格式非法返回 null
     */
    public static LogEntry decodeEntry(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] p = line.split("\t", -1);
        if (p.length != 8) {
            return null;
        }
        try {
            long seq = Long.parseLong(p[0]);
            long time = Long.parseLong(p[1]);
            String stack = unescape(p[7]);
            return new LogEntry(seq, time, unescape(p[2]), unescape(p[3]),
                    LogLevel.valueOf(p[4]), unescape(p[5]), unescape(p[6]),
                    stack.isEmpty() ? null : stack);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 批量解码。
     *
     * @param body 多行文本
     * @return 条目列表（跳过非法行）
     */
    public static List<LogEntry> decodeBatch(String body) {
        List<LogEntry> out = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return out;
        }
        for (String line : body.split("\n", -1)) {
            LogEntry e = decodeEntry(line);
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String unescape(String s) {
        if (s == null || s.indexOf('\\') < 0) {
            return s == null ? "" : s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 't' -> sb.append('\t');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case '\\' -> sb.append('\\');
                    default -> {
                        sb.append('\\');
                        sb.append(n);
                    }
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
