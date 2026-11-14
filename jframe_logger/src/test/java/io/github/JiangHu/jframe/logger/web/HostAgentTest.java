package io.github.JiangHu.jframe.logger.web;

import io.github.JiangHu.jframe.logger.LogEntry;
import io.github.JiangHu.jframe.logger.LogLevel;
import io.github.JiangHu.jframe.logger.LoggerConfig;
import io.github.JiangHu.jframe.logger.WebStartResult;
import io.github.JiangHu.jframe.logger.core.LogStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同进程模拟「同 IP 多面板」：两个 WebRuntime（各自独立 LogStore/配置），
 * A 绑定端口为 host，B 探测到后挂靠为 agent 并转发日志。
 */
class HostAgentTest {

    private final List<WebRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        runtimes.forEach(WebRuntime::stop);
    }

    static LoggerConfig cfg(String serverId, int port, String token) {
        return new LoggerConfig(5000, 20000, false, "127.0.0.1", port, serverId, token, null);
    }

    static LogStore storeOf(WebRuntime r) {
        try {
            var f = WebRuntime.class.getDeclaredField("store");
            f.setAccessible(true);
            return (LogStore) f.get(r);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static void append(LogStore store, String server, String source, String message) {
        store.append(new LogEntry(store.nextSeq(), System.currentTimeMillis(), server, source,
                LogLevel.INFO, "test", message, null));
    }

    private static void awaitTrue(String what, long timeoutMillis, java.util.function.BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("等待超时: " + what);
    }

    @Test
    @Timeout(30)
    void agentAttachesForwardsAndAppearsInAggregate() throws Exception {
        int port = WebRuntimeTest.freePort();
        WebRuntime host = new WebRuntime(new LogStore(5000, 20000), cfg("srv-a", port, ""), () -> "srv-a");
        runtimes.add(host);
        WebStartResult hr = host.start(port);
        assertTrue(hr.success() && hr.role() == WebRole.HOST, hr.message());

        append(storeOf(host), "srv-a", "PA", "host-own-entry");

        WebRuntime agent = new WebRuntime(new LogStore(5000, 20000), cfg("srv-b", port, ""), () -> "srv-b");
        runtimes.add(agent);
        WebStartResult ar = agent.start(port);
        assertTrue(ar.success(), ar.message());
        assertEquals(WebRole.AGENT, ar.role());
        assertTrue(ar.url().endsWith("/s/srv-b"), ar.url());

        append(storeOf(agent), "srv-b", "PB", "agent-entry-1");
        append(storeOf(agent), "srv-b", "PB", "agent-entry-2");

        awaitTrue("agent 日志转发到 host", 10_000, () -> {
            try {
                AgentProtocol.HttpResult r = AgentProtocol.get(port,
                        "/api/logs?server=srv-b&limit=100", java.time.Duration.ofSeconds(3));
                return r.ok() && r.body().contains("agent-entry-1") && r.body().contains("agent-entry-2");
            } catch (Exception e) {
                return false;
            }
        });

        AgentProtocol.HttpResult servers = AgentProtocol.get(port, "/api/servers", java.time.Duration.ofSeconds(3));
        assertTrue(servers.body().contains("srv-a") && servers.body().contains("srv-b"));

        AgentProtocol.HttpResult aggregate = AgentProtocol.get(port, "/api/logs?limit=200", java.time.Duration.ofSeconds(3));
        assertTrue(aggregate.body().contains("host-own-entry"));
        assertTrue(aggregate.body().contains("agent-entry-1"));

        assertTrue(agent.stop());
        assertEquals(WebRole.OFF, agent.role());
    }

    @Test
    @Timeout(30)
    void duplicateServerIdRejected() throws Exception {
        int port = WebRuntimeTest.freePort();
        WebRuntime host = new WebRuntime(new LogStore(5000, 20000), cfg("srv-a", port, ""), () -> "srv-a");
        runtimes.add(host);
        assertTrue(host.start(port).success());

        WebRuntime dup = new WebRuntime(new LogStore(5000, 20000), cfg("srv-a", port, ""), () -> "srv-a");
        runtimes.add(dup);
        WebStartResult r = dup.start(port);
        assertFalse(r.success());
        assertTrue(r.message().contains("已被"), r.message());
        assertEquals(WebRole.OFF, dup.role());
    }

    @Test
    @Timeout(60)
    void agentTakesOverWhenHostDies() throws Exception {
        int port = WebRuntimeTest.freePort();
        WebRuntime host = new WebRuntime(new LogStore(5000, 20000), cfg("srv-a", port, ""), () -> "srv-a");
        WebRuntime agent = new WebRuntime(new LogStore(5000, 20000), cfg("srv-b", port, ""), () -> "srv-b");
        runtimes.add(host);
        runtimes.add(agent);

        assertTrue(host.start(port).success());
        assertTrue(agent.start(port).success());
        assertEquals(WebRole.AGENT, agent.role());

        append(storeOf(agent), "srv-b", "PB", "before-takeover");

        host.stop();

        awaitTrue("agent 接管 host 端口", 45_000, () -> agent.role() == WebRole.HOST);
        assertEquals(port, agent.port());

        awaitTrue("接管后 ping 可用", 5_000, () ->
                AgentProtocol.probe(port, "") == AgentProtocol.ProbeResult.JFRAME);

        append(storeOf(agent), "srv-b", "PB", "after-takeover");
        awaitTrue("接管后新日志可查", 5_000, () -> {
            try {
                AgentProtocol.HttpResult r = AgentProtocol.get(port,
                        "/api/logs?server=srv-b&limit=100", java.time.Duration.ofSeconds(3));
                return r.ok() && r.body().contains("after-takeover");
            } catch (Exception e) {
                return false;
            }
        });
    }
}
