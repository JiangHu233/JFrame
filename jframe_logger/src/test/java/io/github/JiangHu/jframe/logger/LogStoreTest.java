package io.github.JiangHu.jframe.logger;

import io.github.JiangHu.jframe.logger.core.LogStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogStoreTest {

    private static LogEntry entry(LogStore store, String server, String source, LogLevel level,
                                  long time, String message) {
        return new LogEntry(store.nextSeq(), time, server, source, level, "main", message, null);
    }

    @Test
    void partitionEvictsOldestWhenFull() {
        LogStore store = new LogStore(4, 64);
        for (int i = 0; i < 10; i++) {
            store.append(entry(store, "s1", "p1", LogLevel.INFO, i, "m" + i));
        }
        List<LogEntry> out = store.snapshot("s1", "p1", null, 0, 0, 100);
        assertEquals(4, out.size());
        assertEquals("m6", out.get(0).message());
        assertEquals("m9", out.get(3).message());
    }

    @Test
    void singleServerSnapshotOrderedBySeq() {
        LogStore store = new LogStore(100, 200);
        for (int i = 0; i < 5; i++) {
            store.append(entry(store, "s1", "a", LogLevel.INFO, 100 - i, "a" + i));
        }
        for (int i = 0; i < 5; i++) {
            store.append(entry(store, "s1", "b", LogLevel.INFO, 50 + i, "b" + i));
        }
        List<LogEntry> out = store.snapshot("s1", null, null, 0, 0, 100);
        assertEquals(10, out.size());
        for (int i = 1; i < out.size(); i++) {
            assertTrue(out.get(i - 1).seq() < out.get(i).seq());
        }
    }

    @Test
    void aggregateMergesByTimeThenServer() {
        LogStore store = new LogStore(100, 200);
        store.append(entry(store, "srv-b", "p", LogLevel.INFO, 100, "b-1"));
        store.append(entry(store, "srv-a", "p", LogLevel.INFO, 100, "a-1"));
        store.append(entry(store, "srv-a", "p", LogLevel.INFO, 50, "a-0"));
        store.append(entry(store, "srv-b", "p", LogLevel.INFO, 200, "b-2"));
        List<LogEntry> out = store.snapshot("all", null, null, 0, 0, 100);
        assertEquals(4, out.size());
        assertEquals("a-0", out.get(0).message());
        assertEquals("a-1", out.get(1).message());
        assertEquals("b-1", out.get(2).message());
        assertEquals("b-2", out.get(3).message());
    }

    @Test
    void filtersByLevelSourceAndCursors() {
        LogStore store = new LogStore(100, 200);
        store.append(entry(store, "s", "p1", LogLevel.DEBUG, 10, "d1"));
        store.append(entry(store, "s", "p1", LogLevel.INFO, 20, "i1"));
        store.append(entry(store, "s", "p1", LogLevel.WARN, 30, "w1"));
        store.append(entry(store, "s", "p2", LogLevel.ERROR, 40, "e2"));

        assertEquals(3, store.snapshot("s", "p1", null, 0, 0, 100).size());
        assertEquals(3, store.snapshot("s", null, LogLevel.INFO, 0, 0, 100).size());
        assertEquals(1, store.snapshot("s", null, LogLevel.ERROR, 0, 0, 100).size());

        List<LogEntry> page = store.snapshot("s", "p1", null, 2, 0, 100);
        assertEquals(1, page.size());
        assertEquals("w1", page.get(0).message());

        assertEquals(2, store.snapshot("all", null, null, 0, 20, 100).size());
    }

    @Test
    void serversAndSourcesStats() {
        LogStore store = new LogStore(100, 200);
        store.append(entry(store, "s1", "a", LogLevel.INFO, 10, "x"));
        store.append(entry(store, "s1", "b", LogLevel.INFO, 20, "x"));
        store.append(entry(store, "s2", "a", LogLevel.INFO, 30, "x"));

        Map<String, LogStore.ServerStats> servers = store.servers();
        assertEquals(2, servers.size());
        assertEquals(2, servers.get("s1").sources());
        assertEquals(2, servers.get("s1").entries());
        assertEquals(30, servers.get("s2").latestTime());

        assertEquals(2, store.sources("s1").size());
        assertEquals(3, store.sources("all").size());
    }

    @Test
    void seqIsMonotonicUnderConcurrency() throws InterruptedException {
        LogStore store = new LogStore(10_000, 20_000);
        int threads = 4;
        int per = 400;
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicBoolean ascending = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            final String source = "p" + t;
            pool.execute(() -> {
                long prev = 0;
                try {
                    for (int i = 0; i < per; i++) {
                        long seq = store.nextSeq();
                        if (seq <= prev) {
                            ascending.set(false);
                        }
                        prev = seq;
                        store.append(entry(store, "s", source, LogLevel.INFO, i, "m"));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertTrue(ascending.get());
        assertEquals(threads * per, store.snapshot("s", null, null, 0, 0, LogStore.MAX_QUERY_LIMIT).size());
    }

    @Test
    void closeDropsFurtherAppends() {
        LogStore store = new LogStore(10, 10);
        store.append(entry(store, "s", "p", LogLevel.INFO, 1, "x"));
        store.close();
        store.append(entry(store, "s", "p", LogLevel.INFO, 2, "y"));
        assertTrue(store.snapshot("s", null, null, 0, 0, 10).isEmpty());
    }

    @Test
    void listenerNotifiedAndToleratesThrowing() {
        LogStore store = new LogStore(10, 10);
        AtomicBoolean got = new AtomicBoolean(false);
        store.addListener(e -> {
            throw new RuntimeException("boom");
        });
        store.addListener(e -> got.set(true));
        store.append(entry(store, "s", "p", LogLevel.INFO, 1, "x"));
        assertTrue(got.get());
        assertFalse(store.servers().isEmpty());
    }
}
