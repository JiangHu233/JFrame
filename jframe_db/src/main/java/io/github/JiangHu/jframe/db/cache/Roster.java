package io.github.JiangHu.jframe.db.cache;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.JiangHu.jframe.db.spi.RedisStore;
import io.github.JiangHu.jframe.db.spi.Subscription;
import io.github.JiangHu.jframe.db.support.DbConstants;
import io.github.JiangHu.jframe.db.support.DbExecutors;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 实例名册 — 多实例可观测与告警（纯广播式，零 Redis 键、零悬空数据）。
 * <p>
 * 机制：本实例启动/首个 Redis 通道注册时发 {@code join}；每 {@link #PERIOD_SECONDS}
 * 秒重播（存活宣告）；关服/注销发 {@code left}。各实例订阅保留频道
 * {@link DbConstants#CHANNEL_ROSTER}，内存维护活跃名单；超过 3 个周期未重播的
 * 条目自动剔除（崩溃者自然淡出——「不考虑意外关闭」场景下的免费自愈）。
 * <p>
 * <b>边界</b>：名册只做观测与告警（clearCache 前 warning「还有 N 台活跃实例将回源」、
 * 末位关闭提示），<b>不参与任何回收决策</b>；名册不准（丢包/残留）不影响缓存正确性。
 */
public final class Roster {

    /** 重播周期（秒） */
    public static final long PERIOD_SECONDS = 60;

    /** 超过该毫秒数未重播视为离线（3 个周期） */
    private static final long STALE_MS = PERIOD_SECONDS * 3 * 1000;

    private final Gson gson = new Gson();
    private final String instanceId;
    private final String serverLabel;

    /** 实例ID → 最后宣告毫秒 */
    private final Map<String, Long> members = new ConcurrentHashMap<>();

    private volatile RedisStore redis;
    private volatile Subscription subscription;
    private volatile ScheduledFuture<?> task;

    /**
     * @param instanceId  本实例 ID
     * @param serverLabel 展示标签（如服务器名）
     */
    public Roster(String instanceId, String serverLabel) {
        this.instanceId = instanceId;
        this.serverLabel = serverLabel;
    }

    /**
     * 绑定 Redis 通道并开始宣告（幂等；绑定首个注册的 RedisStore）。
     *
     * @param store   Redis 通道
     * @param executors 执行器（周期重播）
     */
    public synchronized void start(RedisStore store, DbExecutors executors) {
        if (this.redis != null) {
            return;
        }
        this.redis = store;
        members.put(instanceId, System.currentTimeMillis());
        try {
            subscription = store.subscribe(DbConstants.CHANNEL_ROSTER, (ch, msg) -> onMessage(msg));
        } catch (Exception ignored) {
            // 订阅失败（如通道故障）：名册仅观测用途，降级为只靠周期重播自愈
        }
        try {
            publish("join");
        } catch (Exception ignored) {
            // 宣告失败：周期任务会重试
        }
        task = executors.scheduleFixedDelay(this::announce, PERIOD_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 停止宣告并退订（关服/unregister 调用）。
     */
    public synchronized void stop() {
        if (redis == null) {
            return;
        }
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        try {
            publish("left");
        } catch (Exception ignored) {
            // 关服路径尽力而为
        }
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception ignored) {
            }
            subscription = null;
        }
        members.clear();
        redis = null;
    }

    /**
     * 活跃实例数（剔除超时未重播者后）。
     *
     * @return 活跃数（含自己）
     */
    public int activeCount() {
        prune();
        return members.size();
    }

    /** 本实例是否为名册中最后一个（末位关灯提示用）。 */
    public boolean isLastMember() {
        prune();
        return members.size() <= 1;
    }

    private void prune() {
        long now = System.currentTimeMillis();
        members.values().removeIf(lastSeen -> now - lastSeen > STALE_MS);
    }

    private void announce() {
        try {
            members.put(instanceId, System.currentTimeMillis());
            publish("announce");
        } catch (Exception ignored) {
            // 重播失败下轮再试
        }
    }

    private void publish(String event) {
        JsonObject json = new JsonObject();
        json.addProperty("e", event);
        json.addProperty("id", instanceId);
        json.addProperty("label", serverLabel);
        redis.publish(DbConstants.CHANNEL_ROSTER, gson.toJson(json));
    }

    private void onMessage(String message) {
        try {
            JsonObject json = com.google.gson.JsonParser.parseString(message).getAsJsonObject();
            String event = json.has("e") ? json.get("e").getAsString() : "";
            String id = json.has("id") ? json.get("id").getAsString() : null;
            if (id == null) {
                return;
            }
            switch (event) {
                case "join" -> {
                    members.put(id, System.currentTimeMillis());
                    // 晚加入者错过了此前广播：收到他人 join 时回发自己的存活宣告，
                    // 让新成员立即学得全量名册（announce 不触发回发，无回声风暴）
                    if (!instanceId.equals(id)) {
                        try {
                            members.put(instanceId, System.currentTimeMillis());
                            publish("announce");
                        } catch (Exception ignored) {
                            // 回发失败等周期重播
                        }
                    }
                }
                case "announce" -> members.put(id, System.currentTimeMillis());
                case "left" -> members.remove(id);
                default -> {
                }
            }
        } catch (Exception ignored) {
            // 名册消息异常一律忽略（纯观测，不影响正确性）
        }
    }
}
