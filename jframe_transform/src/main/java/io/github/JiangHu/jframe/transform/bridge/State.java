package io.github.JiangHu.jframe.transform.bridge;

/**
 * enter 相位结果（bridge 包）——由 Advice 内联代码在方法体头部创建并读取。
 * <ul>
 *   <li>enter advice 返回 {@code null}：无事发生（或仅改参已写回），原方法体执行</li>
 *   <li>enter advice 返回本对象且 {@link #skip}：原方法体跳过，本对象经
 *       {@code @Advice.Enter} 传给 exit advice，用于写回短路返回值 / 强制抛出</li>
 * </ul>
 */
public final class State {

    /** 改写后的参数数组（非 null 时 advice 写回各参数槽；元素为包装类） */
    public Object[] args;

    /** 是否跳过原方法体 */
    public boolean skip;

    /** 短路返回值（skipWith） */
    public Object retValue;

    /** enter 阶段强制抛出的异常（REPLACE 抛出 / forceThrow） */
    public Throwable throwable;

    /** enter 阶段的上下文（skip 路径复用给 after 链） */
    public HookContext ctx;
}
