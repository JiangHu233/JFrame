package io.github.JiangHu.jframe.async.release;

import java.util.concurrent.CompletableFuture;

/**
 * 释放通道——一条逻辑任务流（一个批次）的公开接口。
 * <p>
 * 通过 {@link ReleaseAPI#acquire(ReleasePolicy)}（手动）、
 * {@link ReleaseAPI#acquireAuto(ReleasePolicy)}（自动回收）或
 * {@link ReleaseAPI#channel(String, ReleasePolicy)}（命名）获取。
 * 任务经 {@link #submit(Runnable)} 入队后，由 {@link ReleaseAPI} 在全局预算内
 * 按通道策略（{@link ReleasePolicy}）分批释放到 Nukkit 主线程执行。
 *
 * <h3>生命周期</h3>
 * <ul>
 *   <li><b>MANUAL（{@code acquire}）</b>：默认模式，零自动管理开销，
 *       需显式 {@link #close()} / {@link #abort()} 终结；</li>
 *   <li><b>AUTO（{@code acquireAuto}）</b>：显式申请（含空闲回收扫描的管理开销），
 *       队列清空且空闲超时后自动回收；被回收后再 {@code submit} 惰性复活（新批次）。</li>
 * </ul>
 *
 * <h3>完成感知</h3>
 * <ul>
 *   <li>{@link #onComplete(Runnable)}：通道<b>终结</b>且任务全部排干时在主线程触发一次；</li>
 *   <li>{@link #future()}：<b>当前批次</b>快照——队列被消费清空时 complete，
 *       AUTO 复活 / 新一波提交会换新 future，旧等待者不受干扰（快照语义）。</li>
 * </ul>
 *
 * <h3>示例</h3>
 * <pre>{@code
 * ReleaseChannel ch = release.acquire(ReleasePolicy.deadlineSeconds(20));
 * for (Block b : blocks) {
 *     Data d = heavyCompute(b);                 // 异步线程计算
 *     ch.submit(() -> level.setBlock(b, d));    // 缓释到主线程
 * }
 * ch.onComplete(() -> broadcast("建造完成"));    // 完成回调（主线程）
 * ch.close();                                    // 声明提交结束
 * }</pre>
 *
 * @see ReleasePolicy
 * @see ReleaseAPI
 */
public interface ReleaseChannel {

    /**
     * 通道名。
     * <p>
     * 命名通道为创建时指定名；匿名通道为自动生成的唯一 ID（如 {@code auto-3f2a}）。
     *
     * @return 通道名
     */
    String name();

    /**
     * 通道释放策略。
     *
     * @return 创建时指定的策略
     */
    ReleasePolicy policy();

    /**
     * 入队一个主线程任务。
     * <p>
     * 超出通道背压上限时<b>阻塞</b>（信号量语义，虚拟线程 park 友好），
     * 直到主线程消费释放名额。一个失控的生产者只阻塞自己的通道，不影响其他来源。
     *
     * @param task 要在主线程执行的任务
     * @throws IllegalStateException 通道已 close / abort 后再提交时抛出
     */
    void submit(Runnable task);

    /**
     * 非阻塞入队：队列满（达到背压上限）时返回 {@code false}，不阻塞。
     *
     * @param task 要在主线程执行的任务
     * @return {@code true} 入队成功；{@code false} 背压满或通道已终结
     */
    boolean trySubmit(Runnable task);

    /**
     * 本通道待执行任务数。
     *
     * @return 待执行任务数（O(1) 计数器）
     */
    int size();

    /**
     * 清空本通道未执行任务（不清终结状态）。
     * <p>
     * 被丢弃任务对应的背压名额全部释放；当前批次 future 正常完成（视为排干）。
     */
    void clear();

    /**
     * 注册完成回调：通道<b>终结且任务全部排干</b>时在主线程触发，恰好一次。
     * <ul>
     *   <li>主动终结：{@link #close()} 后存量任务释放完毕的那一刻；</li>
     *   <li>被动终结：AUTO 通道空闲被自动回收时（视为该波完成）。</li>
     * </ul>
     * 多次注册以最后一次为准；{@link #abort()} 不触发回调。
     * <b>回调内不得再向本通道提交</b>（已终结）。
     *
     * @param callback 完成回调（主线程执行）
     */
    void onComplete(Runnable callback);

    /**
     * 「当前批次」快照 future：队列被消费清空时 complete；
     * {@link #abort()} 时以 {@link ChannelAbortedException} 异常完成。
     * <p>
     * 虚拟线程可 {@code join()} 同步等待整批落地；AUTO 通道回收后复活 / 新一波提交
     * 会换新 future——每次调用返回<b>调用时刻所在批次</b>的引用，旧等待者等旧批次（快照语义）。
     *
     * @return 当前批次 future
     */
    CompletableFuture<Void> future();

    /**
     * 优雅关闭：拒绝新提交，存量任务继续释放，排干后触发 {@link #onComplete(Runnable)}
     * 回调并注销通道。幂等；close 时队列已空则立即（下个调度 tick 内）触发回调。
     */
    void close();

    /**
     * 立即注销：丢弃全部未执行任务，<b>不触发</b> {@link #onComplete(Runnable)}，
     * {@link #future()} 以 {@link ChannelAbortedException} 异常完成（等待者可区分取消）。
     * 被丢弃任务对应的背压名额全部释放。
     */
    void abort();

    /**
     * 通道是否已终结（close 或 abort）。
     * <p>
     * 注意：AUTO 通道被空闲回收后此方法仍返回 {@code false}（惰性复活语义），
     * 终结特指显式 close / abort。
     *
     * @return {@code true} 已显式终结
     */
    boolean isClosed();
}
