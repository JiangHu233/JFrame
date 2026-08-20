package io.github.JiangHu.jframe.ai.core.gaze;

import cn.nukkit.entity.Entity;
import cn.nukkit.math.Vector3;
import io.github.JiangHu.jframe.ai.core.targeting.EntityTarget;
import io.github.JiangHu.jframe.ai.core.targeting.PointTarget;
import io.github.JiangHu.jframe.ai.core.targeting.Target;

import java.util.Objects;

/**
 * 朝目标修正器:头(含 pitch)朝目标、身体沿移动方向——横向走位、边撤边看的表现。
 * <p>
 * 目标为<b>活引用</b>({@link Target}),每 tick 重新解析,目标移动时头随之转动;
 * 目标失效({@code get()} 返回 null)时退化为移动方向(头身同向),不产生朝向跳变残留。
 *
 * <h3>链式选项</h3>
 * <ul>
 *   <li>{@link #bodyFollow()} —— 身体也朝目标(面对目标倒退拉扯)</li>
 *   <li>{@link #aimHeight(double)} —— 瞄准点相对目标脚部的高度,默认 {@value #DEFAULT_AIM_HEIGHT}(躯干中心)</li>
 * </ul>
 *
 * <pre>{@code
 * // 横向走位:身体朝移动方向、头盯敌人
 * ai.walk(npc).to(flankPos).gaze(new TargetGaze(enemy)).start();
 *
 * // 面对敌人倒退拉扯
 * ai.walk(npc).to(retreatPos).gaze(new TargetGaze(enemy).bodyFollow()).start();
 * }</pre>
 */
public class TargetGaze implements Gaze {

    /** 瞄准点相对目标脚部的默认高度(躯干中心,避免仰视脚下/俯视头顶) */
    public static final double DEFAULT_AIM_HEIGHT = 1.0;

    private final Target ref;
    private boolean bodyFollow;
    private double aimHeight = DEFAULT_AIM_HEIGHT;

    /**
     * 以活引用构造(每 tick 重新解析)。
     *
     * @param ref 目标引用
     */
    public TargetGaze(Target ref) {
        this.ref = Objects.requireNonNull(ref, "ref");
    }

    /**
     * 以实体构造(等价 {@code new TargetGaze(new EntityTarget(entity))})。
     *
     * @param entity 目标实体
     */
    public TargetGaze(Entity entity) {
        this(new EntityTarget(entity));
    }

    /**
     * 以静态点构造(等价 {@code new TargetGaze(new PointTarget(pos))})。
     *
     * @param pos 目标点
     */
    public TargetGaze(Vector3 pos) {
        this(new PointTarget(pos));
    }

    /**
     * 身体也朝目标(默认身体沿移动方向)。
     *
     * @return this
     */
    public TargetGaze bodyFollow() {
        this.bodyFollow = true;
        return this;
    }

    /**
     * 瞄准点相对目标脚部的高度。
     *
     * @param height 高度(方块,非负)
     * @return this
     */
    public TargetGaze aimHeight(double height) {
        if (height < 0) {
            throw new IllegalArgumentException("aimHeight 必须非负: " + height);
        }
        this.aimHeight = height;
        return this;
    }

    @Override
    public void apply(GazeContext ctx) {
        Entity e = ctx.entity();
        Vector3 pos = ref.get();
        if (pos == null) {
            // 目标失效:退化为移动方向,头身同向
            double yaw = ctx.movementYaw();
            e.yaw = yaw;
            e.headYaw = yaw;
            return;
        }
        double dx = pos.x - e.x;
        double dy = (pos.y + aimHeight) - (e.y + e.getEyeHeight());
        double dz = pos.z - e.z;
        double headYaw = GazeContext.yawTowards(dx, dz);
        e.headYaw = headYaw;
        e.pitch = GazeContext.pitchTowards(dx, dy, dz);
        e.yaw = bodyFollow ? headYaw : ctx.movementYaw();
    }
}
