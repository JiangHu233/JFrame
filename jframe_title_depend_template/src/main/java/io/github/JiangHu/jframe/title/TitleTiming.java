package io.github.JiangHu.jframe.title;

import java.util.Objects;

/**
 * 标题时序参数（淡入 / 停留 / 淡出，单位 tick）
 *
 * <p>不可变值对象；瞬时模式（{@link TitleMode#TRANSIENT}）下
 * {@link #totalTicks()} 即为视图的存活时长（到期自动清除并释放）。</p>
 */
public final class TitleTiming {

    /** 内置默认时序：10 / 70 / 20（tick） */
    public static final TitleTiming DEFAULT =
            new TitleTiming(TitleConstants.DEFAULT_FADE_IN, TitleConstants.DEFAULT_STAY, TitleConstants.DEFAULT_FADE_OUT);

    private final int fadeIn;
    private final int stay;
    private final int fadeOut;

    private TitleTiming(int fadeIn, int stay, int fadeOut) {
        this.fadeIn = fadeIn;
        this.stay = stay;
        this.fadeOut = fadeOut;
    }

    /**
     * 构造时序参数
     *
     * @param fadeIn  淡入时长（tick），不得为负
     * @param stay    停留时长（tick），不得为负
     * @param fadeOut 淡出时长（tick），不得为负
     * @return 时序对象
     * @throws IllegalArgumentException 任一时长为负
     */
    public static TitleTiming of(int fadeIn, int stay, int fadeOut) {
        if (fadeIn < 0 || stay < 0 || fadeOut < 0) {
            throw new IllegalArgumentException(
                    "标题时序参数不得为负：fadeIn=" + fadeIn + ", stay=" + stay + ", fadeOut=" + fadeOut);
        }
        return new TitleTiming(fadeIn, stay, fadeOut);
    }

    /** 淡入时长（tick） */
    public int getFadeIn() {
        return fadeIn;
    }

    /** 停留时长（tick） */
    public int getStay() {
        return stay;
    }

    /** 淡出时长（tick） */
    public int getFadeOut() {
        return fadeOut;
    }

    /** 总时长（fadeIn + stay + fadeOut，tick），瞬时模式下的视图存活时长 */
    public int totalTicks() {
        return fadeIn + stay + fadeOut;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TitleTiming that)) {
            return false;
        }
        return fadeIn == that.fadeIn && stay == that.stay && fadeOut == that.fadeOut;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fadeIn, stay, fadeOut);
    }

    @Override
    public String toString() {
        return "TitleTiming{fadeIn=" + fadeIn + ", stay=" + stay + ", fadeOut=" + fadeOut + '}';
    }
}
