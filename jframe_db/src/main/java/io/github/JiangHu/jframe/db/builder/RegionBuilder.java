package io.github.JiangHu.jframe.db.builder;

import io.github.JiangHu.jframe.db.DbAPI;
import io.github.JiangHu.jframe.db.RegionHandle;
import io.github.JiangHu.jframe.db.spi.RedisStore;
import io.github.JiangHu.jframe.serialize.exception.DataException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 数据区域构建器 — {@code db.region(名, redis, 死期)} 的链式入口。
 * <pre>{@code
 * RegionHandle pool = db.region("匹配池", redis, Duration.ofMinutes(5))
 *         .claim();                     // 多插件/多服可同时认领同一区域
 * }</pre>
 * 区域死期必填：无人认领（全部 release 或全部崩溃）→ 无人续死期 → 整片自动蒸发，
 * 这是「没人要了就回收」的崩溃免疫实现，框架不代填默认值。
 */
public final class RegionBuilder {

    private static final AtomicLong COUNTER = new AtomicLong();

    private final DbAPI db;
    private final RedisStore redis;
    private final String name;
    private final long ttlSeconds;
    private String label = "default";

    /** 【内部 API】由 DbAPI.region 工厂调用。 */
    public RegionBuilder(DbAPI db, RedisStore redis, String name, long ttlSeconds) {
        this.db = db;
        this.redis = redis;
        this.name = name;
        this.ttlSeconds = ttlSeconds;
    }

    /** 持有者标签（区分同进程多个认领方；持有者 ID = 实例ID:标签）。 */
    public RegionBuilder label(String label) {
        this.label = label;
        return this;
    }

    /**
     * 认领并返回区域句柄（启动心跳续期任务）。
     *
     * @return 区域句柄
     */
    public RegionHandle claim() {
        String holderId = db.instanceId() + ":" + label + ":" + COUNTER.incrementAndGet();
        RegionHandle handle = new RegionHandle(redis, db.gson(), name, ttlSeconds,
                holderId, db.executors());
        handle.claim();
        db.trackRegion(redis, handle);
        return handle;
    }

    /** 校验区域死期（必填为正）。【内部 API】 */
    public static long requireTtl(String name, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new DataException("区域 " + name + " 的死期（TTL）必填且必须为正——" +
                    "无人续死期时区域整片自动蒸发，这是『没人要了就回收』的崩溃免疫实现");
        }
        return Math.max(1, ttl.toSeconds());
    }
}
