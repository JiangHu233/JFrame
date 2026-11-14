package io.github.JiangHu.jframe.logger.web;

import io.github.JiangHu.jframe.logger.LogEntry;
import io.github.JiangHu.jframe.logger.core.LogStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * agent 侧转发器 —— 本 JVM 挂靠到本机 host 后：
 * 注册 → 补发当前缓冲快照 → 持续批量推送新日志 + 心跳。
 * <p>
 * 推送走有界队列 + 单守护线程（200ms 或攒 50 条一批），队列满丢旧计数，
 * <b>不阻塞业务日志线程</b>。推送/心跳失败置 {@code unhealthy}，
 * 由 {@link WebRuntime} 的恢复线程负责重连或接管 host。
 */
public final class AgentForwarder implements LogStore.Listener {

    private static final int QUEUE_CAPACITY = 4096;
    private static final int BATCH_MAX = 50;
    private static final long BATCH_WAIT_MILLIS = 200;
    private static final long HEARTBEAT_MILLIS = 5_000;

    private final int hostPort;
    private final String serverId;
    private final String token;
    private final LogStore store;
    private final Runnable onFailure;

    private final BlockingQueue<LogEntry> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong forwarded = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    private volatile boolean running;
    private volatile boolean healthy;
    private volatile String agentId;
    private Thread forwardThread;
    private ScheduledExecutorService heartbeat;

    public AgentForwarder(int hostPort, String serverId, String token, LogStore store, Runnable onFailure) {
        this.hostPort = hostPort;
        this.serverId = serverId;
        this.token = token;
        this.store = store;
        this.onFailure = onFailure;
    }

    /**
     * 注册并开始推送。
     *
     * @throws IllegalStateException 注册失败（重复 serverId / 连接失败 / token 拒绝）
     */
    public void start() {
        String id = register(null);
        this.agentId = id;
        sendSnapshot(id);
        this.running = true;
        this.healthy = true;
        store.addListener(this);

        forwardThread = new Thread(this::forwardLoop, "jframe-logger-agent-forward");
        forwardThread.setDaemon(true);
        forwardThread.start();

        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jframe-logger-agent-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleWithFixedDelay(this::beat, HEARTBEAT_MILLIS, HEARTBEAT_MILLIS, TimeUnit.MILLISECONDS);
    }

    private String register(String oldAgentId) {
        try {
            if (oldAgentId != null) {
                tryPost("/api/agent/unregister?agentId=" + AgentProtocol.urlEncode(oldAgentId), "");
            }
            AgentProtocol.HttpResult r = AgentProtocol.post(hostPort,
                    "/api/agent/register?serverId=" + AgentProtocol.urlEncode(serverId)
                            + AgentProtocol.queryTail(token, ""),
                    "", token);
            if (r.status() == 409) {
                throw new IllegalStateException("server-id \"" + serverId + "\" 已被本机其它实例注册，请在 config.yml 的 logger.web.server-id 中配置唯一标识");
            }
            if (!r.ok() || r.body().isBlank()) {
                throw new IllegalStateException("host 注册失败（HTTP " + r.status() + (r.body().isBlank() ? "" : ": " + r.body()) + "）");
            }
            return r.body().trim();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("无法连接本机 host 127.0.0.1:" + hostPort + "（" + e.getMessage() + "）", e);
        }
    }

    private void sendSnapshot(String id) {
        List<LogEntry> snapshot = store.snapshot(serverId, null, null, 0, 0, LogStore.MAX_QUERY_LIMIT);
        if (snapshot.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder(snapshot.size() * 128);
        for (LogEntry e : snapshot) {
            sb.append(AgentProtocol.encodeEntry(e)).append('\n');
        }
        try {
            AgentProtocol.HttpResult r = AgentProtocol.post(hostPort,
                    "/api/agent/logs?agentId=" + AgentProtocol.urlEncode(id) + AgentProtocol.queryTail(token, ""),
                    sb.toString(), token);
            if (!r.ok()) {
                throw new IllegalStateException("快照补发失败（HTTP " + r.status() + "）");
            }
            forwarded.addAndGet(snapshot.size());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("快照补送失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void onAppend(LogEntry entry) {
        if (!running || entry == null || !serverId.equals(entry.server())) {
            return;
        }
        if (!queue.offer(entry)) {
            queue.poll();
            queue.offer(entry);
            dropped.incrementAndGet();
        }
    }

    private void forwardLoop() {
        while (running) {
            try {
                if (!healthy) {
                    Thread.sleep(1000);
                    continue;
                }
                LogEntry first = queue.poll(BATCH_WAIT_MILLIS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<LogEntry> batch = new ArrayList<>(BATCH_MAX);
                batch.add(first);
                queue.drainTo(batch, BATCH_MAX - 1);
                StringBuilder sb = new StringBuilder(batch.size() * 128);
                for (LogEntry e : batch) {
                    sb.append(AgentProtocol.encodeEntry(e)).append('\n');
                }
                AgentProtocol.HttpResult r = AgentProtocol.post(hostPort,
                        "/api/agent/logs?agentId=" + AgentProtocol.urlEncode(agentId)
                                + AgentProtocol.queryTail(token, ""),
                        sb.toString(), token);
                if (r.ok()) {
                    forwarded.addAndGet(batch.size());
                } else if (r.status() == 410) {
                    healthy = false;
                } else {
                    healthy = false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                healthy = false;
            }
        }
    }

    private void beat() {
        if (!running || !healthy) {
            return;
        }
        try {
            AgentProtocol.HttpResult r = tryPost(
                    "/api/agent/heartbeat?agentId=" + AgentProtocol.urlEncode(agentId)
                            + AgentProtocol.queryTail(token, ""), "");
            if (r != null && !r.ok()) {
                healthy = false;
                kickFailure();
            }
        } catch (Exception e) {
            healthy = false;
            kickFailure();
        }
    }

    private AgentProtocol.HttpResult tryPost(String pathQuery, String body) {
        try {
            return AgentProtocol.post(hostPort, pathQuery, body, token);
        } catch (Exception e) {
            return null;
        }
    }

    private void kickFailure() {
        if (onFailure != null) {
            try {
                onFailure.run();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 重连（host 可能重启或被其它实例接管）：先注销旧 id，再注册并补发快照。
     *
     * @return 是否重连成功
     */
    public synchronized boolean reconnect() {
        if (!running) {
            return false;
        }
        try {
            String id = register(agentId);
            this.agentId = id;
            sendSnapshot(id);
            healthy = true;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 停止转发（可选向 host 注销）。
     *
     * @param sendUnregister true = 尽力发送注销请求
     */
    public void stop(boolean sendUnregister) {
        running = false;
        healthy = false;
        store.removeListener(this);
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
        if (forwardThread != null) {
            forwardThread.interrupt();
        }
        if (sendUnregister && agentId != null) {
            tryPost("/api/agent/unregister?agentId=" + AgentProtocol.urlEncode(agentId), "");
        }
    }

    /**
     * @return 推送是否健康
     */
    public boolean isHealthy() {
        return running && healthy;
    }

    /**
     * @return host 端口
     */
    public int hostPort() {
        return hostPort;
    }

    /**
     * @return 已成功推送条数
     */
    public long forwardedCount() {
        return forwarded.get();
    }

    /**
     * @return 因队列满被丢弃条数
     */
    public long droppedCount() {
        return dropped.get();
    }
}
