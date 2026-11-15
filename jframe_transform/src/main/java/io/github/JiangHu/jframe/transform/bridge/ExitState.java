package io.github.JiangHu.jframe.transform.bridge;

/**
 * exit 相位结果（bridge 包）——Advice 内联代码在方法体尾部读取。
 * {@code null} 表示无变更（原样返回/抛出）。
 */
public final class ExitState {

    /** 强制抛出的异常（forceThrow / REPLACE 抛出） */
    public Throwable throwable;

    /** 修改后的返回值（setReturn / skipWith；元素为包装类） */
    public Object ret;

    /** ret 是否生效 */
    public boolean retModified;
}
