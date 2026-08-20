package io.github.JiangHu.jframe.ai.core.gaze;

/**
 * 固定朝向修正器:头与身体始终朝指定 yaw(pitch 不动)。
 * <p>
 * 适合朝固定方向警戒、面向观众台表演等场景。
 *
 * <pre>{@code
 * ai.walk(guard).to(post).gaze(new FixedGaze(90)).start(); // 朝 -X 方向警戒
 * }</pre>
 */
public class FixedGaze implements Gaze {

    private final double yaw;

    /**
     * 构造固定朝向。
     *
     * @param yaw 朝向角(度;Nukkit 约定 0 朝 +Z,顺时针为正)
     */
    public FixedGaze(double yaw) {
        this.yaw = yaw;
    }

    @Override
    public void apply(GazeContext ctx) {
        ctx.entity().yaw = yaw;
        ctx.entity().headYaw = yaw;
    }
}
