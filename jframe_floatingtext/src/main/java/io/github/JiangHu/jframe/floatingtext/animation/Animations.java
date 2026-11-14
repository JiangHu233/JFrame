package io.github.JiangHu.jframe.floatingtext.animation;

import io.github.JiangHu.jframe.floatingtext.FloatingText;

import java.util.function.Consumer;

/**
 * 内置动画工厂 — {@code none}（静止，默认）与 {@code rise}（上浮 + 颜色阶淡出）。
 *
 * <p>用法：
 * <pre>{@code
 * FloatingText.builder().animation(Animations.none())
 * FloatingText.builder().animation(Animations.rise(b -> b.height(1.2).durationTicks(30)))
 * }</pre>
 */
public final class Animations {

    private static final FloatingTextAnimation NONE = (text, ageTicks) -> true;

    private Animations() {
    }

    /**
     * 静止动画（默认）— 零写回零运动开销，条目只做寿命倒数（脚步场景用）。
     *
     * @return 动画
     */
    public static FloatingTextAnimation none() {
        return NONE;
    }

    /**
     * 上浮动画（默认参数：高 1.0 格 / 20 拍 / 缓出 / 淡出）。
     *
     * @return 动画
     */
    public static FloatingTextAnimation rise() {
        return new RiseAnimation(1.0, 20, true, true);
    }

    /**
     * 上浮动画（Builder 配置）。
     *
     * @param config 配置回调
     * @return 动画
     */
    public static FloatingTextAnimation rise(Consumer<RiseBuilder> config) {
        RiseBuilder builder = new RiseBuilder();
        config.accept(builder);
        return builder.build();
    }

    /**
     * rise 参数 Builder。
     */
    public static final class RiseBuilder {
        private double height = 1.0;
        private int durationTicks = 20;
        private boolean easeOut = true;
        private boolean fade = true;

        /**
         * 总上浮高度（格）。
         *
         * @param height 高度
         * @return self
         */
        public RiseBuilder height(double height) {
            this.height = height;
            return this;
        }

        /**
         * 动画时长（tick）。
         *
         * @param durationTicks 拍数
         * @return self
         */
        public RiseBuilder durationTicks(int durationTicks) {
            this.durationTicks = Math.max(1, durationTicks);
            return this;
        }

        /**
         * 缓出（先快后慢）。
         *
         * @param easeOut 开启
         * @return self
         */
        public RiseBuilder easeOut(boolean easeOut) {
            this.easeOut = easeOut;
            return this;
        }

        /**
         * 尾段颜色阶淡出（Bedrock 无 nameTag alpha，用 §8 暗灰模拟）。
         *
         * @param fade 开启
         * @return self
         */
        public RiseBuilder fade(boolean fade) {
            this.fade = fade;
            return this;
        }

        /**
         * 构建 rise 动画。
         *
         * @return 动画
         */
        public FloatingTextAnimation build() {
            return new RiseAnimation(height, Math.max(1, durationTicks), easeOut, fade);
        }
    }

    /**
     * 上浮动效实现（包级私有）。
     */
    private static final class RiseAnimation implements FloatingTextAnimation {
        private final double height;
        private final int durationTicks;
        private final boolean easeOut;
        private final boolean fade;

        private RiseAnimation(double height, int durationTicks, boolean easeOut, boolean fade) {
            this.height = height;
            this.durationTicks = durationTicks;
            this.easeOut = easeOut;
            this.fade = fade;
        }

        @Override
        public boolean tick(FloatingText text, int ageTicks) {
            if (ageTicks >= durationTicks) {
                return false;
            }
            double progress = (double) ageTicks / durationTicks;
            if (easeOut) {
                progress = 1 - (1 - progress) * (1 - progress);
            }
            text.offset(0, height * progress, 0);
            if (fade && ageTicks >= durationTicks * 3 / 4) {
                text.setText("§8" + text.text());
            }
            return true;
        }
    }
}
