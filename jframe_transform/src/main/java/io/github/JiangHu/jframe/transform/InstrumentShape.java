package io.github.JiangHu.jframe.transform;

/**
 * 已织入形状（相位省略结果）：未被占用的相位完全不内联 dispatch 指令。
 * <ul>
 *   <li>{@link #NONE} — 无任何织入（已还原）</li>
 *   <li>{@link #ENTER} — 仅方法体头部（before / REPLACE）</li>
 *   <li>{@link #EXIT} — 仅方法体尾部（after-only）</li>
 *   <li>{@link #BOTH} — 头尾皆有</li>
 * </ul>
 */
public enum InstrumentShape {

    /** 无织入 */
    NONE,

    /** 仅 enter 相位 */
    ENTER,

    /** 仅 exit 相位 */
    EXIT,

    /** enter + exit */
    BOTH
}
