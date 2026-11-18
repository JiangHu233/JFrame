package io.github.JiangHu.jframe.db.support;

import io.github.JiangHu.jframe.db.spi.RedisStore;
import io.github.JiangHu.jframe.db.spi.Subscription;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * 内存版 RedisStore 假实现 — 单元测试与 jframe_example 演示用。
 * <p>
 * 实现说明：TTL 以「当前时钟 + 死期毫秒」的条目模拟（默认取系统时钟）；
 * pub/sub 为同实例监听列表直投。测试可用 {@link #advanceTime(long)} 推进
 * 虚拟时钟验证过期行为。
 */
public class InMemoryRedisStore implements RedisStore {

    /** 键值条目：值 + 过期毫秒时间戳（Long.MAX_VALUE = 永不过期） */
    private record Entry(String value, long expireAtMs) {
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    /** 频道 → 监听列表 */
    private final Map<String, List<BiConsumer<String, String>>> channels = new ConcurrentHashMap<>();

    /** 虚拟时钟（毫秒）；-1 表示使用系统时钟 */
    private final AtomicLong virtualNow = new AtomicLong(-1);

    /** 当前时间（毫秒） */
    private long now() {
        long v = virtualNow.get();
        return v >= 0 ? v : System.currentTimeMillis();
    }

    /**
     * 推进虚拟时钟（仅测试用）：使 TTL 判定前进指定秒数。
     *
     * @param seconds 秒数
     */
    public void advanceTime(long seconds) {
        long base = now();
        virtualNow.set(base + seconds * 1000L);
    }

    private Entry alive(String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expireAtMs() <= now()) {
            store.remove(key);
            return null;
        }
        return entry;
    }

    @Override
    public String get(String key) {
        Entry entry = alive(key);
        return entry == null ? null : entry.value();
    }

    @Override
    public void set(String key, String value, long ttlSeconds) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        long expireAt = ttlSeconds > 0 ? now() + ttlSeconds * 1000L : Long.MAX_VALUE;
        store.put(key, new Entry(value, expireAt));
    }

    @Override
    public boolean setnx(String key, String value, long ttlSeconds) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        synchronized (store) {
            if (alive(key) != null) {
                return false;
            }
            long expireAt = ttlSeconds > 0 ? now() + ttlSeconds * 1000L : Long.MAX_VALUE;
            store.put(key, new Entry(value, expireAt));
            return true;
        }
    }

    @Override
    public boolean delete(String key) {
        return store.remove(key) != null;
    }

    @Override
    public boolean expire(String key, long ttlSeconds) {
        Entry entry = alive(key);
        if (entry == null) {
            return false;
        }
        long expireAt = ttlSeconds > 0 ? now() + ttlSeconds * 1000L : Long.MAX_VALUE;
        store.put(key, new Entry(entry.value(), expireAt));
        return true;
    }

    @Override
    public void publish(String channel, String message) {
        List<BiConsumer<String, String>> listeners = channels.get(channel);
        if (listeners == null) {
            return;
        }
        for (BiConsumer<String, String> listener : List.copyOf(listeners)) {
            try {
                listener.accept(channel, message);
            } catch (Exception ignored) {
                // 模拟真实推送：单监听器异常不影响其他监听器
            }
        }
    }

    @Override
    public Subscription subscribe(String channel, BiConsumer<String, String> listener) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(listener, "listener");
        List<BiConsumer<String, String>> listeners =
                channels.computeIfAbsent(channel, c -> new CopyOnWriteArrayList<>());
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}
