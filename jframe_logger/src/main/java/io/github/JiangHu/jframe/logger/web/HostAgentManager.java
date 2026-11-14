package io.github.JiangHu.jframe.logger.web;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * host 侧 agent 注册表 —— 登记/心跳/剔除挂靠实例。
 * <p>
 * 重复 {@code serverId} 注册被拒绝（409 语义）；心跳超时（15s）由清扫线程剔除。
 */
public final class HostAgentManager {

    /** 挂靠实例。 */
    public static final class Agent {
        private final String agentId;
        private final String serverId;
        private volatile long lastHeartbeat;

        Agent(String agentId, String serverId, long now) {
            this.agentId = agentId;
            this.serverId = serverId;
            this.lastHeartbeat = now;
        }

        public String agentId() {
            return agentId;
        }

        public String serverId() {
            return serverId;
        }

        void heartbeat(long now) {
            this.lastHeartbeat = now;
        }
    }

    static final long HEARTBEAT_TIMEOUT_MILLIS = 15_000;
    private static final long SWEEP_INTERVAL_MILLIS = 5_000;

    private final Map<String, Agent> byId = new ConcurrentHashMap<>();
    private final Map<String, String> serverToId = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jframe-logger-agent-sweeper");
        t.setDaemon(true);
        return t;
    });

    /**
     * 启动心跳清扫。
     */
    public void start() {
        sweeper.scheduleWithFixedDelay(this::sweep,
                SWEEP_INTERVAL_MILLIS, SWEEP_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * 注册挂靠实例。
     *
     * @param serverId 子服标识
     * @return agentId；serverId 已被占用时返回 null
     */
    public synchronized String register(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return null;
        }
        String existId = serverToId.get(serverId);
        if (existId != null && byId.containsKey(existId)) {
            return null;
        }
        String agentId = UUID.randomUUID().toString();
        Agent agent = new Agent(agentId, serverId, System.currentTimeMillis());
        byId.put(agentId, agent);
        serverToId.put(serverId, agentId);
        return agentId;
    }

    /**
     * 心跳。
     *
     * @param agentId agentId
     * @return 是否为已注册 agent
     */
    public boolean heartbeat(String agentId) {
        Agent agent = agentId == null ? null : byId.get(agentId);
        if (agent == null) {
            return false;
        }
        agent.heartbeat(System.currentTimeMillis());
        return true;
    }

    /**
     * 注销。
     *
     * @param agentId agentId
     */
    public void unregister(String agentId) {
        Agent agent = agentId == null ? null : byId.remove(agentId);
        if (agent != null) {
            serverToId.remove(agent.serverId(), agentId);
        }
    }

    /**
     * 查询 agentId 对应的注册子服标识。
     *
     * @param agentId agentId
     * @return 子服标识；未知 agent 返回 null
     */
    public String serverOf(String agentId) {
        Agent agent = agentId == null ? null : byId.get(agentId);
        return agent == null ? null : agent.serverId();
    }

    /**
     * @return 已挂靠实例数
     */
    public int agentCount() {
        return byId.size();
    }

    private synchronized void sweep() {
        long deadline = System.currentTimeMillis() - HEARTBEAT_TIMEOUT_MILLIS;
        for (Agent agent : byId.values()) {
            if (agent.lastHeartbeat < deadline) {
                byId.remove(agent.agentId());
                serverToId.remove(agent.serverId(), agent.agentId());
            }
        }
    }

    /**
     * 停止清扫并清空注册表。
     */
    public void stop() {
        sweeper.shutdownNow();
        byId.clear();
        serverToId.clear();
    }
}
