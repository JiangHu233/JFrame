package io.github.JiangHu.jframe.title;

import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.title.channel.TitleMetaConfig;
import io.github.JiangHu.jframe.title.channel.TitleMetaResolver;

import java.util.Objects;

/**
 * 标题模板：{@link Template} + 时序 + 通道映射的不可变打包
 *
 * <p>三级配置融合（优先级从高到低）：</p>
 * <ol>
 *   <li>Builder 显式设置（{@code timing(...)} / {@code subtitleLines(...)} 等）；</li>
 *   <li>模板元数据（{@code <meta key="title.*" value="..."/>}，经 {@link TitleMetaResolver} 解析）；</li>
 *   <li>内置默认（{@link TitleConstants}：时序 10/70/20，副标题 [0,1)，动作栏 lines[1]，
 *       常驻模式，到期自动清除，不续期）。</li>
 * </ol>
 *
 * <p>build 时执行 fail-fast 校验：副标题区间起点非负、起点 &le; 终点、
 * 动作栏行号不得落入副标题区间（通道互斥）等。</p>
 */
public final class TitleTemplate {

    private static final String TAG = "TitleTemplate";

    private final String name;
    private final Template template;
    private final TitleTiming timing;
    private final TitleMode mode;
    private final int subtitleFrom;
    private final int subtitleTo;
    private final int actionbarLine;
    private final boolean autoClear;
    private final int actionBarRefreshInterval;

    private TitleTemplate(String name, Template template, TitleTiming timing, TitleMode mode,
                          int subtitleFrom, int subtitleTo, int actionbarLine,
                          boolean autoClear, int actionBarRefreshInterval) {
        this.name = name;
        this.template = template;
        this.timing = timing;
        this.mode = mode;
        this.subtitleFrom = subtitleFrom;
        this.subtitleTo = subtitleTo;
        this.actionbarLine = actionbarLine;
        this.autoClear = autoClear;
        this.actionBarRefreshInterval = actionBarRefreshInterval;
    }

    /**
     * 以默认融合策略打包模板（无 Builder 显式覆盖）
     *
     * @param name     模板名
     * @param template 引擎模板
     * @return 标题模板
     */
    public static TitleTemplate of(String name, Template template) {
        return builder(name, template).build();
    }

    /**
     * 创建 Builder
     *
     * @param name     模板名（非空）
     * @param template 引擎模板（非空）
     * @return Builder 实例
     */
    public static Builder builder(String name, Template template) {
        return new Builder(name, template);
    }

    /** 模板名 */
    public String getName() {
        return name;
    }

    /** 引擎模板 */
    public Template getTemplate() {
        return template;
    }

    /** 时序参数（融合结果） */
    public TitleTiming getTiming() {
        return timing;
    }

    /** 显示模式（融合结果） */
    public TitleMode getMode() {
        return mode;
    }

    /** 副标题区间起点（含） */
    public int getSubtitleFrom() {
        return subtitleFrom;
    }

    /** 副标题区间终点（不含） */
    public int getSubtitleTo() {
        return subtitleTo;
    }

    /** 动作栏行号；-1 表示禁用动作栏通道 */
    public int getActionbarLine() {
        return actionbarLine;
    }

    /** 瞬时模式到期是否自动清除标题 */
    public boolean isAutoClear() {
        return autoClear;
    }

    /** 动作栏周期续期间隔（tick）；0 表示不续期 */
    public int getActionBarRefreshInterval() {
        return actionBarRefreshInterval;
    }

    /**
     * 是否占用 TITLE 槽位（title / subtitle 通道）
     *
     * <p>副标题区间非空即占用；区间为空（from == to）的模板
     * （如仅动作栏的常驻信息栏）不参与 TITLE 槽位争用，
     * 与仅占 ACTIONBAR 槽位的其他模板可双槽共存。</p>
     */
    public boolean occupiesTitleSlot() {
        return subtitleFrom < subtitleTo;
    }

