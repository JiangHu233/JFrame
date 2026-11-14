package io.github.JiangHu.jframe.logger;

import io.github.JiangHu.jframe.logger.core.LogStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JLoggerTest {

    private static LoggerAPI newApi(LoggerConfig config) {
        Supplier<String> id = () -> "server-test";
        return new LoggerAPI(
                new LogStore(config.perSourceLimit(), config.globalLimit()),
                config, null, id);
    }

    @Test
    void defaultSilentWritesToStoreOnly() {
        LoggerAPI api = newApi(LoggerConfig.defaults(null));
        JLogger log = api.getLogger("MyPlugin");
        log.info("hello");
        log.error("boom", new IllegalStateException("cause-x"));

        List<LogEntry> out = api.entriesForTest("MyPlugin");
        assertEquals(2, out.size());
        assertEquals(LogLevel.INFO, out.get(0).level());
        assertEquals("hello", out.get(0).message());
        assertEquals("server-test", out.get(0).server());
        assertEquals("main", out.get(0).thread());
        assertNotNull(out.get(1).stackTrace());
        assertTrue(out.get(1).stackTrace().contains("IllegalStateException: cause-x"));
        assertTrue(out.get(1).stackTrace().contains("Caused by") || out.get(1).stackTrace().contains("\tat"));
    }

    @Test
    void levelFilterDropsLowerLevels() {
        LoggerAPI api = newApi(LoggerConfig.defaults(null));
        JLogger log = api.getLogger("p");
        log.setLevel(LogLevel.WARN);
        log.debug("d");
        log.info("i");
        log.warn("w");
        log.error("e");
        List<LogEntry> out = api.entriesForTest("p");
        assertEquals(2, out.size());
        assertEquals("w", out.get(0).message());
        assertEquals("e", out.get(1).message());
    }

    @Test
    void consoleMirrorToleratesMissingServer() {
        LoggerAPI api = newApi(LoggerConfig.defaults(null));
        JLogger log = api.getLogger("p");
        log.setConsoleMirror(true);
        assertTrue(log.consoleMirror());
        // Server.getInstance() 为 null（测试环境）：镜像静默跳过，不抛异常，条目正常入库
        log.info("mirrored-in-test");
        assertEquals(1, api.entriesForTest("p").size());
    }

    @Test
    void sameSourceCachesSameInstance() {
        LoggerAPI api = newApi(LoggerConfig.defaults(null));
        assertSame(api.getLogger("p"), api.getLogger("p"));
        assertEquals(List.of("p"), api.sources());
    }

    @Test
    void nullMessageNormalizedToEmpty() {
        LoggerAPI api = newApi(LoggerConfig.defaults(null));
        JLogger log = api.getLogger("p");
        log.info(null);
        List<LogEntry> out = api.entriesForTest("p");
        assertEquals("", out.get(0).message());
        assertNull(out.get(0).stackTrace());
    }

    @Test
    void seqAdvancesAcrossLoggers() {
        LoggerAPI api = newApi(LoggerConfig.defaults(null));
        JLogger a = api.getLogger("a");
        JLogger b = api.getLogger("b");
        a.info("1");
        b.info("2");
        a.info("3");
        List<LogEntry> as = api.entriesForTest("a");
        List<LogEntry> bs = api.entriesForTest("b");
        assertTrue(bs.get(0).seq() > as.get(0).seq());
        assertTrue(as.get(1).seq() > bs.get(0).seq());
    }
}
