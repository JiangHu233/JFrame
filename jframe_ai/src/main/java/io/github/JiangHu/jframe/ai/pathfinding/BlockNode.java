package io.github.JiangHu.jframe.ai.pathfinding;

import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.math.Vector3;

/**
 * 寻路节点：表示世界中的一个方块坐标，同时承载 A* 算法的运行时评分。
 * <p>
 * 本类身兼两职：
 * <ul>
 *   <li><b>坐标标识</b>：{@link #x}/{@link #y}/{@link #z} 为方块整数坐标，
 *       {@link #equals}/{@link #hashCode} 仅基于坐标，因此可作为 {@link java.util.HashMap}/{@link java.util.HashSet} 的 key。</li>
 *   <li><b>A* 节点状态</b>：{@code gCost}（起点到本节点的实际代价）、
 *       {@code hCost}（本节点到终点的启发式估值）、{@code cameFrom}（父节点，用于回溯路径）。
 *       这些字段是可变的，仅在单次寻路计算中使用，不参与相等性判断。</li>
 * </ul>
 *
 * <h3>为什么把评分字段放进节点？</h3>
 * 相比"坐标 + 外部评分表"的方案，把 {@code gCost}/{@code cameFrom} 内聚到节点中，
 * 可让 {@link PathFinder} 的 A* 主循环更简洁（无需维护多张映射），
 * 同时节点仍能安全地作为集合元素（相等性只看坐标）。
 *
 * <h3>方块坐标语义</h3>
 * 本节点表示的是实体<b>脚部所在方块</b>的位置（即站立格）。寻路时：
 * <ul>
 *   <li>脚部格 {@code (x,y,z)} 与头部格 {@code (x,y+1,z)} 必须可穿过（空气等）</li>
 *   <li>脚下格 {@code (x,y-1,z)} 必须是可站立的固体方块</li>
 * </ul>
 *
 * @see PathFinder
 */
public class BlockNode {

    /** 方块 X 坐标 */
    public final int x;
    /** 方块 Y 坐标（脚部高度） */
    public final int y;
    /** 方块 Z 坐标 */
    public final int z;

    /** 从起点到本节点的实际累计代价（A* 的 g(n)） */
    public double gCost;
    /** 从本节点到终点的启发式估值（A* 的 h(n)），缓存以避免重复计算 */
    public double hCost;

    /** 父节点，用于在寻路结束后回溯出完整路径 */
    public BlockNode cameFrom;

    /**
     * 构造节点。
     *
     * @param x 方块 X 坐标
     * @param y 方块 Y 坐标（脚部）
     * @param z 方块 Z 坐标
     */
    public BlockNode(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * 估算总代价 f(n) = g(n) + h(n)。
     *
     * @return f 值
     */
    public double fCost() {
        return gCost + hCost;
    }

    /**
     * 将本节点转换为世界坐标（脚部方块中心，X/Z 取方块中心 +0.5，Y 取整数高度）。
     *
     * @param level 所在世界
     * @return 世界位置
     */
    public Position toPosition(Level level) {
        return new Position(x + 0.5, y, z + 0.5, level);
    }

    /**
     * 计算本节点到另一节点的水平距离平方（忽略 Y），用于评估"是否到达"。
     *
     * @param other 另一节点
     * @return 水平距离平方
     */
    public double horizontalDistanceSquared(BlockNode other) {
        double dx = x - other.x;
        double dz = z - other.z;
        return dx * dx + dz * dz;
    }

    /**
     * 计算本节点到另一节点的三维欧几里得距离。
     *
     * @param other 另一节点
     * @return 三维距离
     */
    public double distance3D(BlockNode other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 从世界坐标构造节点（向下取整为方块坐标）。
     *
     * @param pos 世界坐标
     * @return 节点
     */
    public static BlockNode fromVector(Vector3 pos) {
        return new BlockNode(pos.getFloorX(), pos.getFloorY(), pos.getFloorZ());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockNode other)) return false;
        return x == other.x && y == other.y && z == other.z;
    }

    @Override
    public int hashCode() {
        // 将三维坐标打包成一个 int，参考 Nukkit 的 hash 坐标做法
        return (x ^ (z << 12)) ^ (y << 24);
    }

    @Override
    public String toString() {
        return "BlockNode{x=" + x + ", y=" + y + ", z=" + z + ", g=" + gCost + ", h=" + hCost + "}";
    }
}
