package io.github.JiangHu.jframe.logger.core;

import io.github.JiangHu.jframe.logger.LogEntry;
import io.github.JiangHu.jframe.logger.LogLevel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统一日志库 —— 内存环形缓冲 + 查询 + 追加监听。
 * <p>
 * 分区结构：{@code server → source → 有界双端队列}（每分区容量 {@code perSourceLimit}，
 * 满即淘汰最旧），另有全局队列（容量 {@code globalLimit}）支撑聚合视图的有界收集。
 * <p>
 * <b>聚合排序</b>：跨子服条目按 {@code timeMillis → server → seq} 稳定归并
 * （同机时钟一致；seq 仅在同 JVM 内单调）。单子服查询按 {@code seq} 升序。
 * <p>
 * 线程安全：内部 synchronized（日志频率低，简单锁足够）；监听器回调在锁外执行，
 * 回调方（SSE 广播 / 落盘 / agent 转发）必须非阻塞。
 */
public final class LogStore {

    /** 追加监听器（在追加线程上被调用，必须非阻塞）。 */
    public interface Listener {
        void onAppend(LogEntry entry);
    }

    /** 子服统计。 */
    public record ServerStats(String server, int sources, int entries, long latestTime) {
    }

    /** 单来源统计。 */
    public record SourceStats(String server, String source, int entries, long latestTime) {
    }

    private static final Comparator<LogEntry> MERGE_ORDER =
            Comparator.comparingLong(LogEntry::timeMillis)
                    .thenComparing(LogEntry::server)
                    .thenComparingLong(LogEntry::seq);

    public static final int MAX_QUERY_LIMIT = 2000;

    private final int perSourceLimit;
    private final int globalLimit;
    private final AtomicLong seqGen = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final Map<String, Map<String, ArrayDeque<LogEntry>>> byServer = new HashMap<>();
    private final ArrayDeque<LogEntry> global = new ArrayDeque<>();

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public LogStore(int perSourceLimit, int globalLimit) {
        this.perSourceLimit = Math.max(1, perSourceLimit);
        this.globalLimit = Math.max(1, globalLimit);
    }

    /**
     * 分配下一个全局序号（本 JVM 单调）。
     *
     * @return 序号
     */
    public long nextSeq() {
        return seqGen.incrementAndGet();
    }

    /**
     * 注册追加监听器。
     *
     * @param listener 监听器
     */
    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * 移除追加监听器。
     *
     * @param listener 监听器
     */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * 追加一条日志（闭环淘汰 + 通知监听器）。库已关闭时静默丢弃。
     *
     * @param entry 日志条目
     */
    public void append(LogEntry entry) {
        if (entry == null || closed.get()) {
            return;
        }
        synchronized (this) {
            byServer.computeIfAbsent(entry.server(), k -> new HashMap<>())
                    .computeIfAbsent(entry.source(), k -> new ArrayDeque<>())
                    .addLast(entry);
            ArrayDeque<LogEntry> sourceQ = byServer.get(entry.server()).get(entry.source());
            while (sourceQ.size() > perSourceLimit) {
                sourceQ.removeFirst();
            }
            global.addLast(entry);
            while (global.size() > globalLimit) {
                global.removeFirst();
            }
        }
        for (Listener l : listeners) {
            try {
                l.onAppend(entry);
            } catch (Throwable ignored) {
                // 单个监听器异常不影响其他监听器与调用方
            }
        }
    }

