package io.github.JiangHu.jframe.ai.core.gaze;

import cn.nukkit.entity.Entity;

import java.util.Objects;

/**
 * 平滑转头装饰器:包装任意 {@link Gaze},以有限角速度逼近目标朝向,
 * 并支持反应延迟——模拟真人移动视角时的短暂调整延迟。
 * <p>
 * 工作方式:先让被包装修正器算出"期望朝向"(直接写入实体字段),
 * 再读取该期望值,与内部记录的"当前显示朝向"做限速逼近后覆盖写回。
 * 因此可叠加在任意修正器之上。
 *
 * <h3>示例</h3>
 * <pre>{@code
 * // 头以每 tick 最多 10° 转向敌人,而非瞬跳
 * ai.walk(npc).to(pos).gaze(new SmoothGaze(new TargetGaze(enemy)).maxTurnSpeed(10)).start();
 *
 * // 加反应延迟:目标朝向变化后 3 tick 才开始转(模拟人类反应时间)
 * ai.walk(npc).to(pos)
 *         .gaze(new SmoothGaze(new TargetGaze(enemy)).maxTurnSpeed(10).reactionDelay(3))
 *         .start();
 * }</pre>
 *
 * <p>
 * <b>有状态</b>:实例随导航器独享,不可跨实体共享(手动 new 风格天然满足)。
 */
public class SmoothGaze implements Gaze {

    /** 默认头部最大角速度(度/tick,约 200°/秒) */
    public static final double DEFAULT_HEAD_TURN = 10;
    /** 默认身体最大角速度(度/tick,身体转动通常快于头部) */
    public static final double DEFAULT_BODY_TURN = 20;
    /** 目标头朝向变化超过该角度(度)视为"显著变化",触发反应延迟 */
    private static final double CHANGE_THRESHOLD = 1.0;

    private final Gaze delegate;
    private double maxHeadTurn = DEFAULT_HEAD_TURN;
    private double maxBodyTurn = DEFAULT_BODY_TURN;
    private int reactionDelay;

    // ═══ 运行态(随导航器生命周期) ═══
    private boolean initialized;
    private double shownYaw;
    private double shownHeadYaw;
    private double shownPitch;
    private double lastTargetHeadYaw;
    private int delayRemaining;

    /**
     * 包装指定修正器。
     *
     * @param delegate 被包装的修正器(提供期望朝向)
     */
    public SmoothGaze(Gaze delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * 头身共用最大角速度(等价同时调用 {@link #maxHeadTurn} 与 {@link #maxBodyTurn})。
     *
     * @param degreesPerTick 度/tick,必须为正
     * @return this
     */
    public SmoothGaze maxTurnSpeed(double degreesPerTick) {
        return maxHeadTurn(degreesPerTick).maxBodyTurn(degreesPerTick);
    }

    /**
     * 头部最大角速度(headYaw 与 pitch 共用)。
     *
     * @param degreesPerTick 度/tick,必须为正
     * @return this
     */
    public SmoothGaze maxHeadTurn(double degreesPerTick) {
        if (degreesPerTick <= 0) {
            throw new IllegalArgumentException("maxHeadTurn 必须为正: " + degreesPerTick);
        }
        this.maxHeadTurn = degreesPerTick;
        return this;
    }

    /**
     * 身体最大角速度(yaw)。
     *
     * @param degreesPerTick 度/tick,必须为正
     * @return this
     */
    public SmoothGaze maxBodyTurn(double degreesPerTick) {
        if (degreesPerTick <= 0) {
            throw new IllegalArgumentException("maxBodyTurn 必须为正: " + degreesPerTick);
        }
        this.maxBodyTurn = degreesPerTick;
        return this;
    }

    /**
     * 反应延迟:期望头朝向显著变化后,维持旧朝向多少 tick 才开始转动。
     *
     * @param ticks 延迟 tick 数(0=无延迟,必须非负)
     * @return this
     */
    public SmoothGaze reactionDelay(int ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("reactionDelay 必须非负: " + ticks);
        }
        this.reactionDelay = ticks;
        return this;
    }

    @Override
    public void apply(GazeContext ctx) {
        Entity e = ctx.entity();
        // 1. 委托修正器算期望朝向(直接写入实体字段,随后读取)
        double prevYaw = e.yaw;
        double prevHeadYaw = e.headYaw;
        double prevPitch = e.pitch;
        delegate.apply(ctx);
        double targetYaw = e.yaw;
        double targetHeadYaw = e.headYaw;
        double targetPitch = e.pitch;

        // 2. 首次调用:以实体当前朝向为显示起点
        if (!initialized) {
            shownYaw = prevYaw;
            shownHeadYaw = prevHeadYaw;
            shownPitch = prevPitch;
            lastTargetHeadYaw = targetHeadYaw;
            initialized = true;
        }

        // 3. 反应延迟:期望头朝向显著变化时重置延迟计数
        if (Math.abs(GazeContext.angleDiff(lastTargetHeadYaw, targetHeadYaw)) > CHANGE_THRESHOLD) {
            lastTargetHeadYaw = targetHeadYaw;
            delayRemaining = reactionDelay;
        }
        if (delayRemaining > 0) {
            delayRemaining--;
            writeBack(e);
            return;
        }

        // 4. 限速逼近期望朝向
        shownYaw = approachAngle(shownYaw, targetYaw, maxBodyTurn);
        shownHeadYaw = approachAngle(shownHeadYaw, targetHeadYaw, maxHeadTurn);
        shownPitch = approachAngle(shownPitch, targetPitch, maxHeadTurn);
        writeBack(e);
    }

    private void writeBack(Entity e) {
        e.yaw = shownYaw;
        e.headYaw = shownHeadYaw;
        e.pitch = shownPitch;
    }

    /**
     * 限速角度逼近:从 {@code current} 向 {@code target} 沿最短路径最多前进 {@code maxStep} 度。
     * <p>
     * 纯函数,便于单元测试。
     *
     * @param current 当前角(度)
     * @param target  目标角(度)
     * @param maxStep 单步最大度数(正)
     * @return 逼近后的角度(归一至 {@code [-180, 180)})
     */
    static double approachAngle(double current, double target, double maxStep) {
        double diff = GazeContext.angleDiff(current, target);
        double next;
        if (Math.abs(diff) <= maxStep) {
            next = target;
        } else {
            next = current + Math.signum(diff) * maxStep;
        }
        // 归一,防止长时间运行角度漂移
        double normalized = next % 360.0;
        if (normalized >= 180.0) {
            normalized -= 360.0;
        } else if (normalized < -180.0) {
            normalized += 360.0;
        }
        return normalized;
    }
}
