package io.github.JiangHu.jframe.db.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地内存缓存层 — DataManager 的第一层读缓存。
 * <p>
 * 语义（按释放权设计定案）：
 * <ul>
 *   <li><b>默认无期限</b>：进程即释放权（随进程生死，无悬空问题）</li>
 *   <li>可选 TTL：需要「外部改库（OA/运维直改正本）最终一致」时显式配置</li>
 *   <li>可选容量上限 maxEntries：超出时先逐过期、再逐最久未访问（简化 LRU），
 *       防止 getBatch 等批量场景撑爆内存</li>
 *   <li>多实例新鲜度由失效广播负责（CacheCoordinator）</li>
 * </ul>
 */
public final class LocalCache {

    private record Entry(Object value, long expireAtMs, long lastAccessSeq) {
    }

    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();

    /** 默认死期毫秒（-1 = 无期限） */
    private final long defaultTtlMillis;

    /** 容量上限（-1 = 无限） */
    private final int maxEntries;

    /** 访问序号发生器（驱逐用） */
    private long accessSeq = 0;

    /**
     * @param defaultTtlMillis 默认死期毫秒（-1 无期限）
     * @param maxEntries       容量上限（-1 无限）
     */
    public LocalCache(long defaultTtlMillis, int maxEntries) {
        this.defaultTtlMillis = defaultTtlMillis;
        this.maxEntries = maxEntries;
    }

    /**
     * 写入（使用默认死期）。
     *
     * @param key   键
     * @param value 值
     */
    public void put(String key, Object value) {
        long now = System.currentTimeMillis();
        long expireAt = defaultTtlMillis > 0 ? now + defaultTtlMillis : Long.MAX_VALUE;
        map.put(key, new Entry(value, expireAt, nextSeq()));
        evictIfOverLimit();
    }

    /**
     * 读取（惰性过期 + 刷新访问序）。
     *
     * @param key 键
     * @return 命中值；未命中或已过期返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        Entry entry = map.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expireAtMs() <= System.currentTimeMillis()) {
            map.remove(key);
            return null;
        }
        map.put(key, new Entry(entry.value(), entry.expireAtMs(), nextSeq()));
        return (T) entry.value();
    }

    /**
     * 失效单键（收到失效广播时调用）。
     *
     * @param key 键
     */
    public void invalidate(String key) {
        map.remove(key);
    }

    /** 清空全部。 */
    public void clear() {
        map.clear();
    }

    /** @return 当前条目数（含可能未清理的过期项） */
    public int size() {
        return map.size();
    }

    private synchronized long nextSeq() {
        return ++accessSeq;
    }

    /** 超容量时驱逐：先清过期，再逐最久未访问 */
    private void evictIfOverLimit() {
        if (maxEntries <= 0 || map.size() <= maxEntries) {
            return;
        }
        long now = System.currentTimeMillis();
        map.entrySet().removeIf(e -> e.getValue().expireAtMs() <= now);
        while (map.size() > maxEntries) {
            Map.Entry<String, Entry> eldest = null;
            for (Map.Entry<String, Entry> e : map.entrySet()) {
                if (eldest == null || e.getValue().lastAccessSeq() < eldest.getValue().lastAccessSeq()) {
                    eldest = e;
                }
            }
            if (eldest == null || map.remove(eldest.getKey()) == null) {
                break;
            }
        }
    }
}