    /** 是否占用 ACTIONBAR 槽位（动作栏通道启用） */
    public boolean occupiesActionBarSlot() {
        return actionbarLine >= 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TitleTemplate that)) {
            return false;
        }
        return name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "TitleTemplate{name='" + name + "', mode=" + mode + ", timing=" + timing
                + ", subtitle=[" + subtitleFrom + "," + subtitleTo + ")"
                + ", actionbarLine=" + actionbarLine + ", autoClear=" + autoClear
                + ", actionBarRefresh=" + actionBarRefreshInterval + '}';
    }

    /**
     * 标题模板 Builder：显式设置项参与三级融合的最高优先级
     */
    public static final class Builder {

        private final String name;
        private final Template template;

        private TitleTiming timing;
        private TitleMode mode;
        private Integer subtitleFrom;
        private Integer subtitleTo;
        private Integer actionbarLine;
        private Boolean autoClear;
        private Integer actionBarRefreshInterval;

        private Builder(String name, Template template) {
            this.name = Objects.requireNonNull(name, "模板名不得为 null");
            this.template = Objects.requireNonNull(template, "引擎模板不得为 null");
        }

        /** 显式设置整体时序（覆盖元数据与默认值） */
        public Builder timing(TitleTiming timing) {
            this.timing = timing;
            return this;
        }

        /** 显式设置显示模式 */
        public Builder mode(TitleMode mode) {
            this.mode = mode;
            return this;
        }

        /** 显式设置副标题区间 [from, to)（半开区间，多行副标题） */
        public Builder subtitleLines(int from, int to) {
            this.subtitleFrom = from;
            this.subtitleTo = to;
            return this;
        }

        /** 显式设置动作栏行号；-1 表示禁用动作栏通道 */
        public Builder actionbarLine(int line) {
            this.actionbarLine = line;
            return this;
        }

        /** 显式设置瞬时模式到期是否自动清除标题 */
        public Builder autoClear(boolean autoClear) {
            this.autoClear = autoClear;
            return this;
        }

        /** 显式设置动作栏周期续期间隔（tick）；0 表示不续期 */
        public Builder actionBarRefresh(int intervalTicks) {
            this.actionBarRefreshInterval = intervalTicks;
            return this;
        }

        /**
         * 执行三级融合与 fail-fast 校验，产出不可变模板
         *
         * @return 标题模板
         * @throws IllegalArgumentException 元数据非法，或融合结果违反通道互斥等约束
         */
        public TitleTemplate build() {
            TitleMetaConfig meta = TitleMetaResolver.resolve(template, name);

            // 时序：Builder 整体 > 元数据逐 key > 默认
            TitleTiming finalTiming;
            if (timing != null) {
                finalTiming = timing;
            } else if (meta.hasAnyTiming()) {
                finalTiming = TitleTiming.of(
                        meta.timingFadeIn().orElse(TitleConstants.DEFAULT_FADE_IN),
                        meta.timingStay().orElse(TitleConstants.DEFAULT_STAY),
                        meta.timingFadeOut().orElse(TitleConstants.DEFAULT_FADE_OUT));
            } else {
                finalTiming = TitleTiming.DEFAULT;
            }

            TitleMode finalMode = mode != null ? mode
                    : meta.mode().orElse(TitleMode.PERSISTENT);

            int finalFrom = subtitleFrom != null ? subtitleFrom
                    : meta.subtitleFrom().orElse(TitleConstants.DEFAULT_SUBTITLE_FROM);
            int finalTo = subtitleTo != null ? subtitleTo
                    : meta.subtitleTo().orElse(TitleConstants.DEFAULT_SUBTITLE_TO);
            int finalActionbar = actionbarLine != null ? actionbarLine
                    : meta.actionbarLine().orElse(TitleConstants.DEFAULT_ACTIONBAR_LINE);
            boolean finalAutoClear = autoClear != null ? autoClear
                    : meta.autoClear().orElse(true);
            int finalRefresh = actionBarRefreshInterval != null ? actionBarRefreshInterval
                    : meta.actionBarRefreshInterval().orElse(0);

            validate(finalFrom, finalTo, finalActionbar, finalRefresh);

            if (finalTo - finalFrom > TitleConstants.SUGGESTED_MAX_SUBTITLE_LINES) {
                TitleLog.warning(TAG, "模板[" + name + "]副标题行数 " + (finalTo - finalFrom)
                        + " 超过建议上限 " + TitleConstants.SUGGESTED_MAX_SUBTITLE_LINES
                        + "，客户端可能显示异常");
            }

            return new TitleTemplate(name, template, finalTiming, finalMode,
                    finalFrom, finalTo, finalActionbar, finalAutoClear, finalRefresh);
        }

        private void validate(int from, int to, int actionbar, int refresh) {
            if (from < 0) {
                throw new IllegalArgumentException("模板[" + name + "]副标题区间起点不得为负：" + from);
            }
            if (to < from) {
                throw new IllegalArgumentException("模板[" + name + "]副标题区间终点（" + to
                        + "）不得小于起点（" + from + "）");
            }
            if (actionbar < TitleConstants.ACTIONBAR_DISABLED) {
                throw new IllegalArgumentException("模板[" + name + "]动作栏行号非法：" + actionbar
                        + "（应为 >= 0 的行号或 -1 表示禁用）");
            }
            if (actionbar >= from && actionbar < to) {
                throw new IllegalArgumentException("模板[" + name + "]动作栏行号 " + actionbar
                        + " 与副标题区间 [" + from + "," + to + ") 重叠（通道互斥约束）");
            }
            if (refresh < 0) {
                throw new IllegalArgumentException("模板[" + name + "]动作栏续期间隔不得为负：" + refresh);
            }
        }
    }
}
