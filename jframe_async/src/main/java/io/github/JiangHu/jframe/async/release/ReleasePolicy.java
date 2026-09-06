package io.github.JiangHu.jframe.async.release;

/**
 * 释放策略——不可变值对象。
 * <p>
 * 声明一条 {@link ReleaseChannel 通道} 中主线程任务的释放节奏，三种策略：
 * <ul>
 *   <li><b>DEADLINE</b>（截止时间）：声明「这批任务在 N tick / N 秒内完成」，
 *       缓释器根据剩余量与剩余时间动态计算释放速率（越接近截止速率越高，自然收敛）；
 *       超期后退化为弹性（与 BEST_EFFORT 同组分食剩余带宽），不挤压其他守约通道。</li>
 *   <li><b>RATE</b>（固定速率）：每 tick 释放 n 个（支持小数配额累积，如 0.5/tick = 每 2 tick 1 个）。</li>
 *   <li><b>BEST_EFFORT</b>（尽力而为）：无速率要求，吃全局剩余预算（默认策略）。</li>
 * </ul>
 *
 * <h3>示例</h3>
 * <pre>{@code
 * ReleasePolicy.deadlineSeconds(20);  // 这批 20 秒内放完，速率自动计算与调整
 * ReleasePolicy.ratePerTick(5);       // 每 tick 固定释放 5 个
 * ReleasePolicy.bestEffort();         // 尽力而为，吃剩余预算
 * }</pre>
 *
 * <h3>线程安全</h3>
 * 不可变对象，可自由跨线程共享。
 *
 * @see ReleaseChannel
 * @see ReleaseAPI
 */
public final class ReleasePolicy {

    /** Nukkit 标准 TPS，用于秒与 tick 的换算 */
    public static final int TPS = 20;

    /** 策略种类 */
    public enum Kind { DEADLINE, RATE, BEST_EFFORT }

    /** 策略种类 */
    private final Kind kind;

    /** DEADLINE：期限长度（tick） */
    private final long durationTicks;

    /** RATE：每 tick 释放速率（支持小数） */
    private final double ratePerTick;

    private ReleasePolicy(Kind kind, long durationTicks, double ratePerTick) {
        this.kind = kind;
        this.durationTicks = durationTicks;
        this.ratePerTick = ratePerTick;
    }

    // ==================== 静态工厂 ====================

    /**
     * 截止时间策略：在 {@code durationTicks} 个调度 tick 内完成全部任务。
     * <p>
     * 释放速率 = ceil(pending / 剩余tick)，随积压与剩余时间动态调整，自然收敛。
     *
     * @param durationTicks 期限长度（tick），必须 ≥ 1
     * @return 截止时间策略
     * @throws IllegalArgumentException 参数非法时抛出
     */
    public static ReleasePolicy deadlineTicks(long durationTicks) {
        if (durationTicks < 1) {
            throw new IllegalArgumentException("durationTicks 必须 >= 1，实际: " + durationTicks);
        }
        return new ReleasePolicy(Kind.DEADLINE, durationTicks, 0.0);
    }

    /**
     * 截止时间策略：在 {@code seconds} 秒内完成全部任务（按 {@link #TPS} 换算为 tick）。
     *
     * @param seconds 期限长度（秒），必须 > 0
     * @return 截止时间策略
     * @throws IllegalArgumentException 参数非法时抛出
     */
    public static ReleasePolicy deadlineSeconds(double seconds) {
        if (seconds <= 0 || !Double.isFinite(seconds)) {
            throw new IllegalArgumentException("seconds 必须为正有限数，实际: " + seconds);
        }
        return deadlineTicks(Math.round(seconds * TPS));
    }

    /**
     * 固定速率策略：每 tick 释放 {@code n} 个（支持小数，如 0.5 = 每 2 tick 1 个）。
     *
     * @param n 每 tick 释放数，必须 > 0 且为有限数
     * @return 固定速率策略
     * @throws IllegalArgumentException 参数非法时抛出
     */
    public static ReleasePolicy ratePerTick(double n) {
        if (n <= 0 || !Double.isFinite(n)) {
            throw new IllegalArgumentException("ratePerTick 必须为正有限数，实际: " + n);
        }
        return new ReleasePolicy(Kind.RATE, 0, n);
    }

    /**
     * 固定速率策略：每秒释放 {@code n} 个（按 {@link #TPS} 换算为每 tick 速率）。
     *
     * @param n 每秒释放数，必须 > 0 且为有限数
     * @return 固定速率策略
     * @throws IllegalArgumentException 参数非法时抛出
     */
    public static ReleasePolicy ratePerSecond(double n) {
        if (n <= 0 || !Double.isFinite(n)) {
            throw new IllegalArgumentException("ratePerSecond 必须为正有限数，实际: " + n);
        }
        return ratePerTick(n / TPS);
    }

    /**
     * 尽力而为策略：无速率要求，吃全局剩余预算（默认策略）。
     *
     * @return 尽力而为策略
     */
    public static ReleasePolicy bestEffort() {
        return new ReleasePolicy(Kind.BEST_EFFORT, 0, 0.0);
    }

    // ==================== 只读访问 ====================

    /**
     * 策略种类。
     *
     * @return 策略种类枚举值
     */
    public Kind kind() {
        return kind;
    }

    /**
     * DEADLINE 策略的期限长度（tick），其他策略返回 0。
     *
     * @return 期限长度（tick）
     */
    public long durationTicks() {
        return durationTicks;
    }

    /**
     * RATE 策略的每 tick 释放速率（支持小数），其他策略返回 0。
     *
     * @return 每 tick 释放速率
     */
    public double ratePerTick() {
        return ratePerTick;
    }

    @Override
    public String toString() {
        return switch (kind) {
            case DEADLINE -> "ReleasePolicy(DEADLINE, " + durationTicks + " ticks)";
            case RATE -> "ReleasePolicy(RATE, " + ratePerTick + "/tick)";
            case BEST_EFFORT -> "ReleasePolicy(BEST_EFFORT)";
        };
    }

    /**
     * 值语义相等：同 kind 且参数相同即相等（命名通道复用检查依赖此语义，
     * 每次工厂调用返回新实例不得视为不同策略）。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReleasePolicy that)) {
            return false;
        }
        return kind == that.kind
                && durationTicks == that.durationTicks
                && Double.compare(ratePerTick, that.ratePerTick) == 0;
    }

    @Override
    public int hashCode() {
        int result = kind.hashCode();
        result = 31 * result + Long.hashCode(durationTicks);
        result = 31 * result + Double.hashCode(ratePerTick);
        return result;
    }
}
