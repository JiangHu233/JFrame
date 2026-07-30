package io.github.JiangHu.jframe.ai.pathfinding;

/**
 * 启发式估值函数类型，用于 A* 的 h(n) 计算。
 * <p>
 * 启发函数必须是<b>可采纳的</b>（admissible），即永不高估实际代价，
 * 这样 A* 才能保证找到最优路径。不同函数在"精度"与"计算成本"间取舍不同：
 * <ul>
 *   <li>{@link #MANHATTAN}：仅允许上下左右移动时最精确；允许对角线移动时会高估（不严格可采纳），
 *       但寻路更快、更"贪婪"，适合追求响应速度的场景。</li>
 *   <li>{@link #EUCLIDEAN}：三维直线距离，对任意移动方向都是可采纳的下界，精度最高。</li>
 *   <li>{@link #CHEBYSHEV}：切比雪夫距离（八连通网格的精确代价），适合允许对角线且无高度差的平面寻路。</li>
 * </ul>
 *
 * <h3>选型建议</h3>
 * <ul>
 *   <li>默认用 {@link #MANHATTAN}（速度快、对 MC 方块世界效果好）</li>
 *   <li>需要严格最优路径时用 {@link #EUCLIDEAN}</li>
 *   <li>大范围平面寻路用 {@link #CHEBYSHEV}</li>
 * </ul>
 *
 * @see PathFinder
 * @see PathfinderOptions
 */
public enum HeuristicType {

    /**
     * 曼哈顿距离：|dx| + |dy| + |dz|。
     * <p>
     * 计算最廉价。在仅四向移动时是精确代价；允许对角线时会略微高估，
     * 使 A* 更快收敛但路径可能非严格最优。这是 Minecraft 方块寻路最常用的选择。
     */
    MANHATTAN {
        @Override
        public double estimate(int dx, int dy, int dz) {
            return Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
        }
    },

    /**
     * 欧几里得距离：√(dx² + dy² + dz²)。
     * <p>
     * 对任意移动方向都是可采纳的下界，路径质量最高，但含开方运算成本略高。
     */
    EUCLIDEAN {
        @Override
        public double estimate(int dx, int dy, int dz) {
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    },

    /**
     * 切比雪夫距离：max(|dx|, |dy|, |dz|)。
     * <p>
     * 八连通网格（含对角线）单步代价为 1 时的精确启发值，
     * 在无高度变化的平面寻路中既快又精确。
     */
    CHEBYSHEV {
        @Override
        public double estimate(int dx, int dy, int dz) {
            return Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
        }
    };

    /**
     * 计算两个节点间的启发式估值。
     *
     * @param dx X 坐标差
     * @param dy Y 坐标差
     * @param dz Z 坐标差
     * @return 启发式估值 h(n)
     */
    public abstract double estimate(int dx, int dy, int dz);
}
