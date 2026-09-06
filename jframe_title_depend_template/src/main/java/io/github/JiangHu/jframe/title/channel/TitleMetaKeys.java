package io.github.JiangHu.jframe.title.channel;

import java.util.Set;

/**
 * title 模块元数据 key 常量（title.* 命名空间）
 *
 * <p>对应模板中 {@code <meta key="..." value="..."/>} 声明，
 * 由 {@link TitleMetaResolver} 消费。非 title.* 前缀的元数据
 * 属于其他模块命名空间，title 侧静默忽略；title.* 前缀但未知的
 * key 记录告警日志（不抛异常）。</p>
 */
public final class TitleMetaKeys {

    /** 命名空间前缀 */
    public static final String PREFIX = "title.";

    /** 副标题区间起点（含），默认 0 */
    public static final String SUBTITLE_FROM = PREFIX + "subtitle.from";

    /** 副标题区间终点（不含），默认 1 */
    public static final String SUBTITLE_TO = PREFIX + "subtitle.to";

    /** 动作栏行号，默认 1；-1 表示禁用动作栏通道 */
    public static final String ACTIONBAR_LINE = PREFIX + "actionbar.line";

    /** 淡入时长（tick），默认 {@link io.github.JiangHu.jframe.title.TitleConstants#DEFAULT_FADE_IN} */
    public static final String TIMING_FADE_IN = PREFIX + "timing.fadeIn";

    /** 停留时长（tick），默认 {@link io.github.JiangHu.jframe.title.TitleConstants#DEFAULT_STAY} */
    public static final String TIMING_STAY = PREFIX + "timing.stay";

    /** 淡出时长（tick），默认 {@link io.github.JiangHu.jframe.title.TitleConstants#DEFAULT_FADE_OUT} */
    public static final String TIMING_FADE_OUT = PREFIX + "timing.fadeOut";

    /** 显示模式：persistent / transient，默认 persistent */
    public static final String MODE = PREFIX + "mode";

    /** 瞬时模式到期是否自动清除标题：true / false，默认 true */
    public static final String AUTOCLEAR = PREFIX + "autoclear";

    /** 动作栏周期续期间隔（tick），0 表示不续期，默认 0 */
    public static final String ACTIONBAR_REFRESH = PREFIX + "actionbar.refresh";

    /** title 模块已知 key 全集 */
    private static final Set<String> KNOWN_KEYS = Set.of(
            SUBTITLE_FROM,
            SUBTITLE_TO,
            ACTIONBAR_LINE,
            TIMING_FADE_IN,
            TIMING_STAY,
            TIMING_FADE_OUT,
            MODE,
            AUTOCLEAR,
            ACTIONBAR_REFRESH);

    private TitleMetaKeys() {
    }

    /** 判断 key 是否属于 title 命名空间（以 title. 开头） */
    public static boolean isTitleKey(String key) {
        return key != null && key.startsWith(PREFIX);
    }

    /** 判断 key 是否为 title 模块已定义的 key */
    public static boolean isKnownKey(String key) {
        return KNOWN_KEYS.contains(key);
    }
}
