package io.github.JiangHu.jframe.logger.core;

import io.github.JiangHu.jframe.logger.LogEntry;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 可选落盘sink —— 以 {@link LogStore.Listener} 身份把<b>本 JVM 自身</b>的日志
 * 按来源、按天写入 {@code <rootDir>/<来源>/<yyyy-MM-dd>.log}（agent 上报到 host 的
 * 条目不在本 JVM 落盘范围；host 也不为 agent 落盘）。
 * <p>
 * 写入走单守护线程 + BufferedWriter；每次回调 flush 一批；{@link #shutdown()} 强制
 * flush + close。按天滚动（写入前校验日期，跨天关闭旧 writer 开新文件）。
 */
public final class PersistSink implements LogStore.Listener {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Path rootDir;
    private final Supplier<String> ownServerId;
    private final ZoneId zone;
    private final ExecutorService io;

    private BufferedWriter writer;
    private String currentDay;
    private Path currentFile;
    private volatile boolean closed;

    public PersistSink(Path rootDir, Supplier<String> ownServerId) {
        this(rootDir, ownServerId, ZoneId.systemDefault());
    }

    public PersistSink(Path rootDir, Supplier<String> ownServerId, ZoneId zone) {
        this.rootDir = rootDir;
        this.ownServerId = ownServerId;
        this.zone = zone;
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicLong n = new AtomicLong();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "jframe-logger-persist-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.io = Executors.newSingleThreadExecutor(factory);
    }

    @Override
    public void onAppend(LogEntry entry) {
        if (closed || entry == null) {
            return;
        }
        String own = ownServerId.get();
        if (own != null && !own.equals(entry.server())) {
            return; // 只落本 JVM 自身日志
        }
        io.execute(() -> write(entry));
    }

    private void write(LogEntry e) {
        try {
            String day = DAY.format(Instant.ofEpochMilli(e.timeMillis()).atZone(zone));
            Path file = rootDir.resolve(sanitize(e.source())).resolve(day + ".log");
            if (writer == null || !day.equals(currentDay) || !file.equals(currentFile)) {
                rotate(file, day);
            }
            String time = TIME.format(Instant.ofEpochMilli(e.timeMillis()).atZone(zone));
            writer.write("[" + time + "] [" + e.level() + "] [" + e.thread() + "] " + e.message());
            writer.newLine();
            if (e.stackTrace() != null) {
                for (String line : e.stackTrace().split("\n")) {
                    writer.write("    " + line);
                    writer.newLine();
                }
            }
            writer.flush();
        } catch (Exception ignored) {
            // 落盘失败不影响内存日志库
        }
    }

    private synchronized void rotate(Path file, String day) throws IOException {
        closeWriter();
        Files.createDirectories(file.getParent());
        this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        this.currentDay = day;
        this.currentFile = file;
    }

    private synchronized void closeWriter() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException ignored) {
            }
            writer = null;
        }
    }

    /**
     * 停止落盘：排空队列并强制 flush + close。
     */
    public void shutdown() {
        closed = true;
        io.shutdown();
        try {
            io.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeWriter();
    }

    public static String sanitize(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            sb.append(((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') ? c : '-');
        }
        if (sb.isEmpty()) {
            sb.append("default");
        }
        return sb.toString();
    }
}
