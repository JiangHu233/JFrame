package io.github.JiangHu.jframe.db.spi;

/**
 * 订阅句柄 — {@link RedisStore#subscribe} 的返回值，{@code close()} 退订。
 * <p>
 * jframe_db 内部订阅（失效广播 / 实例名册）在注销与关服时必须 close，
 * 防止订阅泄漏。
 */
public interface Subscription extends AutoCloseable {

    /**
     * 退订。幂等：重复 close 无副作用。
     */
    @Override
    void close();
}
