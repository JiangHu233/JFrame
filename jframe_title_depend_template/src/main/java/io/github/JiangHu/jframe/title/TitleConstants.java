package io.github.JiangHu.jframe.title;

/**
 * title 模块公共常量
 *
 * <p>集中管理时序默认值、通道映射默认区间与建议上限，
 * 供 {@link TitleTemplate} 三级配置融合与元数据解析兜底使用。</p>
 */
public final class TitleConstants {

    /** 默认淡入时长（tick） */
    public static final int DEFAULT_FADE_IN = 10;

    /** 默认停留时长（tick） */
    public static final int DEFAULT_STAY = 70;

    /** 默认淡出时长（tick） */
    public static final int DEFAULT_FADE_OUT = 20;

    /** 默认副标题区间起点（含）：lines[0] 为副标题 */
    public static final int DEFAULT_SUBTITLE_FROM = 0;

    /** 默认副标题区间终点（不含）：默认区间 [0,1) 仅取 lines[0] */
    public static final int DEFAULT_SUBTITLE_TO = 1;

    /** 默认动作栏行号：lines[1] 为动作栏 */
    public static final int DEFAULT_ACTIONBAR_LINE = 1;

    /** 动作栏禁用标记值 */
    public static final int ACTIONBAR_DISABLED = -1;

    /** 默认动作栏周期续期间隔（tick）；0 表示不续期 */
    public static final int DEFAULT_ACTIONBAR_REFRESH_INTERVAL = 40;

    /** 建议的副标题最大行数（超过仅告警，不阻断） */
    public static final int SUGGESTED_MAX_SUBTITLE_LINES = 8;

    private TitleConstants() {
    }
}
