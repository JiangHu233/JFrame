package io.github.JiangHu.jframe.floatingtext.animation;

import io.github.JiangHu.jframe.floatingtext.FloatingText;

/**
 * 浮空字动画 SPI — 每拍由管理器调用，经句柄写回效果，返回是否继续。
 *
 * <h3>写回面（句柄方法）</h3>
 * <ul>
 *   <li>{@link FloatingText#offset(double, double, double)} — 位置偏移（绝对累计值，
 *       与位置源相加后按 {@code moveIntervalTicks} 节流提交 Move 包）；</li>
 *   <li>{@link FloatingText#setText(String)} — 文本变换（如颜色阶淡出、倒计时数字），
 *       与位置同节流提交 SetEntityData 包；</li>
 * </ul>
 *
 * <h3>生命周期</h3>
 * 返回 false 表示动画结束（此后不再调用）；<b>条目寿命仍由 lifetimeTicks 支配</b>——
 * {@link Animations#none()} 永远返回 true 且零写回，即为「静止倒计时」路径。
 *
 * <p>自定义动画（脚步、粒子、数字滚动）实现本接口即可，每条浮空字各持一个实例，
 * 动画状态存实例字段互不干扰。
 */
@FunctionalInterface
public interface FloatingTextAnimation {

    /**
     * 推进一拍。
     *
     * @param text     浮空字句柄（写回 offset / setText）
     * @param ageTicks 已存活拍数（从 1 开始）
     * @return false = 动画结束
     */
    boolean tick(FloatingText text, int ageTicks);
}
