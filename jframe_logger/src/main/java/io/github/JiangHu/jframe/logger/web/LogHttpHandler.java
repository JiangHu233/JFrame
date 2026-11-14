package io.github.JiangHu.jframe.logger.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.JiangHu.jframe.logger.LogEntry;
import io.github.JiangHu.jframe.logger.LogLevel;
import io.github.JiangHu.jframe.logger.core.LogStore;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * host 侧 HTTP 路由 —— 页面、查询 API、SSE、agent 管理端点。
 * <p>
 * agent 端点（{@code /api/agent/*}）仅接受<b>回环来源</b>请求；
 * {@code /api/ping} 用于本机探测识别（不要求 token、不返回数据）；
 * 其余 API 在配置 token 非空时要求 {@code ?token=} 或 Bearer 头。
 */
public final class LogHttpHandler implements HttpHandler {

    private static final String INDEX_RESOURCE = "/logger-web/index.html";

    private final LogStore store;
    private final HostAgentManager agents;
    private final SseBroadcaster broadcaster;
    private final String token;
    private final Supplier<String> ownServerId;

    private final byte[] indexHtml;

    public LogHttpHandler(LogStore store, HostAgentManager agents, SseBroadcaster broadcaster,
                          String token, Supplier<String> ownServerId) {
        this.store = store;
        this.agents = agents;
        this.broadcaster = broadcaster;
        this.token = token == null ? "" : token;
        this.ownServerId = ownServerId;
        this.indexHtml = loadIndex();
    }

    private static byte[] loadIndex() {
        try (InputStream in = LogHttpHandler.class.getResourceAsStream(INDEX_RESOURCE)) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (IOException ignored) {
        }
        return ("<html><body><h3>JFrame logger web UI missing (" + INDEX_RESOURCE + ")</h3></body></html>")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method) && AgentProtocol.PING_PATH.equals(path)) {
                send(exchange, 200, "application/json; charset=utf-8",
                        "{\"app\":\"jframe-logger\",\"version\":1}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.startsWith("/api/agent/")) {
                handleAgent(exchange, path);
                return;
            }
            if ("GET".equalsIgnoreCase(method) && path.startsWith("/api/")) {
                handleApi(exchange, path);
                return;
            }
            if ("GET".equalsIgnoreCase(method) && isPagePath(path)) {
                send(exchange, 200, "text/html; charset=utf-8", indexHtml);
                return;
            }
            send(exchange, 404, "text/plain; charset=utf-8",
                    "not found".getBytes(StandardCharsets.UTF_8));
        } finally {
            exchange.close();
        }
    }

    private static boolean isPagePath(String path) {
        if ("/".equals(path) || "/index.html".equals(path)) {
            return true;
        }
        return path.startsWith("/s/") && path.length() > 3;
    }

    private void handleAgent(HttpExchange exchange, String path) throws IOException {
        if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
            send(exchange, 403, "text/plain; charset=utf-8",
                    "agent endpoints are loopback-only".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Map<String, String> q = parseQuery(exchange);
        if (!tokenOk(q, exchange)) {
            send(exchange, 403, "text/plain; charset=utf-8", "invalid token".getBytes(StandardCharsets.UTF_8));
            return;
        }
        switch (path) {
            case "/api/agent/register" -> {
                String serverId = q.get("serverId");
                String own = ownServerId.get();
                String agentId = (own != null && own.equals(serverId))
                        ? null
                        : agents.register(serverId);
                if (agentId == null) {
                    send(exchange, 409, "text/plain; charset=utf-8",
                            "duplicate server-id".getBytes(StandardCharsets.UTF_8));
                } else {
                    send(exchange, 200, "text/plain; charset=utf-8",
                            agentId.getBytes(StandardCharsets.UTF_8));
                }
            }
            case "/api/agent/logs" -> {
                String registeredServer = agents.serverOf(q.get("agentId"));
                if (registeredServer == null) {
                    send(exchange, 410, "text/plain; charset=utf-8",
                            "unknown agent".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                List<LogEntry> entries = AgentProtocol.decodeBatch(body);
                for (LogEntry e : entries) {
                    store.append(e.withServer(registeredServer));
                }
                send(exchange, 200, "text/plain; charset=utf-8", "OK".getBytes(StandardCharsets.UTF_8));
            }
            case "/api/agent/heartbeat" -> {
                boolean ok = agents.heartbeat(q.get("agentId"));
                send(exchange, ok ? 200 : 410, "text/plain; charset=utf-8",
                        (ok ? "OK" : "unknown agent").getBytes(StandardCharsets.UTF_8));
            }
            case "/api/agent/unregister" -> {
                agents.unregister(q.get("agentId"));
                send(exchange, 200, "text/plain; charset=utf-8", "OK".getBytes(StandardCharsets.UTF_8));
            }
            default -> send(exchange, 404, "text/plain; charset=utf-8",
                    "not found".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void handleApi(HttpExchange exchange, String path) throws IOException {
        Map<String, String> q = parseQuery(exchange);
        if (!tokenOk(q, exchange)) {
            send(exchange, 403, "text/plain; charset=utf-8", "invalid token".getBytes(StandardCharsets.UTF_8));
            return;
        }
        switch (path) {
            case "/api/servers" -> send(exchange, 200, "application/json; charset=utf-8",
                    Json.servers(store.servers()).getBytes(StandardCharsets.UTF_8));
            case "/api/sources" -> {
                String server = q.getOrDefault("server", "all");
                send(exchange, 200, "application/json; charset=utf-8",
                        Json.sources(store.sources(server)).getBytes(StandardCharsets.UTF_8));
            }
            case "/api/logs" -> {
                String server = q.getOrDefault("server", "all");
                String source = q.get("source");
                LogLevel level = LogLevel.parse(q.get("level"), null);
                long afterSeq = longVal(q.get("afterSeq"));
                long afterTime = longVal(q.get("afterTime"));
                int limit = (int) longVal(q.get("limit"));
                List<LogEntry> entries = store.snapshot(server, source, level, afterSeq, afterTime, limit);
                send(exchange, 200, "application/json; charset=utf-8",
                        Json.entries(entries).getBytes(StandardCharsets.UTF_8));
            }
            case "/api/stream" -> {
                String server = q.getOrDefault("server", "all");
                SseBroadcaster.Filters filters = new SseBroadcaster.Filters(
                        server, q.get("source"), LogLevel.parse(q.get("level"), null));
                broadcaster.serve(exchange, filters, longVal(q.get("afterSeq")), longVal(q.get("afterTime")));
            }
            default -> send(exchange, 404, "text/plain; charset=utf-8",
                    "not found".getBytes(StandardCharsets.UTF_8));
        }
    }

    private boolean tokenOk(Map<String, String> q, HttpExchange exchange) {
        if (token.isBlank()) {
            return true;
        }
        String given = q.get("token");
        if (given == null) {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                given = auth.substring("Bearer ".length());
            }
        }
        return token.equals(given);
    }

    private static long longVal(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Map<String, String> parseQuery(HttpExchange exchange) {
        Map<String, String> out = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (var os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
