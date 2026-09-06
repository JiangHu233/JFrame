package io.github.JiangHu.jframe.async.release;

/**
 * 通道被中止（{@link ReleaseChannel#abort()}）时使用的异常。
 * <p>
 * 用于两种场景：
 * <ul>
 *   <li>{@link ReleaseChannel#future()} 以本异常异常完成，等待者可区分「排干完成」与「被中止」；</li>
 *   <li>虚拟线程通过 {@code awaitChannel} 等待被中止的通道时，
 *       抛出的 {@code CompletionException} 的 cause 为本异常。</li>
 * </ul>
 *
 * @see ReleaseChannel
 */
public class ChannelAbortedException extends RuntimeException {

    /**
     * 以指定通道名构造中止异常。
     *
     * @param channelName 被中止的通道名
     */
    public ChannelAbortedException(String channelName) {
        super("通道 '" + channelName + "' 已被 abort 中止，未执行任务全部丢弃");
    }
}
