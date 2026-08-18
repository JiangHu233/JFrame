package io.github.JiangHu.jframe.ai.core.tactical;

/**
 * 阵型类型：团队战术中实体编队的排列方式。
 * <p>
 * 由 {@link TeamTactics#rally} 与 {@link TeamTactics#formationOffsets} 使用，
 * 决定多个实体围绕集结点/领队的几何分布。
 *
 * <h3>各阵型示意（▲ 为领队/集结点，● 为成员，→ 为朝向）</h3>
 * <pre>
 * LINE（横排）      COLUMN（纵队）     WEDGE（楔形）       CIRCLE（圆形）
 * ● ● ▲ ● ●         ●                 ●           ●     ●
 *                   ●                  ● ●       ●   ▲
 *                   ▲                 ● ● ● ●     ●     ●
 *                   ↑朝向               ↑朝向
 * </pre>
 *
 * @see TeamTactics
 */
public enum FormationType {
    /**
     * 横排：成员在集结点两侧水平一字排开，垂直于朝向。
     * <p>
     * 适合正面冲锋、列队展示。
     */
    LINE,

    /**
     * 纵队：成员在集结点前后排成单列，沿朝向延伸。
     * <p>
     * 适合行军、穿越狭窄通道。
     */
    COLUMN,

    /**
     * 楔形（V 字）：成员呈 V 字形，尖端在前、两翼向后展开。
     * <p>
     * 适合进攻推进，兼顾正面火力与侧翼保护。
     */
    WEDGE,

    /**
     * 圆形：成员均匀分布在集结点周围的圆周上。
     * <p>
     * 适合防御、护卫、包围。
     */
    CIRCLE,

    /**
     * 方阵：成员排列成接近正方形的网格。
     * <p>
     * 适合大规模集结、密集防御。
     */
    SQUARE
}
