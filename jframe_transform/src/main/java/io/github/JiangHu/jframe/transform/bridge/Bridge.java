package io.github.JiangHu.jframe.transform.bridge;

/**
 * 内联 dispatch 静态入口（bridge 包）——Advice 模板字节码中唯一的框架引用点。
 * <p>
 * 被增强方法体内的调用形态（由 jframe_transform 的 Advice 模板内联）：
 * <ul>
 *   <li>enter（免参数模板）：{@code Bridge.enter(id, self)} → State / null</li>
 *   <li>enter（带参数模板）：{@code Bridge.enter(id, self, args)} → State / null</li>
 *   <li>exit：{@code Bridge.exit(id, self, args, enterState, thrown, ret)} → ExitState / null</li>
 * </ul>
 * {@link #sink} 未装载（框架未初始化/关服清理）时全部空转返回 null——内联的几条
 * 指令零副作用，方法按原逻辑执行。
 */
public final class Bridge {

    /** 分发回调（由 TransformModule 装载 / 关服置 null） */
    public static volatile Sink sink;

    private Bridge() {
    }

    /**
     * enter 相位（免参数模板，不组参数数组）。
     *
     * @param id   hookId
     * @param self 目标实例（static 为 null）
     * @return State 或 null
     */
    public static State enter(int id, Object self) {
        Sink s = sink;
        if (s == null) {
            return null;
        }
        try {
            return s.onEnter(id, self, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * enter 相位（带参数模板）。
     *
     * @param id   hookId
     * @param self 目标实例
     * @param args 参数数组（包装类）
     * @return State 或 null
     */
    public static State enter(int id, Object self, Object[] args) {
        Sink s = sink;
        if (s == null) {
            return null;
        }
        try {
            return s.onEnter(id, self, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * exit 相位。
     *
     * @param id     hookId
     * @param self   目标实例
     * @param args   只读参数快照
     * @param enter  enter 相位返回的 State（可为 null）
     * @param thrown 原方法异常（可为 null）
     * @param ret    原方法返回值（可为 null）
     * @return ExitState 或 null（无变更）
     */
    public static ExitState exit(int id, Object self, Object[] args, State enter,
                                 Throwable thrown, Object ret) {
        Sink s = sink;
        if (s == null) {
            return null;
        }
        try {
            return s.onExit(id, self, args, enter, thrown, ret);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
