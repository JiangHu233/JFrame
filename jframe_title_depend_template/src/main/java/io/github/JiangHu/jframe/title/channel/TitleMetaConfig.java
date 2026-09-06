package io.github.JiangHu.jframe.title.channel;

import io.github.JiangHu.jframe.title.TitleMode;

import java.util.OptionalInt;
import java.util.Optional;

/**
 * title 元数据解析结果值对象
 *
 * <p>仅承载模板元数据中显式声明的内容；未声明的项以空 {@link OptionalInt} /
 * {@link Optional} 表示，由 {@link io.github.JiangHu.jframe.title.TitleTemplate}
 * 在三级配置融合（Builder 显式 > 模板元数据 > 内置默认）时逐项取用。</p>
 *
 * @param subtitleFrom          副标题区间起点（含）
 * @param subtitleTo            副标题区间终点（不含）
 * @param actionbarLine         动作栏行号（-1 表示禁用）
 * @param timingFadeIn          淡入时长（tick）
 * @param timingStay            停留时长（tick）
 * @param timingFadeOut         淡出时长（tick）
 * @param mode                  显示模式
 * @param autoClear             瞬时模式到期是否自动清除标题
 * @param actionBarRefreshInterval 动作栏周期续期间隔（tick，0 表示不续期）
 */
public record TitleMetaConfig(
        OptionalInt subtitleFrom,
        OptionalInt subtitleTo,
        OptionalInt actionbarLine,
        OptionalInt timingFadeIn,
        OptionalInt timingStay,
        OptionalInt timingFadeOut,
        Optional<TitleMode> mode,
        Optional<Boolean> autoClear,
        OptionalInt actionBarRefreshInterval) {

    /** 空配置：模板未声明任何 title.* 元数据 */
    public static final TitleMetaConfig EMPTY = new TitleMetaConfig(
            OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(),
            OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(),
            Optional.empty(), Optional.empty(), OptionalInt.empty());

    /** 是否声明了任一时序参数 */
    public boolean hasAnyTiming() {
        return timingFadeIn.isPresent() || timingStay.isPresent() || timingFadeOut.isPresent();
    }
}
