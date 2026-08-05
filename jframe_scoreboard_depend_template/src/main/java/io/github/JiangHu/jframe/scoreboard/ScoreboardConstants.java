package io.github.JiangHu.jframe.scoreboard;

import cn.nukkit.network.protocol.types.DisplaySlot;
import cn.nukkit.network.protocol.types.SortOrder;

/**
 * 计分板模块常量。
 *
 * <p>集中管理 Nukkit 计分板相关的默认值与前缀，避免魔法字符串散落各处。
 *
 * @see DisplaySlot
 * @see SortOrder
 */
public final class ScoreboardConstants {

    private ScoreboardConstants() {}

    /** Nukkit objectiveName 前缀——每个玩家的计分板使用 {@code PREFIX + UUID} 保证唯一性 */
    public static final String OBJECTIVE_NAME_PREFIX = "jframe_sb_";

    /** 默认 criteriaName（Nukkit 计分板的判定准则，{@code dummy} 表示不由游戏事件驱动） */
    public static final String DEFAULT_CRITERIA = "dummy";

    /** 默认显示槽位（侧边栏） */
    public static final DisplaySlot DEFAULT_DISPLAY_SLOT = DisplaySlot.SIDEBAR;

    /** 默认排序方式（降序——第一行显示在计分板顶部） */
    public static final SortOrder DEFAULT_SORT_ORDER = SortOrder.DESCENDING;

    /** 计分板最大行数（Nukkit 侧边栏限制） */
    public static final int MAX_LINES = 15;

    /** 行文本最大长度（Nukkit 侧边栏单行字符限制） */
    public static final int MAX_LINE_LENGTH = 30;

    /**
     * 增量更新阈值——变更行数 ≤ 此值时使用逐行 removeLine+addLine 更新，
     * 超过此值时回退全量 setLines。
     * <p>逐行更新的网络开销为 {@code 2 × changedLines} 个数据包，
     * 全量 setLines 的开销为 {@code 2 × totalLines} 个数据包。
     * 当 {@code changedLines ≤ totalLines / 2} 时逐行更优，此阈值取保守值 3。
     */
    public static final int INCREMENTAL_LINE_THRESHOLD = 3;
}
