package io.github.JiangHu.jframe.db.cache;

import io.github.JiangHu.jframe.db.support.DbExecutors;
import io.github.JiangHu.jframe.db.support.InMemoryRedisStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Roster} 实例名册测试：join/left 维护、双名册互见。
 */
@DisplayName("实例名册（Roster）测试")
class RosterTest {

    @Test
    @DisplayName("两名册互相可见；left 后除名")
    void joinLeftPropagation() throws Exception {
        InMemoryRedisStore redis = new InMemoryRedisStore();
        DbExecutors executors = new DbExecutors();
        Roster r1 = new Roster(UUID.randomUUID().toString(), "lobby-1");
        Roster r2 = new Roster(UUID.randomUUID().toString(), "lobby-2");

        r1.start(redis, executors);
        // 内存 store 的 publish 为同步直投；r1 自身计入
        assertEquals(1, r1.activeCount());

        r2.start(redis, executors);
        assertEquals(2, r1.activeCount());
        assertEquals(2, r2.activeCount());

        r2.stop();
        assertEquals(1, r1.activeCount());
        assertTrue(r1.isLastMember());

        r1.stop();
        executors.shutdown();
    }

    @Test
    @DisplayName("LocalCache：无期限默认 + 容量驱逐")
    void localCacheEviction() {
        LocalCache cache = new LocalCache(-1, 3);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");
        assertEquals("1", cache.<String>get("a")); // a 访问序最新
        cache.put("d", "4"); // 超容量，逐最久未访问（b）
        assertNull(cache.<String>get("b"));
        assertNotNull(cache.<String>get("a"));
        assertNotNull(cache.<String>get("d"));
        cache.invalidate("a");
        assertNull(cache.<String>get("a"));
        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("LocalCache：TTL 到期惰性失效")
    void localCacheTtl() throws Exception {
        LocalCache cache = new LocalCache(50, -1);
        cache.put("a", "1");
        assertNotNull(cache.<String>get("a"));
        Thread.sleep(80);
        assertNull(cache.<String>get("a"));
    }
}
