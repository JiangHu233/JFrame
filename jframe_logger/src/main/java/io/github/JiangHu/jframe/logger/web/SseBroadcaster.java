package io.github.JiangHu.jframe.logger.web;

import com.sun.net.httpserver.HttpExchange;
import io.github.JiangHu.jframe.logger.LogEntry;
import io.github.JiangHu.jframe.logger.LogLevel;
import io.github.JiangHu.jframe.logger.core.LogStore;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 实时推送 —— 以 {@link LogStore.Listener} 身份接收新日志，
 * 按订阅者过滤器非阻塞投递（订阅队列满则丢旧并计数，不拖慢业务日志线程）。
 * <p>
 * 每个订阅连接占用 host 线程池的一个守护线程（handler 线程内联服务：
 * 先补发历史快照，再循环写实时事件，30s 无事件发 keepalive 注释行）。
 */
public final class SseBroadcaster implements LogStore.Listener {

    /** 订阅过滤器。 */
    public record Filters(String server, String source, LogLevel minLevel) {
        boolean matches(LogEntry e) {
            if (server != null && !server.isBlank() && !"all".equalsIgnoreCase(server)
                    && !server.equals(e.server())) {
                return false;
            }
            if (source != null && !source.isBlank() && !source.equals(e.source())) {
                return false;
            }
            return e.level().atLeast(minLevel);
        }
    }

    private static final int SUB_QUEUE = 1024;
    private static final long KEEPALIVE_MILLIS = 30_000;

    private final LogStore store;
    private final List<Sub> subs = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public SseBroadcaster(LogStore store) {
        this.store = store;
    }

    private static final class Sub {
        final Filters filters;
        final BlockingQueue<LogEntry> queue = new ArrayBlockingQueue<>(SUB_QUEUE);
        final AtomicLong dropped = new AtomicLong();

        Sub(Filters filters) {
            this.filters = filters;
        }
    }

    @Override
    public void onAppend(LogEntry entry) {
        if (closed || entry == null) {
            return;
        }
        for (Sub s : subs) {
            if (!s.filters.matches(entry)) {
                continue;
            }
            if (!s.queue.offer(entry)) {
                s.queue.poll();
                s.queue.offer(entry);
                s.dropped.incrementAndGet();
            }
        }
    }

    /**
     * 在当前 handler 线程上服务一个 SSE 连接（阻塞直至客户端断开）。
     *
     * @param exchange HTTP 交换
     * @param filters  过滤器
     * @param afterSeq 单子服补发游标（&lt;=0 忽略）
     * @param afterTime 聚合补发游标（&lt;=0 忽略）
     * @throws IOException 写失败（客户端断开）
     */
    public void serve(HttpExchange exchange, Filters filters, long afterSeq, long afterTime)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, 0);
        OutputStream os = exchange.getResponseBody();
        Sub sub = new Sub(filters);
        subs.add(sub);
        try {
            List<LogEntry> history = store.snapshot(
                    filters.server(), filters.source(), filters.minLevel(),
                    afterSeq, afterTime, LogStore.MAX_QUERY_LIMIT);
            for (LogEntry e : history) {
                writeEvent(os, e);
            }
            os.flush();
            while (!closed) {
                LogEntry e;
                try {
                    e = sub.queue.poll(KEEPALIVE_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (e == null) {
                    os.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    continue;
                }
                writeEvent(os, e);
                os.flush();
            }
        } finally {
            subs.remove(sub);
            try {
                os.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void writeEvent(OutputStream os, LogEntry e) throws IOException {
        os.write("data: ".getBytes(StandardCharsets.UTF_8));
        os.write(Json.entry(e).getBytes(StandardCharsets.UTF_8));
        os.write("\n\n".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 关闭广播：清空订阅者（各连接写线程随后因流关闭退出）。
     */
    public void close() {
        closed = true;
        subs.clear();
    }

    /**
     * @return 当前订阅连接数
     */
    public int subscriberCount() {
        return subs.size();
    }
}
