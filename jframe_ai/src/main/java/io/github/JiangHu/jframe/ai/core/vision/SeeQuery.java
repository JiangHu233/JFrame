package io.github.JiangHu.jframe.ai.core.vision;

import cn.nukkit.entity.Entity;
import cn.nukkit.math.Vector3;

/**
 * 视野查询:链式配置观察参数后执行感知判断。
 * <p>
 * 本类是 {@link VisionSensor} 的链式门面,感知语义(距离/FOV/视线三要素)全部委托后者,
 * 本类只负责<b>参数收集</b>。判断语义与 {@code VisionSensor} 完全一致:
 * <ul>
 *   <li>{@link #canSee(Entity)} —— 距离 + 朝向 FOV + 视线(受当前朝向约束)</li>
 *   <li>{@link #canSee(Vector3)} —— 坐标版:距离 + 视线("转头能否看到该点")</li>
 *   <li>{@link #canSee360(Entity)} —— 全向感知:距离 + 视线(不限朝向)</li>
 *   <li>{@link #angleTo(Entity)} —— 目标相对朝向的水平夹角(0=正前方,180=正后方)</li>
 * </ul>
 *
 * <h3>设计说明</h3>
 * <ul>
 *   <li><b>配置态对象,可复用</b>:一次构造绑定观察者,{@code range()}/{@code fov()}
 *       调整参数后可反复查询——行为循环内常见"每 tick 查一次视野"。</li>
 *   <li><b>无状态纯读</b>:仅读取实体朝向与世界方块,线程安全,任意线程可查。</li>
 *   <li>默认参数与 {@link VisionSensor} 一致:16 格 / 90° FOV。</li>
 * </ul>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 僵尸 24 格 120° 视野内能否看到玩家
 * if (ai.see(zombie).range(24).fov(120).canSee(player)) {
 *     ai.chase(zombie, player).fire();
 * }
 * // 哨塔 360° 感知
 * if (ai.see(guard).range(32).canSee360(intruder)) {
 *     alarm.trigger();
 * }
 * }</pre>
 *
 * @see VisionSensor
 */
public final class SeeQuery {

    private final Entity self;

    private double range = VisionSensor.DEFAULT_MAX_DISTANCE;
    private double fov = VisionSensor.DEFAULT_FIELD_OF_VIEW;

    /**
     * 构造查询器。
     *
     * @param self 观察者
     */
    public SeeQuery(Entity self) {
        this.self = self;
    }

    /**
     * 设置最大视野距离(方块,水平)。
     *
     * @param blocks 距离
     * @return this
     */
    public SeeQuery range(double blocks) {
        this.range = blocks;
        return this;
    }

    /**
     * 设置全视野角度(度)。≥360 表示全向(跳过角度判断)。
     *
     * @param degrees 全视野角度
     * @return this
     */
    public SeeQuery fov(double degrees) {
        this.fov = degrees;
        return this;
    }

    /**
     * 判断能否看到目标实体(距离 + 当前朝向 FOV + 视线)。
     *
     * @param target 被观察目标
     * @return true 表示目标在视野内
     */
    public boolean canSee(Entity target) {
        return VisionSensor.canSee(self, target, range, fov);
    }

    /**
     * 判断"转头能否看到"指定坐标点(距离 + 视线,不受当前朝向约束)。
     *
     * @param point 目标坐标点
     * @return true 表示转头即可看到该点
     */
    public boolean canSee(Vector3 point) {
        return VisionSensor.canSee(self, point, range);
    }

    /**
     * 全向感知判断(距离 + 视线,不限朝向)。
     *
     * @param target 被观察目标
     * @return true 表示目标在感知范围内且视线畅通
     */
    public boolean canSee360(Entity target) {
        return VisionSensor.canSee360(self, target, range);
    }

    /**
     * 计算目标相对观察者朝向的水平夹角。
     *
     * @param target 目标
     * @return 夹角度数 [0, 180]:0 正前方,90 正侧方,180 正后方
     */
    public double angleTo(Entity target) {
        return VisionSensor.angleTo(self, target);
    }
}
