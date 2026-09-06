package io.github.JiangHu.jframe.ai.core.targeting;

import cn.nukkit.math.Vector3;

import java.util.Objects;

/**
 * 静态点目标:目标位置固定不变(或由调用方显式更换)。
 * <p>
 * 最简单的 {@link Target} 实现——去往一个固定坐标。适用于巡逻点、集结点、
 * 预分配的战术位等"位置在决策时已确定"的场景。
 *
 * <h3>链式可变语义</h3>
 * <p>构造绑定初始点,{@link #point(Vector3)} 可中途改点(活引用语义:下次解析生效):
 *
 * <pre>{@code
 * PointTarget home = new PointTarget(new Vector3(100, 64, 100));
 * ai.walk(zombie).to(home).compute();
 * home.point(new Vector3(200, 64, 200));   // 中途改目的地
 * }</pre>
 */
public final class PointTarget implements Target {

    private volatile Vector3 point;

    /**
     * 创建静态点目标。
     *
     * @param point 目标点(脚部世界坐标);{@code null} 表示暂无目标({@link #get()} 返回 null)
     */
    public PointTarget(Vector3 point) {
        this.point = point;
    }

    /**
     * 链式设置/中途修改目标点。
     *
     * @param point 新目标点;{@code null} 表示目标失效
     * @return this
     */
    public PointTarget point(Vector3 point) {
        this.point = point;
        return this;
    }

    /**
     * @return 目标点快照;未设置时返回 {@code null}(无有效目标)
     */
    @Override
    public Vector3 get() {
        Vector3 p = point;
        return p == null ? null : new Vector3(p.x, p.y, p.z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PointTarget that)) {
            return false;
        }
        return Objects.equals(point, that.point);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(point);
    }

    @Override
    public String toString() {
        return "PointTarget{" + point + '}';
    }
}
