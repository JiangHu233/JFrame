package io.github.JiangHu.jframe.logger;

import io.github.JiangHu.jframe.logger.core.LogStore;
import io.github.JiangHu.jframe.logger.core.PersistSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistSinkTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @TempDir
    Path tempDir;

    private static long at(int year, int month, int day, int hour, int min, int sec) {
        return ZonedDateTime.of(LocalDateTime.of(year, month, day, hour, min, sec), ZONE).toInstant().toEpochMilli();
    }

    @Test
    void writesOwnEntriesWithFormatAndFlush() throws Exception {
        LogStore store = new LogStore(100, 100);
        PersistSink sink = new PersistSink(tempDir.resolve("logs"), () -> "me", ZONE);
        store.addListener(sink);
        store.append(new LogEntry(store.nextSeq(), at(2026, 11, 13, 10, 0, 0), "me", "Econ", LogLevel.INFO, "main", "玩家存款 +100", null));
        store.append(new LogEntry(store.nextSeq(), at(2026, 11, 13, 10, 0, 1), "me", "Econ", LogLevel.ERROR, "async",
                "扣款失败", "java.lang.IllegalStateException: boom\n\tat Foo.bar(Foo.java:1)"));
        store.append(new LogEntry(store.nextSeq(), at(2026, 11, 13, 10, 0, 2), "other-jframe", "Econ", LogLevel.INFO, "main", "不应落盘", null));

        sink.shutdown();

        Path file = tempDir.resolve("logs").resolve("Econ").resolve("2026-11-13.log");
        assertTrue(Files.exists(file), "日志文件应存在: " + file);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(4, lines.size());
        assertTrue(lines.get(0).startsWith("[10:00:00.000] [INFO] [main] 玩家存款 +100"));
        assertTrue(lines.get(1).startsWith("[10:00:01.000] [ERROR] [async] 扣款失败"));
        assertEquals("    java.lang.IllegalStateException: boom", lines.get(2));
        assertEquals("    \tat Foo.bar(Foo.java:1)", lines.get(3));
        assertFalse(String.join("\n", lines).contains("不应落盘"), "agent/他服条目不应在本机落盘");
    }

    @Test
    void dayRolloverCreatesNewFile() throws Exception {
        LogStore store = new LogStore(100, 100);
        PersistSink sink = new PersistSink(tempDir.resolve("logs"), () -> "me", ZONE);
        store.addListener(sink);

        store.append(new LogEntry(store.nextSeq(), at(2026, 11, 13, 23, 59, 59), "me", "P", LogLevel.INFO, "main", "day1", null));
        store.append(new LogEntry(store.nextSeq(), at(2026, 11, 14, 0, 0, 1), "me", "P", LogLevel.INFO, "main", "day2", null));
        sink.shutdown();

        assertTrue(Files.exists(tempDir.resolve("logs").resolve("P").resolve("2026-11-13.log")));
        Path day2 = tempDir.resolve("logs").resolve("P").resolve("2026-11-14.log");
        assertTrue(Files.exists(day2));
        assertTrue(Files.readString(day2).contains("day2"));
    }

    @Test
    void sanitizesSourceNameForPath() {
        assertEquals("My-Plugin-1", PersistSink.sanitize("My Plugin\\1"));
        assertEquals("---", PersistSink.sanitize("///"));
    }
}
