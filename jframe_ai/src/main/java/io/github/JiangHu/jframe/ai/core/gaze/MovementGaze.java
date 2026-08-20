package io.github.JiangHu.jframe.ai.core.gaze;

/**
 * 移动方向修正器(默认):头与身体均朝移动方向。
 * <p>
 * 行为与旧版导航器一致并补齐 {@code headYaw} 写入(修复"身体转了、头保持旧朝向"的侧头问题)。
 * 未在移动时保持实体当前朝向。无状态,可随意共享或每导航器 new。
 *
 * <pre>{@code
 * ai.walk(zombie).to(pos).gaze(new MovementGaze()).start(); // 显式指定(等价于默认)
 * }</pre>
 */
public class MovementGaze implements Gaze {

    @Override
    public void apply(GazeContext ctx) {
        double yaw = ctx.movementYaw();
        ctx.entity().yaw = yaw;
        ctx.entity().headYaw = yaw;
    }
}
