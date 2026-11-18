package io.github.JiangHu.jframe.db;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.JiangHu.jframe.db.spi.RedisStore;
import io.github.JiangHu.jframe.db.support.DbConstants;
import io.github.JiangHu.jframe.serialize.exception.DataException;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 跨服共享数据池句柄（不落盘临时共享数据）— <b>单键 JSON 区域</b>。
 * <p>
 * 整个区域是 Redis 里的<b>一个键</b>：
 * <pre>
 *   __jframe_db__:region:{名} = {
 *     "holders": { "持有者ID": 最后心跳毫秒, ... },   // 多插件/多服共享认领
 *     "data":    { "字段": JSON值, ... }             // 区域数据
 *   }
 * </pre>
 * <h3>回收语义（释放权三层落地）</h3>
 * <ul>
 *   <li><b>心跳续死期</b>：认领者周期（死期/3）续命；无人认领（全部 release 或全部崩溃）
 *       → 无人续 → <b>死期到整片自动蒸发</b>——「没人要了就回收」的崩溃免疫实现</li>
 *   <li><b>末位关灯</b>：最后一个 release 发现 holders 空 → 直接删键立即回收</li>
 *   <li><b>崩溃恢复</b>：新 claim 发现 holders 全部超时（&gt; 2 心跳周期）→ 视为无主清空重来</li>
 * </ul>
 * <h3>并发写</h3>
 * <p>
 * {@code setnx} 短写锁（3 秒自解）串行化读改写；锁竞争失败短暂重试后抛
 * {@link DataException}。
 * <h3>容量边界</h3>
 * <p>
 * 单键模式适合<b>条目百级、单值 KB 级</b>的池（匹配池/房间状态/共享开关）；
 * 万条级大池会 bigkey（全量读写放大），需要时另行扩展多键模式。
 */
public final class RegionHandle {

    /** 短写锁 TTL（秒）：持有者崩溃锁自解 */
    private static final long LOCK_TTL_SECONDS = 3;

    /** 写锁重试次数 × 间隔 */
    private static final int LOCK_RETRIES = 10;
    private static final long LOCK_RETRY_SLEEP_MS = 100;

    private final RedisStore redis;
    private final Gson gson;
    private final String name;
    private final String regionKey;
    private final String lockKey;
    private final long ttlSeconds;
    private final long heartbeatMillis;
    private final String holderId;
    private final io.github.JiangHu.jframe.db.support.DbExecutors executors;

    private volatile ScheduledFuture<?> heartbeatTask;
    private final AtomicBoolean released = new AtomicBoolean(false);

    /** 【内部 API】由 RegionBuilder 装配调用。 */
    public RegionHandle(RedisStore redis, Gson gson, String name, long ttlSeconds,
                        String holderId, io.github.JiangHu.jframe.db.support.DbExecutors executors) {
        this.redis = redis;
        this.gson = gson;
        this.name = name;
        this.regionKey = DbConstants.regionKey(name);
        this.lockKey = regionKey + DbConstants.REGION_LOCK_SUFFIX;
        this.ttlSeconds = ttlSeconds;
        this.heartbeatMillis = Math.max(1, ttlSeconds * 1000 / 3);
        this.holderId = holderId;
        this.executors = executors;
    }

