package io.github.JiangHu.jframe.logger.web;

import io.github.JiangHu.jframe.logger.LogEntry;
import io.github.JiangHu.jframe.logger.LogLevel;
import io.github.JiangHu.jframe.logger.LoggerConfig;
import io.github.JiangHu.jframe.logger.WebStartResult;
import io.github.JiangHu.jframe.logger.core.LogStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebRuntimeTest {

    private final List<WebRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        runtimes.forEach(WebRuntime::stop);
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static LoggerConfig cfg(String serverId, int port, String token) {
        return new LoggerConfig(5000, 20000, false, "127.0.0.1", port, serverId, token, null);
    }

    WebRuntime newRuntime(String serverId, int port, String token) {
        WebRuntime r = new WebRuntime(new LogStore(5000, 20000), cfg(serverId, port, token), () -> serverId);
        runtimes.add(r);
        return r;
    }

    static LogStore runtimeStore(WebRuntime r) {
        try {
            var f = WebRuntime.class.getDeclaredField("store");
            f.setAccessible(true);
            return (LogStore) f.get(r);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static AgentProtocol.HttpResult get(int port, String pathQuery) throws Exception {
        return AgentProtocol.get(port, pathQuery, Duration.ofSeconds(5));
    }

    static void append(LogStore store, String server, String source, String message) {
        store.append(new LogEntry(store.nextSeq(), System.currentTimeMillis(), server, source,
                LogLevel.INFO, "test", message, null));
    }

    @Test
    void hostBindsEphemeralAndServesApisAndPages() throws Exception {
        WebRuntime rt = newRuntime("srv-a", 18080, "");
        WebStartResult r = rt.start(0);
        assertTrue(r.success());
        assertEquals(WebRole.HOST, r.role());
        int port = rt.port();
        assertTrue(port > 0);

        AgentProtocol.HttpResult ping = get(port, "/api/ping");
        assertTrue(ping.ok() && ping.body().contains(AgentProtocol.PING_MAGIC));

        LogStore store = runtimeStore(rt);
        append(store, "srv-a", "P1", "host-entry-1");
        append(store, "srv-a", "P1", "host-entry-2");

        AgentProtocol.HttpResult servers = get(port, "/api/servers");
        assertTrue(servers.ok() && servers.body().contains("srv-a"));

        AgentProtocol.HttpResult logs = get(port, "/api/logs?server=srv-a&source=P1");
        assertTrue(logs.ok());
        assertTrue(logs.body().contains("host-entry-1"));
        assertTrue(logs.body().contains("host-entry-2"));

        AgentProtocol.HttpResult all = get(port, "/api/logs");
        assertTrue(all.ok() && all.body().contains("host-entry-1"));

        AgentProtocol.HttpResult sources = get(port, "/api/sources?server=all");
        assertTrue(sources.ok() && sources.body().contains("P1"));

        AgentProtocol.HttpResult page = get(port, "/");
        assertEquals(200, page.status());
        assertTrue(page.body().contains("JFrame"));

        AgentProtocol.HttpResult sub = get(port, "/s/srv-a/");
        assertEquals(200, sub.status());

        AgentProtocol.HttpResult notFound = get(port, "/api/whatever");
        assertEquals(404, notFound.status());

        assertTrue(rt.stop());
        assertFalse(rt.isRunning());
        assertEquals(WebRole.OFF, rt.role());
        assertThrows(Exception.class, () -> get(port, "/api/ping"));

        WebStartResult again = rt.start(freePort());
        assertTrue(again.success());
        assertEquals(WebRole.HOST, again.role());
    }

    @Test
    void tokenProtectsApisButNotPing() throws Exception {
        WebRuntime rt = newRuntime("srv-a", 18080, "s3cret");
        assertTrue(rt.start(0).success());
        int port = rt.port();

        assertEquals(200, get(port, "/api/ping").status());
        assertEquals(403, get(port, "/api/servers").status());
        assertEquals(200, get(port, "/api/servers?token=s3cret").status());

        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(new java.net.URI("http://127.0.0.1:" + port + "/api/servers"))
                .header("Authorization", "Bearer s3cret")
                .GET().build();
        assertEquals(200, client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void explicitPortOccupiedByNonJframeFailsWithHint() throws Exception {
        int port = freePort();
        try (ServerSocket blocker = new ServerSocket(port)) {
            WebRuntime rt = newRuntime("srv-a", 18080, "");
            WebStartResult r = rt.start(port);
            assertFalse(r.success());
            assertTrue(r.message().contains("非 JFrame logger"));
            assertTrue(r.message().contains("auto"));
            assertEquals(WebRole.OFF, rt.role());
        }
    }

    @Test
    void sseSendsHistoryThenLiveEvents() throws Exception {
        WebRuntime rt = newRuntime("srv-a", 18080, "");
        assertTrue(rt.start(0).success());
        int port = rt.port();
        LogStore store = runtimeStore(rt);
        append(store, "srv-a", "P1", "history-1");

        URL url = new URL("http://127.0.0.1:" + port + "/api/stream?server=all&level=DEBUG");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setReadTimeout(10_000);
        conn.setConnectTimeout(5_000);
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        try {
            String history = readDataLine(reader);
            assertTrue(history.contains("history-1"));

            append(store, "srv-a", "P1", "live-1");
            String live = readDataLine(reader);
            assertTrue(live.contains("live-1"));
        } finally {
            conn.disconnect();
        }
    }

    private static String readDataLine(BufferedReader reader) throws Exception {
        String line;
        long deadline = System.currentTimeMillis() + 10_000;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("data: ")) {
                return line.substring("data: ".length());
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("SSE 超时未收到数据");
            }
        }
        throw new AssertionError("SSE 连接提前关闭");
    }

    @Test
    void statusReflectsRoles() throws Exception {
        WebRuntime rt = newRuntime("srv-a", 18080, "");
        assertTrue(rt.status().contains("未运行"));
        rt.start(0);
        assertTrue(rt.status().contains("HOST"));
        rt.stop();
        assertTrue(rt.status().contains("未运行"));
    }
}