    /**
     * 查询日志。
     * <p>
     * {@code server} 为 null/blank/"all" 时做<b>聚合查询</b>（按 timeMillis 归并排序，
     * 分页游标用 {@code afterTime}）；否则做<b>单子服查询</b>（按 seq 升序，分页游标用 {@code afterSeq}）。
     *
     * @param server    子服 id / "all"
     * @param source    来源过滤（null/blank = 全部来源）
     * @param minLevel  级别下限（null = 不过滤）
     * @param afterSeq  单子服分页游标（返回 seq 严格大于者；&lt;=0 忽略）
     * @param afterTime 聚合分页游标（返回 timeMillis 严格大于者；&lt;=0 忽略）
     * @param limit     条数上限（clamp 到 1..MAX_QUERY_LIMIT）
     * @return 日志列表（升序）
     */
    public List<LogEntry> snapshot(String server, String source, LogLevel minLevel,
                                   long afterSeq, long afterTime, int limit) {
        int cap = Math.min(Math.max(limit <= 0 ? 500 : limit, 1), MAX_QUERY_LIMIT);
        boolean aggregate = server == null || server.isBlank() || "all".equalsIgnoreCase(server);

        List<LogEntry> out = new ArrayList<>();
        if (aggregate) {
            List<LogEntry> pool;
            synchronized (this) {
                pool = new ArrayList<>(global);
            }
            for (LogEntry e : pool) {
                if (minLevel != null && !e.level().atLeast(minLevel)) continue;
                if (source != null && !source.isBlank() && !e.source().equals(source)) continue;
                if (afterTime > 0 && e.timeMillis() <= afterTime) continue;
                out.add(e);
            }
            out.sort(MERGE_ORDER);
            if (out.size() > cap) {
                out = new ArrayList<>(out.subList(out.size() - cap, out.size()));
            }
            return out;
        }

        Map<String, ArrayDeque<LogEntry>> sources;
        synchronized (this) {
            Map<String, ArrayDeque<LogEntry>> got = byServer.get(server);
            if (got == null) {
                return out;
            }
            sources = new HashMap<>(got);
        }
        for (Map.Entry<String, ArrayDeque<LogEntry>> en : sources.entrySet()) {
            if (source != null && !source.isBlank() && !en.getKey().equals(source)) continue;
            List<LogEntry> copy;
            synchronized (this) {
                copy = new ArrayList<>(en.getValue());
            }
            for (LogEntry e : copy) {
                if (minLevel != null && !e.level().atLeast(minLevel)) continue;
                if (afterSeq > 0 && e.seq() <= afterSeq) continue;
                out.add(e);
            }
        }
        out.sort(Comparator.comparingLong(LogEntry::seq));
        if (out.size() > cap) {
            out = new ArrayList<>(out.subList(out.size() - cap, out.size()));
        }
        return out;
    }

    /**
     * 全部子服统计。
     *
     * @return server → 统计（按 server 名排序）
     */
    public Map<String, ServerStats> servers() {
        Map<String, ServerStats> out = new LinkedHashMap<>();
        Map<String, Map<String, ArrayDeque<LogEntry>>> snap;
        synchronized (this) {
            snap = new HashMap<>();
            for (Map.Entry<String, Map<String, ArrayDeque<LogEntry>>> e : byServer.entrySet()) {
                snap.put(e.getKey(), new HashMap<>(e.getValue()));
            }
        }
        List<String> ids = new ArrayList<>(snap.keySet());
        ids.sort(String::compareTo);
        for (String id : ids) {
            int entries = 0;
            long latest = 0;
            for (ArrayDeque<LogEntry> q : snap.get(id).values()) {
                List<LogEntry> copy;
                synchronized (this) {
                    copy = new ArrayList<>(q);
                }
                entries += copy.size();
                for (LogEntry e : copy) {
                    latest = Math.max(latest, e.timeMillis());
                }
            }
            out.put(id, new ServerStats(id, snap.get(id).size(), entries, latest));
        }
        return out;
    }

    /**
     * 指定子服（或聚合）的来源统计。
     *
     * @param server 子服 id / "all"
     * @return 来源统计列表
     */
    public List<SourceStats> sources(String server) {
        Map<String, Map<String, ArrayDeque<LogEntry>>> snap;
        synchronized (this) {
            snap = new HashMap<>();
            for (Map.Entry<String, Map<String, ArrayDeque<LogEntry>>> e : byServer.entrySet()) {
                snap.put(e.getKey(), new HashMap<>(e.getValue()));
            }
        }
        List<SourceStats> out = new ArrayList<>();
        boolean aggregate = server == null || server.isBlank() || "all".equalsIgnoreCase(server);
        for (Map.Entry<String, Map<String, ArrayDeque<LogEntry>>> se : snap.entrySet()) {
            if (!aggregate && !se.getKey().equals(server)) continue;
            for (Map.Entry<String, ArrayDeque<LogEntry>> src : se.getValue().entrySet()) {
                List<LogEntry> copy;
                synchronized (this) {
                    copy = new ArrayList<>(src.getValue());
                }
                long latest = 0;
                for (LogEntry e : copy) {
                    latest = Math.max(latest, e.timeMillis());
                }
                out.add(new SourceStats(se.getKey(), src.getKey(), copy.size(), latest));
            }
        }
        out.sort(Comparator.comparing(SourceStats::server).thenComparing(SourceStats::source));
        return out;
    }

    /**
     * 关闭日志库：清空监听器，后续 append 静默丢弃。
     */
    public void close() {
        closed.set(true);
        listeners.clear();
        synchronized (this) {
            byServer.clear();
            global.clear();
        }
    }
}
