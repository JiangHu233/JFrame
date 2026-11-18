package io.github.JiangHu.jframe.db.cache;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.JiangHu.jframe.db.spi.RedisStore;
import io.github.JiangHu.jframe.db.spi.Subscription;
import io.github.JiangHu.jframe.db.support.DbConstants;
import io.github.JiangHu.jframe.serialize.exception.DataException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 多实例缓存失效广播协调器。
 * <p>
 * 协议（保留频道 {@link DbConstants#CHANNEL_INVALIDATE}）：
 * <pre>
 *   payload = {"i": 实例ID, "t": 表名, "k": 主键}
 *   写后发布：save/delete 的最后一步（Redis 故障静默降级，不影响主流程）
 *   收到广播：instanceId 等于自己跳过；否则路由到 (表名) 的失效回调
 *            （DataManager 失效其本地缓存对应键）
 * </pre>
 * 每个 RedisStore 至多一条订阅（首个挂缓存层的 manager 建立时订阅，注销时退订）。
 */
public final class CacheCoordinator {

    private final Gson gson = new Gson();
    private final String instanceId;

    /** RedisStore → 订阅 */
    private final Map<RedisStore, Subscription> subscriptions = new ConcurrentHashMap<>();

    /** 表名 → 本地失效回调列表（key = 主键） */
    private final Map<String, List<Consumer<String>>> routes = new ConcurrentHashMap<>();

    /** @param instanceId 本实例 ID（跳过自己发的广播） */
    public CacheCoordinator(String instanceId) {
        this.instanceId = instanceId;
    }

    /**
     * 确保已订阅该 RedisStore 的失效频道（幂等）。
     *
     * @param redis Redis 通道
     */
    public void ensureSubscribed(RedisStore redis) {
        subscriptions.computeIfAbsent(redis, r -> r.subscribe(DbConstants.CHANNEL_INVALIDATE,
                (channel, message) -> onMessage(message)));
    }

    /**
     * 退订该 RedisStore 的失效频道（unregister 时调用）。
     *
     * @param redis Redis 通道
     */
    public void unsubscribe(RedisStore redis) {
        Subscription sub = subscriptions.remove(redis);
        if (sub != null) {
            try {
                sub.close();
            } catch (Exception ignored) {
                // 退订失败不阻塞注销流程
            }
        }
    }

    /**
     * 注册某张表的本地失效回调。
     *
     * @param table   表名
     * @param handler 回调（入参为主键）
     */
    public void route(String table, Consumer<String> handler) {
        routes.computeIfAbsent(table, t -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /**
     * 发布失效广播（写路径最后一步；失败静默 + debug 日志）。
     *
     * @param redis  Redis 通道（无缓存层时由调用方跳过）
     * @param table  表名
     * @param key    主键
     */
    public void publishInvalidate(RedisStore redis, String table, String key) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("i", instanceId);
            json.addProperty("t", table);
            json.addProperty("k", key);
            redis.publish(DbConstants.CHANNEL_INVALIDATE, gson.toJson(json));
        } catch (Exception e) {
            // 广播失败仅影响他服本地缓存的即时失效，TTL/下次写会收敛——静默降级
        }
    }

    private void onMessage(String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String from = json.has("i") ? json.get("i").getAsString() : null;
            if (instanceId.equals(from)) {
                return; // 自己发的，本地已在写路径刷新
            }
            String table = json.has("t") ? json.get("t").getAsString() : null;
            String key = json.has("k") ? json.get("k").getAsString() : null;
            if (table == null || key == null) {
                return;
            }
            List<Consumer<String>> handlers = routes.get(table);
            if (handlers == null) {
                return;
            }
            for (Consumer<String> handler : handlers) {
                try {
                    handler.accept(key);
                } catch (Exception ignored) {
                    // 单个回调异常不影响其他回调
                }
            }
        } catch (Exception e) {
            throw new DataException("解析失效广播失败（频道保留，外部不应发布）: " + message, e);
        }
    }
}
