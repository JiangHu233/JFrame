package io.github.JiangHu.jframe.logger.web;

import io.github.JiangHu.jframe.logger.LoggerConfig;
import io.github.JiangHu.jframe.logger.WebStartResult;
import io.github.JiangHu.jframe.logger.core.LogStore;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Web 运行协调器 —— HOST / AGENT / OFF 状态机。
 * <p>
 * <b>启动流程</b>（手动指令触发，模块加载不自动开启端口）：
 * <ol>
 *   <li>探测目标端口：是 JFrame logger host → 挂靠为 agent（本机统一入口）</li>
 *   <li>端口空闲 → 自己绑定为 host（0 端口 = OS 原子分配未占用端口）</li>
 *   <li>被非 JFrame 程序占用 → 失败并建议 auto</li>
 *   <li>绑定竞争失败 → 重探一次，是 JFrame 则转 agent</li>
 * </ol>
 * <b>故障接管</b>：agent 推送/心跳不健康后由恢复线程每 5s 重探 —— host 端口空闲则
 * 自己绑定接管（其它实例下轮重新挂靠到新 host），发现新 host 则重连/重新挂靠，
 * 直至恢复或被 stop。窗口期日志保留在本地缓冲，恢复后随快照补发。
 */
public final class WebRuntime {

    private final LogStore store;
    private final LoggerConfig config;
    private final Supplier<String> ownServerId;

    private volatile WebRole role = WebRole.OFF;
    private volatile int boundPort = -1;
    private volatile int lastHostPort = -1;

    private WebLogServer hostServer;
    private HostAgentManager hostAgents;
    private SseBroadcaster broadcaster;
    private AgentForwarder forwarder;
    private Thread recoveryThread;

    public WebRuntime(LogStore store, LoggerConfig config, Supplier<String> ownServerId) {
        this.store = store;
        this.config = config;
        this.ownServerId = ownServerId;
    }

    /**
     * 当前角色。
     *
     * @return 角色
     */
    public WebRole role() {
        return role;
    }

    /**
     * 相关端口（HOST = 实际监听端口；AGENT = host 端口；OFF = -1）。
     *
     * @return 端口
     */
    public int port() {
        if (role == WebRole.HOST) {
            return boundPort;
        }
        AgentForwarder f = forwarder;
        return f != null ? f.hostPort() : (lastHostPort > 0 ? lastHostPort : -1);
    }

    /**
     * 是否在运行（HOST 或 AGENT）。
     *
     * @return true = 运行中
     */
    public boolean isRunning() {
        return role != WebRole.OFF;
    }

    /**
     * 启动 Web 展示。
     *
     * @param port 0 = auto；正数 = 指定端口
     * @return 启动结果
     */
    public synchronized WebStartResult start(int port) {
        if (role != WebRole.OFF) {
            return WebStartResult.fail("已在运行（角色 " + role + "），请先 /jlog stop");
        }
        if (port > 0) {
            return startExplicit(port);
        }
        // auto：先探测 config 端口上的本机 host，保持统一入口
        AgentProtocol.ProbeResult p = AgentProtocol.probe(config.port(), config.token());
        if (p == AgentProtocol.ProbeResult.JFRAME) {
            return attach(config.port());
        }
        return bindHost(0);
    }

    private WebStartResult startExplicit(int port) {
        AgentProtocol.ProbeResult p = AgentProtocol.probe(port, config.token());
        switch (p) {
            case JFRAME:
                return attach(port);
            case OCCUPIED_OTHER:
                return WebStartResult.fail("端口 " + port + " 被非 JFrame logger 程序占用，可改用 /jlog start auto");
            case REFUSED:
            default:
                try {
                    return bindHost(port);
                } catch (Exception e) {
                    // 竞争窗口：探测后、绑定前被占 —— 重探一次，是 JFrame 则转 agent
                    if (AgentProtocol.probe(port, config.token()) == AgentProtocol.ProbeResult.JFRAME) {
                        return attach(port);
                    }
                    return WebStartResult.fail(
                            "绑定端口 " + port + " 失败（" + e.getMessage() + "），可改用 /jlog start auto");
                }
        }
    }

    private synchronized WebStartResult bindHost(int port) {
        HostAgentManager agents = new HostAgentManager();
        SseBroadcaster caster = new SseBroadcaster(store);
        WebLogServer server = new WebLogServer();
        LogHttpHandler handler = new LogHttpHandler(store, agents, caster, config.token(), ownServerId);
        int actual;
        try {
            actual = server.start(config.bind(), port, handler);
        } catch (Exception e) {
            server.stop();
            throw new IllegalStateException("绑定 " + config.bind() + ":" + port + " 失败: " + e.getMessage(), e);
        }
        agents.start();
        store.addListener(caster);
        this.hostServer = server;
        this.hostAgents = agents;
        this.broadcaster = caster;
        this.boundPort = actual;
        this.role = WebRole.HOST;
        String shownHost = "0.0.0.0".equals(config.bind()) ? "<服务器IP>" : config.bind();
        String url = "http://" + shownHost + ":" + actual + "/";
        String extra = port == 0 ? "（OS 自动分配端口）" : "";
        return WebStartResult.host(url, "已监听 " + config.bind() + ":" + actual + extra);
    }

