package io.github.JiangHu.jframe.transform.bridge;

/**
 * 分发回调接口（bridge 包）——Bridge 与 jframe_transform 模块（HookRegistry）之间的边界。
 * <p>
 * Bridge（系统类加载器）经本接口把内联 dispatch 路由进框架注册表（jframe 插件
 * 类加载器）；接口位于 bridge 包保证跨加载器类型一致。实现方必须自捕获一切异常，
 * 绝不让回调异常炸掉被增强方法的线程。
 */
public interface Sink {

    /**
     * enter 相位分发（方法体头部）。
     *
     * @param id   织入时内联的 hookId
     * @param self 目标实例（static 为 null）
     * @param args 参数数组（needsArgs 织入时非 null，否则 null）
     * @return State：null = 原方法体执行；skip = 跳过（retValue/throwable 随后由 exit 写回）
     */
    State onEnter(int id, Object self, Object[] args);

    /**
     * exit 相位分发（方法体尾部）。
     *
     * @param id      hookId
     * @param self    目标实例（static 为 null）
     * @param args    只读参数快照（AllArguments，总是非 null；无参方法为空数组）
     * @param enter   enter 相位返回的 State（未短路 / 无 enter 织入时为 null）
     * @param thrown  原方法抛出的异常（未抛出为 null）
     * @param ret     原方法返回值（void 方法为 null；包装类）
     * @return ExitState：null = 无变更
     */
    ExitState onExit(int id, Object self, Object[] args, State enter, Throwable thrown, Object ret);
}