    /**
     * 认领区域（幂等；共享语义——多插件、多服务器可同时认领）。
     *
     * @return this
     */
    public RegionHandle claim() {
        checkAlive();
        String existing = redis.get(regionKey);
        long now = System.currentTimeMillis();
        if (existing == null) {
            JsonObject fresh = new JsonObject();
            fresh.add("holders", new JsonObject());
            fresh.getAsJsonObject("holders").addProperty(holderId, now);
            fresh.add("data", new JsonObject());
            redis.set(regionKey, gson.toJson(fresh), ttlSeconds);
        } else if (allHoldersStale(existing, now)) {
            // 崩溃残留：视为无主，清空重来
            redis.delete(regionKey);
            claim();
            return this;
        } else {
            withLock(json -> {
                json.getAsJsonObject("holders").addProperty(holderId, System.currentTimeMillis());
                writeRegion(json);
                return null;
            });
        }
        heartbeatTask = executors.scheduleFixedDelay(this::heartbeat,
                heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
        return this;
    }

    /**
     * 写入区域字段（短写锁内读改写整键）。
     *
     * @param field 字段名
     * @param value 值（经 serialize 管线序列化为 JSON；有 @SaveField 的对象按注解语义）
     */
    public void put(String field, Object value) {
        checkAlive();
        withLock(json -> {
            json.getAsJsonObject("data").add(field, gson.toJsonTree(value));
            writeRegion(json);
            return null;
        });
    }

    /**
     * 读取区域字段。
     *
     * @param field 字段名
     * @param type  值类型
     * @param <R>   值类型
     * @return 值；字段不存在返回 null
     */
    public <R> R get(String field, Class<R> type) {
        checkAlive();
        String raw = redis.get(regionKey);
        if (raw == null) {
            return null;
        }
        JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
        JsonElement element = json.getAsJsonObject("data").get(field);
        return element == null || element.isJsonNull() ? null : gson.fromJson(element, type);
    }

    /**
     * 删除区域字段。
     *
     * @param field 字段名
     */
    public void remove(String field) {
        checkAlive();
        withLock(json -> {
            json.getAsJsonObject("data").remove(field);
            writeRegion(json);
            return null;
        });
    }

    /**
     * @return 区域数据字段名集合（快照）
     */
    public Set<String> keys() {
        checkAlive();
        String raw = redis.get(regionKey);
        Set<String> keys = new LinkedHashSet<>();
        if (raw == null) {
            return keys;
        }
        for (String key : JsonParser.parseString(raw).getAsJsonObject()
                .getAsJsonObject("data").keySet()) {
            keys.add(key);
        }
        return keys;
    }

    /**
     * @return 区域数据字段数
     */
    public int size() {
        return keys().size();
    }

    /**
     * 放弃认领（幂等）。
     * <p>
     * 末位持有者发现 holders 空 → 直接删键（立即回收）；否则保留区域等死期/他人续命。
     */
    public void release() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        try {
            withLock(json -> {
                json.getAsJsonObject("holders").remove(holderId);
                if (json.getAsJsonObject("holders").isEmpty()) {
                    redis.delete(regionKey); // 末位关灯
                } else {
                    writeRegion(json);
                }
                return null;
            });
        } catch (Exception ignored) {
            // 无人续死期，到期自动蒸发——release 失败无需上抛
        }
    }

    // ==================== 内部 ====================

    private void checkAlive() {
        if (released.get()) {
            throw new DataException("区域 " + name + " 已 release，句柄失效");
        }
    }

    /** 心跳：touch 自己的 holders 条目 + 续死期 */
    private void heartbeat() {
        try {
            withLock(json -> {
                json.getAsJsonObject("holders").addProperty(holderId, System.currentTimeMillis());
                writeRegion(json);
                return null;
            });
        } catch (Exception ignored) {
            // 心跳失败下轮再试；无人续死期时区域自然蒸发（语义正确）
        }
    }

    /** 区域键所有 holders 是否全部心跳超时 */
    private boolean allHoldersStale(String raw, long now) {
        try {
            JsonObject holders = JsonParser.parseString(raw).getAsJsonObject()
                    .getAsJsonObject("holders");
            if (holders == null || holders.isEmpty()) {
                return true;
            }
            long staleThreshold = 2 * heartbeatMillis;
            for (var entry : holders.entrySet()) {
                long lastSeen = entry.getValue().getAsLong();
                if (now - lastSeen <= staleThreshold) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return true; // 结构损坏视为无主
        }
    }

    /** 短写锁内执行读改写；锁竞争失败重试，最终失败抛 DataException */
    private <R> R withLock(Function<JsonObject, R> work) {
        for (int i = 0; i < LOCK_RETRIES; i++) {
            if (redis.setnx(lockKey, holderId, LOCK_TTL_SECONDS)) {
                try {
                    String raw = redis.get(regionKey);
                    JsonObject json = (raw == null)
                            ? newRegion()
                            : JsonParser.parseString(raw).getAsJsonObject();
                    if (!json.has("holders") || !json.has("data")) {
                        json = normalize(json);
                    }
                    return work.apply(json);
                } finally {
                    try {
                        redis.delete(lockKey);
                    } catch (Exception ignored) {
                        // 锁 3 秒自解，删锁失败无害
                    }
                }
            }
            try {
                Thread.sleep(LOCK_RETRY_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DataException("区域写锁等待被中断（区域 " + name + "）", e);
            }
        }
        throw new DataException("区域写锁竞争失败（区域 " + name + "，重试 " + LOCK_RETRIES + " 次未获得锁）");
    }

    /** 写回区域并续死期 */
    private void writeRegion(JsonObject json) {
        redis.set(regionKey, gson.toJson(json), ttlSeconds);
    }

    private JsonObject newRegion() {
        JsonObject json = new JsonObject();
        json.add("holders", new JsonObject());
        json.add("data", new JsonObject());
        return json;
    }

    /** 兼容结构缺失的旧值 */
    private JsonObject normalize(JsonObject json) {
        if (!json.has("holders")) {
            json.add("holders", new JsonObject());
        }
        if (!json.has("data")) {
            json.add("data", new JsonObject());
        }
        return json;
    }

    /** 未认领且未释放的心跳任务取消（DbAPI 注销时强制调用）。【内部 API】 */
    public void forceRelease() {
        release();
    }

    /** 持有者 ID（观测/调试用） */
    String holderId() {
        return holderId;
    }
}
