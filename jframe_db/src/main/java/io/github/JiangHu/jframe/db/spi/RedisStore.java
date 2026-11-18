package io.github.JiangHu.jframe.db.spi;

import java.util.function.BiConsumer;

/**
 * Redis 型存储 SPI（适配器实现）— 一个 Redis 实例（或一个 db 编号）的 KV + 广播通道。
 * <p>
 * 精简 7 方法 + 一个永久 set 重载：取值 / 存值（可带 TTL）/ setnx 原子抢占 /
 * 删除 / 续期 / 发布 / 订阅。覆盖缓存、防重、跨实例互斥、限时标记与跨服消息。
 * <p>
 * jframe_db 的组合层（DataManager 缓存、失效广播、实例名册、数据区域）
 * 全部由本接口方法组合实现，<b>零扩展</b>。
 *
 * <h3>TTL 语义与归属权</h3>
 * <ul>
 *   <li>直用层<b>无全局默认 TTL</b>——锁/验证码/防重标记的合理寿命各不相同，
 *       每次调用显式传入（{@code ttl <= 0} 表示永不过期）</li>
 *   <li>框架写入的缓存键<b>死期必填</b>：TTL 是悬空数据免疫与外部写
 *       （OA/运维直改正本）时旧值存活上限的唯一保底——<b>删除是优化，过期才是释放</b></li>
 *   <li>永久 key 仅应用于「它就是正本且由持有者显式管理删除」的小体量数据</li>
 * </ul>
 *
 * <h3>subscribe 线程模型</h3>
 * <p>
 * 回调在<b>适配器的订阅线程</b>执行（如 Jedis 独立订阅连接的推送线程），
 * 回调内不应执行长阻塞操作；需要回到服务器主线程请调用方自行调度。
 */
public interface RedisStore {

    /**
     * 读取键值。
     *
     * @param key 键
     * @return 值；不存在或已过期返回 null
     */
    String get(String key);

    /**
     * 写入键值（带过期）。
     *
     * @param key        键
     * @param value      值
     * @param ttlSeconds 死期（秒）；&lt;=0 表示永不过期
     */
    void set(String key, String value, long ttlSeconds);

    /**
     * 写入键值（永不过期）——对齐 Redis 原生 SET 无 EX 的行为。
     * <p>
     * 仅用于「正本且显式管理删除」的数据；凡缓存必带死期。
     *
     * @param key   键
     * @param value 值
     */
    default void set(String key, String value) {
        set(key, value, -1);
    }

    /**
     * 原子抢占：「不存在才写入」，一条命令原子完成。
     * <p>
     * 多实例同时调用仅一个成功——多服防重（如生日邮件）与跨服互斥的基础。
     *
     * @param key        键
     * @param value      值（通常写入持有者标识）
     * @param ttlSeconds 死期（秒）；持有者崩溃后 key 到期自动释放防死锁
     * @return 抢占成功（此前不存在）返回 true；已存在返回 false
     */
    boolean setnx(String key, String value, long ttlSeconds);

    /**
     * 删除键。
     *
     * @param key 键
     * @return 存在并删除返回 true；不存在返回 false
     */
    boolean delete(String key);

    /**
     * 给已有键设置/顺延过期时间（续命）。
     *
     * @param key        键
     * @param ttlSeconds 新死期（秒）
     * @return 键存在返回 true；不存在返回 false
     */
    boolean expire(String key, long ttlSeconds);

    /**
     * 向频道发布消息（跨服广播）。
     *
     * @param channel 频道（业务消息建议 {@code 插件名:主题} 前缀隔离；
     *                {@code __jframe_db__:} 前缀为框架保留）
     * @param message 消息文本
     */
    void publish(String channel, String message);

    /**
     * 订阅频道。同一频道多次订阅会依次收到消息。
     *
     * @param channel  频道
     * @param listener 回调（参数为 频道, 消息；在适配器订阅线程执行）
     * @return 订阅句柄，{@code close()} 退订
     */
    Subscription subscribe(String channel, BiConsumer<String, String> listener);
}
