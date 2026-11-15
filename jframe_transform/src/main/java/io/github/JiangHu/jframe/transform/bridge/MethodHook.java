package io.github.JiangHu.jframe.transform.bridge;

/**
 * 方法钩子接口 — 动态类增强模块的回调契约（bridge 包，系统类加载器唯一副本）。
 * <p>
 * 业务插件实现本接口并经命令式 {@code TransformPluginScope.hook(...)} 注册；
 * {@code before} 在原方法体前执行（可改参/短路），{@code after} 在原方法体后执行
 * （可读/改返回值）。未 override 的相位视为空——框架不为其内联任何 dispatch 指令
 * （相位省略）。
 *
 * <h3>线程约定</h3>
 * 回调可能在任意线程被触发（Nukkit 主线程 / Netty IO 线程），跨线程改世界请自行
 * 调度回主线程。回调抛出的异常由框架捕获记录，本次调用按"未修改"继续。
 */
public interface MethodHook {

    /**
     * 方法体执行前回调（HEAD 相位）。
     * <p>
     * 可经 {@code ctx.setArg} 改参（需注册时 {@code needsArgs=true}）、
     * {@code ctx.skip/skipWith} 短路原方法体、{@code ctx.forceThrow} 强制抛出。
     *
     * @param ctx 调用上下文
     */
    default void before(HookContext ctx) {
    }

    /**
     * 方法体执行后回调（RETURN 相位，无论正常返回还是抛异常均触发）。
     * <p>
     * 可读返回值/异常，{@code ctx.setReturn} 改返回值（仅正常返回路径生效）。
     *
     * @param ctx 调用上下文
     */
    default void after(HookContext ctx) {
    }
}
