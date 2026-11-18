package io.github.JiangHu.jframe.db;

import io.github.JiangHu.jframe.db.support.InMemoryRedisStore;
import io.github.JiangHu.jframe.serialize.exception.DataException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RegionHandle} 单键 JSON 数据区域测试：共享认领、put/get、
 * 末位关灯、无人续死期整片蒸发、崩溃残留清空重来、release 后句柄失效。
 */
@DisplayName("数据区域（RegionHandle）测试")
class RegionTest {

    record Room(String host, int players) {
    }

    @Test
    @DisplayName("claim + put/get/keys/size/remove + release 基本语义")
    void basicLifecycle() {
        DbAPI db = new DbAPI();
        InMemoryRedisStore redis = new InMemoryRedisStore();
        db.registerRedis(redis);

        RegionHandle pool = db.region("匹配池", redis, Duration.ofMinutes(5)).claim();
        pool.put("room:123", new Room("Steve", 4));
        pool.put("room:456", new Room("Alex", 2));

        assertEquals(2, pool.size());
        assertTrue(pool.keys().contains("room:123"));
        Room room = pool.get("room:123", Room.class);
        assertEquals("Steve", room.host());
        assertEquals(4, room.players());

        pool.remove("room:123");
        assertEquals(1, pool.size());
        assertNull(pool.get("room:123", Room.class));

        pool.release();
        assertThrows(DataException.class, () -> pool.put("room:789", new Room("x", 1)));
    }

    @Test
    @DisplayName("多插件/多服共享认领同一区域；末位 release 立即删键，前位 release 保留")
    void sharedClaimAndLastRelease() {
        DbAPI db = new DbAPI();
        InMemoryRedisStore redis = new InMemoryRedisStore();
        db.registerRedis(redis);

        RegionHandle first = db.region("共享池", redis, Duration.ofMinutes(5)).label("A").claim();
        first.put("key", "v");
        RegionHandle second = db.region("共享池", redis, Duration.ofMinutes(5)).label("B").claim();

        // 前位 release：仍有他人持有，区域保留
        first.release();
        assertEquals("v", second.get("key", String.class));

        // 末位 release：holders 空 → 键立即删除
        second.release();
        assertNull(redis.get("__jframe_db__:region:共享池"));
    }

    @Test
    @DisplayName("无人续死期 → 整片自动蒸发（没人要了就回收）")
    void evaporateWhenNobodyRenews() {
        DbAPI db = new DbAPI();
        InMemoryRedisStore redis = new InMemoryRedisStore();
        db.registerRedis(redis);

        RegionHandle pool = db.region("临时池", redis, Duration.ofMinutes(5)).claim();
        pool.put("data", "x");
        pool.release(); // 正常关灯会删键；这里再模拟崩溃场景：直接停止心跳不 release

        // 模拟崩溃：另一个实例 claim 后不再续命（release 且跳过删键很难模拟，
        // 改为直接验证 TTL 判定：写入后推进时间超过死期 → 蒸发）
        RegionHandle crashed = db.region("崩溃池", redis, Duration.ofSeconds(10)).claim();
        crashed.put("a", "1");
        redis.advanceTime(11);
        assertNull(redis.get("__jframe_db__:region:崩溃池"), "无人续死期，区域应整片蒸发");
        crashed.release();
    }

    @Test
    @DisplayName("崩溃残留（holders 全超时）→ 新认领清空重来")
    void staleHoldersReset() {
        DbAPI db = new DbAPI();
        InMemoryRedisStore redis = new InMemoryRedisStore();
        db.registerRedis(redis);

        // 直接植入「远古心跳」的残留区域：持有者早已崩溃，数据仍在
        String raw = "{\"holders\":{\"ghost\":1},\"data\":{\"old\":\"旧数据\"}}";
        redis.set("__jframe_db__:region:残留池", raw, 300);

        RegionHandle fresh = db.region("残留池", redis, Duration.ofMinutes(5)).claim();
        assertNull(fresh.get("old", String.class), "崩溃残留应被清空（持有者心跳远古）");
        fresh.put("new", "新数据");
        assertEquals("新数据", fresh.get("new", String.class));
        fresh.release();
    }
}
