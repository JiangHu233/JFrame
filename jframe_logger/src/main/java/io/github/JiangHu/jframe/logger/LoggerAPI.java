package io.github.JiangHu.jframe.logger;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.logger.core.LogStore;
import io.github.JiangHu.jframe.logger.core.PersistSink;
import io.github.JiangHu.jframe.logger.web.WebRole;
import io.github.JiangHu.jframe.logger.web.WebRuntime;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 统一日志模块公开服务 —— 业务插件的唯一入口。
 *
 * <h3>写入日志（默认静默，不进 MCSM 控制台）</h3>
 * <pre>{@code
 * JLogger log = jframeMain.getLoggerAPI().forPlugin(this);
 * log.info("玩家进入经济系统");
 * log.error("扣款失败: " + e.getMessage(), e);
 * log.setConsoleMirror(true);   // 可选：镜像回控制台
 * }</pre>
 *
 * <h3>Web 展示（手动指令 /jlog 启停，亦可编程调用）</h3>
 * <pre>{@code
 * api.startWeb(0);        // auto：OS 分配未占用端口
 * api.startWeb(18080);    // 指定端口；被本机其它 JFrame logger 占用时自动挂靠为 agent
 * api.stopWeb();
 * }</pre>
 */
public class LoggerAPI {

    private final LogStore store;
    private final LoggerConfig config;
    private final PersistSink persistSink;
    private final WebRuntime web;
    private final Supplier<String> ownServerId;

    private final ConcurrentMap<String, JLogger> loggers = new ConcurrentHashMap<>();
    private final LongSupplier clock = System::currentTimeMillis;

    LoggerAPI(LogStore store, LoggerConfig config, PersistSink persistSink, Supplier<String> ownServerId) {
        this.store = store;
        this.config = config;
        this.persistSink = persistSink;
        this.ownServerId = ownServerId;
        this.web = new WebRuntime(store, config, ownServerId);
    }

    /**
     * 以插件名为来源取得日志器（同名缓存同实例）。
     *
     * @param plugin 业务插件
     * @return 该插件的日志器
     */
    public JLogger forPlugin(Plugin plugin) {
        return getLogger(plugin.getName());
    }

    /**
     * 以自定义来源名取得日志器（子系统命名）。
     *
     * @param sourceName 来源名
     * @return 日志器
     */
    public JLogger getLogger(String sourceName) {
        return loggers.computeIfAbsent(sourceName, s -> new JLogger(store, s, ownServerId, clock));
    }

    /**
     * 本 JVM 已创建的日志来源（含尚未写入任何日志的）。
     *
     * @return 来源名列表（排序）
     */
    public List<String> sources() {
        return loggers.keySet().stream().sorted().toList();
    }

    List<LogEntry> entriesForTest(String source) {
        return store.snapshot(ownServerId.get(), source, null, 0, 0, 100);
    }

    /**
     * 本机（本 JVM）的子服标识。
     *
     * @return 配置覆盖值（URL 安全清洗）或默认 {@code server-<本服端口>}
     */
    public String ownServerId() {
        return ownServerId.get();
    }

    /**
     * config 中配置的默认端口（/jlog start 不带参数时使用）。
     *
     * @return 端口
     */
    public int defaultWebPort() {
        return config.port();
    }

    // -------------------- Web 端口手动控制（/jlog 指令入口） --------------------

    /**
     * 启动 Web 展示端口。
     * <p>
     * 探测目标端口：是本机 JFrame logger host 则挂靠为 agent；空闲则绑定为 host；
     * 被非 JFrame 程序占用则失败。
     *
     * @param port 0 = OS 自动分配未占用端口；正数 = 指定端口
     * @return 启动结果（角色 / 访问地址 / 失败原因）
     */
    public WebStartResult startWeb(int port) {
        return web.start(port);
    }

    /**
     * 停止（host 停监听并断开 SSE；agent 注销 host）。未运行返回 false。
     *
     * @return 是否确实停止了运行中的服务
     */
    public boolean stopWeb() {
        return web.stop();
    }

    /**
     * @return 是否运行中（HOST 或 AGENT）
     */
    public boolean isWebRunning() {
        return web.isRunning();
    }

    /**
     * @return 当前角色
     */
    public WebRole webRole() {
        return web.role();
    }

    /**
     * @return 相关端口（HOST 监听端口 / AGENT 的 host 端口；未运行 -1）
     */
    public int webPort() {
        return web.port();
    }

    /**
     * 状态文本（/jlog status）。
     *
     * @return 多行状态
     */
    public String webStatus() {
        return web.status();
    }

    /**
     * 模块关服清理入口（由 LoggerModule.shutdown 调用）。
     */
    void shutdownInternal() {
        try {
            web.stop();
        } catch (Throwable ignored) {
        }
        if (persistSink != null) {
            try {
                persistSink.shutdown();
            } catch (Throwable ignored) {
            }
        }
        store.close();
        loggers.clear();
    }

    /**
     * 计算默认子服标识（LoggerModule 构造 supplier 时使用）。
     *
     * @param config 配置
     * @return 子服标识
     */
    static String computeOwnServerId(LoggerConfig config) {
        String override = config.serverIdOverride();
        if (override != null && !override.isBlank()) {
            return sanitizeId(override.trim());
        }
        Server server = Server.getInstance();
        String port = server != null ? String.valueOf(server.getPort()) : "local";
        return "server-" + port;
    }

    static String sanitizeId(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            sb.append(((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') ? c : '-');
        }
        if (sb.isEmpty()) {
            sb.append("server");
        }
        return sb.toString();
    }
}
