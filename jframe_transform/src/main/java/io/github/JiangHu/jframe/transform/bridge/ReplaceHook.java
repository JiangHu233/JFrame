package io.github.JiangHu.jframe.transform.bridge;

/**
 * 整方法接管（REPLACE）接口 — 原方法体保证不执行（bridge 包）。
 * <p>
 * {@link #replace(HookContext)} 的返回值即方法返回值（按声明类型自动拆箱，
 * void 方法忽略）；抛出的异常直接传给原调用方。多插件同方法都 REPLACE 时，
 * 优先级最高者先执行并胜出，其余 REPLACE 与原体全部跳过。
 */
@FunctionalInterface
public interface ReplaceHook {

    /**
     * 完全替代原方法体。
     *
     * @param ctx 调用上下文（instance / args，needsArgs 建议开启）
     * @return 方法返回值（boxed；void 方法忽略）
     * @throws Throwable 异常即原方法抛出的异常，直接传播给调用方
     */
    Object replace(HookContext ctx) throws Throwable;
}
