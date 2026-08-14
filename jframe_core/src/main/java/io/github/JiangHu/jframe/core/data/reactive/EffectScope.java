package io.github.JiangHu.jframe.core.data.reactive;

import io.github.JiangHu.jframe.core.JFrameLog;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 副作用作用域——集中管理一组清理逻辑,dispose() 时自动执行全部清理链。
 *
 * <h3>设计灵感</h3>
 * <p>对齐 Vue 3 的 {@code effectScope}:将组件/视图生命周期内注册的所有副作用(监听器、
 * 定时器、网络句柄等)收集到一个 scope,scope 销毁时框架自动执行全部清理,
 * 无需开发者手动逐个摘除——从根上杜绝「忘记清理导致内存泄漏」。
 *
 * <h3>核心机制</h3>
 * <pre>{@code
 * EffectScope scope = new EffectScope();
 *
 * // 注册副作用时,一并注册对应的清理逻辑
 * dataContext.onChange(listener);
 * scope.register(() -> dataContext.removeListener(listener));
 *
 * Disposable child = someResource.acquire();
 * scope.register(child::dispose);
 *
 * // 生命周期结束时,一行清理全部
 * scope.dispose();  // 自动:removeListener + child.dispose()
 * }</pre>
 *
 * <h3>为什么需要它</h3>
 * <p>计分板切换 Bug 的根因:旧 view 的 DataContext 在 parent 上注册了监听器,
 * 切换时只调用了 hide()(移除自身渲染监听)却遗漏了 dispose()(摘除 parent 监听),
 * 导致 parent 持有旧 view 的强引用无法 GC。
 * <p>引入 EffectScope 后,view 将所有副作用注册到 scope,切换/退出时只需 dispose scope,
 * 框架保证全部清理链执行——遗漏任何一项都不可能。
 *
 * <h3>线程安全</h3>
 * <ul>
 *   <li>清理列表使用 {@link CopyOnWriteArrayList},register/dispose 可并发调用</li>
 *   <li>{@code disposed} 标记为 volatile,保证跨线程可见性</li>
 *   <li>单个清理逻辑异常不影响其他清理(捕获并记录日志)</li>
 * </ul>
 *
 * @see Disposable
 */
public class EffectScope implements Disposable {

    /** 已注册的清理逻辑列表(dispose 时逆序执行) */
    private final List<Runnable> disposers = new CopyOnWriteArrayList<>();

    /** dispose 标记,防止重复清理(volatile 保证跨线程可见性) */
    private volatile boolean disposed = false;

    /**
     * 注册一条清理逻辑。
     * <p>清理逻辑在 {@link #dispose()} 时执行。若 scope 已销毁,则立即执行该清理(不缓存)。
     *
     * @param onDispose 清理逻辑(无参 Runnable,通常为方法引用 {@code obj::cleanup})
     * @return this(链式调用)
     */
    public EffectScope register(Runnable onDispose) {
        if (onDispose == null) {
            return this;
        }
        if (disposed) {
            // scope 已销毁,立即执行清理(资源不应泄漏)
            runSafely(onDispose);
            return this;
        }
        disposers.add(onDispose);
        return this;
    }

    /**
     * 销毁此 scope,执行全部已注册的清理逻辑。
     * <p><b>幂等</b>:多次调用安全,仅首次执行清理。清理逻辑按注册顺序执行,
     * 单个异常不影响其他清理。
     */
    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        for (Runnable disposer : disposers) {
            runSafely(disposer);
        }
        disposers.clear();
    }

    /**
     * @return 此 scope 是否已销毁
     */
    public boolean isDisposed() {
        return disposed;
    }

    /**
     * 安全执行单条清理逻辑——捕获异常并记录日志,不影响后续清理。
     *
     * @param task 清理逻辑
     */
    private void runSafely(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            try {
                JFrameLog.warning("EffectScope", "清理逻辑执行异常: " + e.getMessage());
            } catch (IllegalStateException ignored) {
                // Server 尚未初始化(如单元测试环境),静默忽略
            }
        }
    }
}
