package io.github.JiangHu.jframe.ai.tactical;

import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;

/**
 * 战术位置扫描结果：表示一个满足某种战术意图的候选位置及其评分。
 * <p>
 * 由 {@link TacticalScanner} 的各 {@code findXxx} 方法返回，描述<b>为何</b>该位置
 * 适合该战术（评分、与威胁/目标的距离等），供上层决策或调试使用。
 *
 * <h3>评分语义</h3>
 * 评分越高表示该位置越符合战术意图（具体含义由产生它的战术方法决定）：
 * <ul>
 *   <li>找掩体：评分 = 掩体遮挡质量（被阻挡的视线方向数等）</li>
 *   <li>远离：评分 = 与威胁的距离</li>
 *   <li>包抄：评分 = 偏离正面的程度（越接近侧后方越高）</li>
 *   <li>高地：评分 = 相对当前的高度优势</li>
 * </ul>
 *
 * @param position 目标位置（实体脚部，世界坐标）
 * @param score    评分（越高越优）
 * @param distanceToThreat 与威胁/目标的水平距离（方块）
 */
public record TacticalPosition(Vector3 position, double score, double distanceToThreat) {

    /**
     * 当扫描未找到任何满足条件的候选位置时返回的空结果。
     *
     * @return 表示"无可用位置"的结果
     */
    public static TacticalPosition empty() {
        return new TacticalPosition(null, -1.0, -1.0);
    }

    /**
     * 是否找到了有效位置。
     *
     * @return true 表示存在有效候选位置
     */
    public boolean isPresent() {
        return position != null;
    }

    /**
     * 将该战术位置转换为 {@link io.github.JiangHu.jframe.ai.pathfinding.BlockNode} 脚部坐标。
     *
     * @return 脚部方块坐标节点
     */
    public io.github.JiangHu.jframe.ai.pathfinding.BlockNode toBlockNode() {
        if (!isPresent()) {
            return null;
        }
        return new io.github.JiangHu.jframe.ai.pathfinding.BlockNode(
                (int) Math.floor(position.x),
                (int) Math.floor(position.y),
                (int) Math.floor(position.z));
    }

    /**
     * 在指定世界生成一个用于实体定位的 {@link cn.nukkit.level.Position}。
     *
     * @param level 世界
     * @return 世界坐标位置（方块中心）
     */
    public cn.nukkit.level.Position toLevelPosition(Level level) {
        if (!isPresent()) {
            return null;
        }
        return new cn.nukkit.level.Position(
                Math.floor(position.x) + 0.5,
                Math.floor(position.y),
                Math.floor(position.z) + 0.5,
                level);
    }
}
