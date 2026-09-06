package io.github.JiangHu.jframe.core.data.trigger;

import java.util.Objects;
import java.util.function.Consumer;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * 倒计时触发器——被调用指定次数后执行绑定函数的计数数据类型。
 *
 * <p>每次调用 {@link #tick()} 计数减一；当计数恰好归零时，执行构造时绑定的函数（{@code action}）。
 * 适合「按次数触发」的场景：连击第 N 次触发暴击、每采集 M 个方块掉落奖励、技能充能 N 层后释放、
 * 重试第 K 次后告警等。
 *
 * <h3>触发模式（{@link Mode}）</h3>
 * <ul>
 *   <li><b>{@link Mode#RESETTABLE 可重置}</b>（默认）：触发后进入失效状态，后续 {@link #tick()} 不再计数；
 *       调用 {@link #reset()} 可恢复初始计数重新开始。<b>不调用 reset 就是一次性保险丝语义</b>。</li>
 *   <li><b>{@link Mode#RECURRING 自动循环}</b>：每次触发后自动恢复初始计数，立即开始下一轮，
 *       无需手动 reset（周期性触发语义）。</li>
 * </ul>
 *
 * <h3>快速上手</h3>
 * <pre>{@code
 * // 一次性：第 3 次 tick 时触发，之后失效
 * var fuse = CountdownTrigger.of(3, () -> player.sendMessage("三连击达成！"));
 * fuse.tick();   // false（剩余 2）
 * fuse.tick();   // false（剩余 1）
 * fuse.tick();   // true  —— 执行绑定函数
 * fuse.tick();   // false（已失效，不再计数）
 *
 * // 周期性：每 5 次 tick 触发一次，自动循环
 * var loop = CountdownTrigger.of(5, () -> dropReward(), CountdownTrigger.Mode.RECURRING);
 * for (int i = 0; i < 12; i++) {
 *     loop.tick();   // 第 5、10 次 tick 返回 true 并触发
 * }
 *
 * // 手动重置：触发后 reset 复活，重新计数
 * fuse.reset();  // 恢复到 3 次初始计数
 * }</pre>
 *
 * <h3>状态与生命周期</h3>
 * <ul>
 *   <li>{@link #tick()} 返回<b>本次调用是否触发</b>了绑定函数（非剩余次数）。</li>
 *   <li>{@link #cancel()} 取消后 {@link #tick()} 永远返回 {@code false}；{@link #reset()} 可清除取消状态。</li>
 *   <li>{@link #reset()} 在任何模式、任何状态下均可安全调用，恢复到「全新」状态。</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <ul>
 *   <li>状态字段为 {@code volatile}；所有状态变更（计数 / 取消 / 重置）在 {@code synchronized} 段内串行化。</li>
 *   <li><b>触发权在同步段内判定</b>：并发调用下，绑定函数对每次归零<b>恰好执行一次</b>（不会重复触发）。</li>
 *   <li>绑定函数在<b>锁外</b>执行：action 内可安全调用 {@link #tick()} / {@link #reset()} / {@link #cancel()}
 *       而不会死锁；action 抛出的 {@link RuntimeException} 由 {@link #errorHandler} 捕获，
 *       <b>不会</b>影响计数状态，{@link Mode#RECURRING} 下仍继续下一轮。</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>计数 {@code count} 必须 {@code >= 1}，绑定函数不可为 {@code null}，否则工厂方法抛出异常。</li>
 *   <li>「已触发」({@link #isTriggered()}) 指历史上至少触发过一次（含 RECURRING 的每一轮）。</li>
 *   <li>本类型只负责次数判定，不做时间调度；如需定时请配合调度器使用。</li>
 * </ul>
 *
 * @see Mode
 */
@Accessors(chain = true)
public class CountdownTrigger {

    /**
     * 触发模式：计数归零、执行绑定函数后的行为。
     * <ul>
     *   <li>{@link #RESETTABLE} —— 失效并等待手动 {@link CountdownTrigger#reset()}（不重置即一次性）。</li>
     *   <li>{@link #RECURRING} —— 自动恢复计数，立即开始下一轮（周期触发）。</li>
     * </ul>
     */
    public enum Mode {
        /** 触发后失效：后续 tick 不再计数，需手动 {@link CountdownTrigger#reset()} 复活。不重置即一次性保险丝。 */
        RESETTABLE,

        /** 触发后自动恢复初始计数，立即开始下一轮，无需手动重置。 */
        RECURRING
    }

    /** 设定次数（构造后不变）。
     * -- GETTER --
     *
     * @return 设定次数（构造时传入，不变）
     */
    @Getter
    private final int count;

    /** 绑定的函数：计数归零时执行（构造后不变）。 */
    private final Runnable action;

    /** 触发模式（构造后不变）。 */
    @Getter
    private final Mode mode;

    /** 剩余次数。归零且非 RECURRING 自动重置时表示失效。读取走 {@link #getRemaining()}。
     * -- GETTER --
     *
     * @return 剩余次数（还需调用多少次 {@link #tick()} 触发；已取消 / 已失效时为当前值，通常为 0）
     */
    @Getter
    private volatile int remaining;

    /** 是否已取消：取消后 tick 永不计数、永不触发；reset 可清除。 */
    @Getter
    private volatile boolean cancelled = false;

    /** 历史累计触发次数（RECURRING 每轮各计一次）。 */
    @Getter
    private volatile int triggerCount = 0;

    /**
     * 绑定函数异常处理器。action 抛出 {@link RuntimeException} 时调用，
     * 默认实现将异常打印到 {@code System.err}。可替换为自定义日志 / 上报逻辑。
     */
    @Setter
    private Consumer<RuntimeException> errorHandler = CountdownTrigger::defaultErrorHandler;

    private CountdownTrigger(int count, Runnable action, Mode mode) {
        this.count = count;
        this.action = action;
        this.mode = mode;
        this.remaining = count;
    }

    // ==================== 工厂 ====================

    /**
     * 创建默认模式（{@link Mode#RESETTABLE}）的触发器：触发后失效，可手动 {@link #reset()} 复活。
     *
     * @param count  触发所需的调用次数（{@code >= 1}）
     * @param action 计数归零时执行的函数
     * @return 新触发器（剩余次数 = {@code count}）
     * @throws IllegalArgumentException 若 {@code count < 1}
     * @throws NullPointerException     若 {@code action} 为 {@code null}
     */
    public static CountdownTrigger of(int count, Runnable action) {
        return of(count, action, Mode.RESETTABLE);
    }

    /**
     * 创建指定模式的触发器。
     *
     * @param count  触发所需的调用次数（{@code >= 1}）
     * @param action 计数归零时执行的函数
     * @param mode   触发模式（{@link Mode#RESETTABLE} 失效待重置 / {@link Mode#RECURRING} 自动循环）
     * @return 新触发器（剩余次数 = {@code count}）
     * @throws IllegalArgumentException 若 {@code count < 1}
     * @throws NullPointerException     若 {@code action} 或 {@code mode} 为 {@code null}
     */
    public static CountdownTrigger of(int count, Runnable action, Mode mode) {
        if (count < 1) {
            throw new IllegalArgumentException("count 必须 >= 1，实际: " + count);
        }
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(mode, "mode");
        return new CountdownTrigger(count, action, mode);
    }

    // ==================== 核心操作 ====================

    /**
     * 调用一次：计数减一，归零时执行绑定函数。
     *
     * <p>已取消或已失效（RESETTABLE 触发后未重置）时为空操作。
     *
     * @return 本次调用是否触发了绑定函数（其余调用一律返回 {@code false}）
     */
    public boolean tick() {
        final boolean fire;
        synchronized (this) {
            if (cancelled || remaining <= 0) {
                return false;
            }
            remaining--;
            fire = remaining == 0;
            if (fire) {
                triggerCount++;
                if (mode == Mode.RECURRING) {
                    remaining = count;   // 自动开始下一轮
                }
            }
        }
        if (fire) {
            runAction();                 // 锁外执行，避免死锁与长时间持锁
        }
        return fire;
    }

    /**
     * 重置：恢复初始计数并清除取消状态，回到「全新」触发器。
     *
     * <p>任何模式、任何状态（含已触发 / 已取消）下均可安全调用。
     *
     * @return {@code this}（链式）
     */
    public CountdownTrigger reset() {
        synchronized (this) {
            remaining = count;
            cancelled = false;
        }
        return this;
    }

    /**
     * 取消：此后 {@link #tick()} 永不计数、永不触发。
     *
     * <p>幂等；可通过 {@link #reset()} 清除取消状态复活。
     *
     * @return {@code this}（链式）
     */
    public CountdownTrigger cancel() {
        synchronized (this) {
            cancelled = true;
        }
        return this;
    }

    // ==================== 查询 ====================

    /**
     * @return 历史上是否至少触发过一次（含 RECURRING 的每一轮；reset 不清除历史）
     */
    public boolean isTriggered() {
        return triggerCount > 0;
    }

    /**
     * 是否已失效：已触发且处于 {@link Mode#RESETTABLE}（等待手动重置）。
     *
     * <p>{@link Mode#RECURRING} 触发后自动开始下一轮，永不失效；已取消不算失效（见 {@link #isCancelled()}）。
     *
     * @return {@code true} 表示后续 tick 不会再计数，需 reset 复活
     */
    public boolean isExpired() {
        return !cancelled && remaining <= 0;
    }

    // ==================== 内部 ====================

    /** 在锁外执行绑定函数，异常交给 {@link #errorHandler}，不影响计数状态。 */
    private void runAction() {
        try {
            action.run();
        } catch (RuntimeException e) {
            errorHandler.accept(e);
        }
    }

    private static void defaultErrorHandler(RuntimeException e) {
        System.err.println("[CountdownTrigger] 绑定函数执行异常: " + e);
        e.printStackTrace(System.err);
    }

    @Override
    public String toString() {
        return "CountdownTrigger{mode=" + mode + ", count=" + count + ", remaining=" + remaining
                + ", cancelled=" + cancelled + ", triggerCount=" + triggerCount + "}";
    }
}
