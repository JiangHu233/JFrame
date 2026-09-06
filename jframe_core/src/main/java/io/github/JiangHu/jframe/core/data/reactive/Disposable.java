package io.github.JiangHu.jframe.core.data.reactive;

/**
 * 可释放资源的生命周期接口——统一资源清理契约。
 *
 * <h3>设计目的</h3>
 * <p>框架中许多对象持有需要显式释放的资源(监听器引用、网络句柄、缓存映射等)。
 * 在此之前,释放逻辑分散在各处,调用方需要用 {@code instanceof} 判断具体类型才能清理,
 * 既容易遗漏(如计分板切换时漏调 dispose 导致内存泄漏),也难以统一管理。
 *
 * <p>引入 {@code Disposable} 后:
 * <ul>
 *   <li>所有可释放对象实现此接口,调用方只需 {@code if (obj instanceof Disposable)} 即可统一清理;</li>
 *   <li>配合 {@link EffectScope} 实现声明式自动清理——注册副作用时一并注册清理逻辑,
 *       scope 销毁时自动执行全部清理链,从根上杜绝手动遗漏。</li>
 * </ul>
 *
 * <h3>实现约定</h3>
 * <ul>
 *   <li><b>幂等</b>:多次调用 {@link #dispose()} 必须安全,不应抛出异常或重复释放;</li>
 *   <li><b>防御</b>:dispose 后对象进入「已销毁」状态,后续写入/读取应安全降级(忽略或返回默认值),
 *       不应产生僵尸数据或向已释放的资源写入;</li>
 *   <li><b>线程安全</b>:dispose 可能从不同线程调用,实现需保证可见性(volatile 标记或同步)。</li>
 * </ul>
 *
 * <h3>已知实现</h3>
 * <ul>
 *   <li>{@link DataContext} —— 默认 no-op(基类无外部资源)</li>
 *   <li>{@link io.github.JiangHu.jframe.content_template.HierarchicalDataContext}
 *       —— 解除对 parent 的监听引用,防止内存泄漏</li>
 *   <li>{@link EffectScope} —— 执行全部已注册的清理逻辑</li>
 * </ul>
 *
 * @see EffectScope
 * @see DataContext
 */
public interface Disposable {

    /**
     * 释放此对象持有的资源。
     * <p>调用后对象进入已销毁状态,不应再被使用。多次调用安全(幂等)。
     */
    void dispose();
}