    private synchronized WebStartResult attach(int hostPort) {
        AgentForwarder f = new AgentForwarder(hostPort, ownServerId.get(), config.token(), store, null);
        try {
            f.start();
        } catch (Exception e) {
            return WebStartResult.fail("挂靠 127.0.0.1:" + hostPort + " 失败（" + e.getMessage() + "）");
        }
        this.forwarder = f;
        this.lastHostPort = hostPort;
        this.role = WebRole.AGENT;
        startRecoveryLoop();
        String url = "http://127.0.0.1:" + hostPort + "/s/" + ownServerId.get();
        return WebStartResult.agent(url, "已挂靠本机 host 127.0.0.1:" + hostPort);
    }

    private void startRecoveryLoop() {
        recoveryThread = new Thread(() -> {
            while (role == WebRole.AGENT) {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    return;
                }
                AgentForwarder f = forwarder;
                if (role == WebRole.AGENT && (f == null || !f.isHealthy())) {
                    try {
                        recoverStep();
                    } catch (Throwable ignored) {
                        // 恢复失败下一轮重试
                    }
                }
            }
        }, "jframe-logger-agent-recovery");
        recoveryThread.setDaemon(true);
        recoveryThread.start();
    }

    private synchronized void recoverStep() {
        int target = lastHostPort;
        if (target <= 0) {
            return;
        }
        AgentProtocol.ProbeResult p = AgentProtocol.probe(target, config.token());
        switch (p) {
            case REFUSED -> {
                // host 已死亡：停转发，尝试接管绑定；失败则保持 AGENT + 无转发，下一轮重试
                if (forwarder != null) {
                    forwarder.stop(false);
                    forwarder = null;
                }
                try {
                    bindHost(target);
                } catch (Exception ignored) {
                    // 下一轮继续尝试接管或重挂靠
                }
            }
            case JFRAME -> {
                if (forwarder == null) {
                    try {
                        AgentForwarder f = new AgentForwarder(target, ownServerId.get(), config.token(), store, null);
                        f.start();
                        forwarder = f;
                    } catch (Exception ignored) {
                        // 下一轮重试
                    }
                } else {
                    forwarder.reconnect();
                }
            }
            case OCCUPIED_OTHER -> {
                // 端口被非 JFrame 程序占用：等待下一轮（可能用户改配置/释放）
            }
        }
    }

    /**
     * 停止（按角色）。
     *
     * @return 未运行时 false，否则 true
     */
    public synchronized boolean stop() {
        if (role == WebRole.OFF) {
            return false;
        }
        if (role == WebRole.HOST) {
            if (broadcaster != null) {
                store.removeListener(broadcaster);
                broadcaster.close();
            }
            if (hostAgents != null) {
                hostAgents.stop();
            }
            if (hostServer != null) {
                hostServer.stop();
            }
            hostServer = null;
            hostAgents = null;
            broadcaster = null;
            boundPort = -1;
        } else {
            if (forwarder != null) {
                forwarder.stop(true);
                forwarder = null;
            }
        }
        if (recoveryThread != null) {
            recoveryThread.interrupt();
            recoveryThread = null;
        }
        role = WebRole.OFF;
        return true;
    }

    /**
     * 状态文本（/jlog status 展示）。
     *
     * @return 多行状态
     */
    public synchronized String status() {
        String own = ownServerId.get();
        Map<String, LogStore.ServerStats> stats = store.servers();
        int entries = stats.values().stream().mapToInt(LogStore.ServerStats::entries).sum();
        int sources = stats.values().stream().mapToInt(LogStore.ServerStats::sources).sum();
        String head = "子服标识: " + own + " | 已记录 " + entries + " 条 / " + sources + " 个来源（含挂靠实例）";
        return switch (role) {
            case OFF -> "未运行（用 /jlog start 启动；模块加载不自动开启端口）\n" + head;
            case HOST -> "HOST（本机监听）: " + config.bind() + ":" + boundPort
                    + " | 挂靠实例 " + (hostAgents == null ? 0 : hostAgents.agentCount())
                    + " | SSE 连接 " + (broadcaster == null ? 0 : broadcaster.subscriberCount())
                    + "\n" + head;
            case AGENT -> {
                AgentForwarder f = forwarder;
                if (f == null) {
                    yield "AGENT（host 丢失，恢复线程接管/重挂靠中）\n" + head;
                }
                yield "AGENT（挂靠本机 host 127.0.0.1:" + f.hostPort() + "）| 子路由 /s/" + own
                        + " | 已推送 " + f.forwardedCount() + " 条 / 丢弃 " + f.droppedCount() + " 条"
                        + (f.isHealthy() ? "" : " | 推送异常，恢复线程重连中")
                        + "\n" + head;
            }
        };
    }
}
