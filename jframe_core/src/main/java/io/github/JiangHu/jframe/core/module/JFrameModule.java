package io.github.JiangHu.jframe.core.module;

import java.util.Set;

/**
 * 模块描述符接口 — 自研模块容器的工厂类契约。
 * <p>
 * 一个模块 = 一个 {@code JFrameModule} 实例 = 一次 {@link #load(ModuleContext)} 调用中
 * 产出的<b>一组懒加载单例</b>。对象之间的引用（B 持有 A）在 {@code load} 内用普通 Java
 * 代码组装，容器不做任何反射注入。
 *
 * <h3>生命周期</h3>
 * <ol>
 *   <li>{@code register(module)} —— 登记 {@link #id()} 与 {@link #provides()}（懒加载路由键），
 *       此时<b>不创建任何对象</b></li>
 *   <li>首次 {@code get(provides 中的类型)} —— 递归加载 {@link #requires()} 后执行
 *       {@link #load(ModuleContext)}，全部产物经 {@link ModuleContext#register} 登记</li>
 *   <li>{@code ModuleManager.shutdown()} —— 仅对已加载模块按 {@link #shutdownOrder()}
 *       升序调用 {@link #shutdown(ModuleContext)}</li>
 * </ol>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * public class EventModule implements JFrameModule {
 *     public String id() { return "event"; }
 *     public Set<Class<?>> provides() { return Set.of(EventAPI.class); }
 *     public void load(ModuleContext ctx) {
 *         EventEngine engine = new EventEngine();          // 内部 Bean：仅绑定 PluginAware
 *         ctx.bind(engine);
 *         HandlerRegistry registry = new HandlerRegistry(engine);
 *         ctx.register(EventAPI.class, new EventAPI(registry, engine));  // 公开服务
 *     }
 * }
 * }</pre>
 */
public interface JFrameModule {

    /**
     * 模块唯一标识（小写、稳定），用于 {@code requires()} 依赖声明、
     * {@code preload(String...)} 预加载与日志。
     *
     * @return 模块 id（不能为空白）
     */
    String id();

    /**
     * 依赖的其他模块 id。模块加载前会先递归加载这些依赖。
     *
     * @return 依赖模块 id 集合（默认空）
     */
    default Set<String> requires() {
        return Set.of();
    }

    /**
     * 声明本模块可产出的类型（懒加载路由键）。
     * <p>
     * 首次 {@code get} 这些类型中的任意一个都会触发本模块 {@link #load}；
     * load 结束时每个声明类型都必须已通过 {@link ModuleContext#register} 登记，
     * 否则模块标记为 FAILED。
     *
     * @return 产出类型集合（不能为空、不能与其他注册重叠）
     */
    Set<Class<?>> provides();

    /**
     * 构造并登记本模块的全部产物。
     * <p>
     * 对象图在方法体内用普通 Java 组装（引用工厂内解决，零反射注入）；
     * 公开服务经 {@link ModuleContext#register} 登记（类型表 + ServiceManager + PluginAware 绑定），
     * 内部 {@link PluginAware} Bean 经 {@link ModuleContext#bind} 绑定。
     *
     * @param ctx 加载上下文
     */
    void load(ModuleContext ctx);

    /**
     * 关闭顺序权重，升序执行（值越小越先停）。与加载顺序无关，保证关服语义稳定。
     *
     * @return 关闭顺序（默认 500 = 最后一批）
     */
    default int shutdownOrder() {
        return 500;
    }

    /**
     * 关服清理回调。仅当模块已成功加载时被调用（在
     * {@link ModuleManager#shutdown()} 中按 {@link #shutdownOrder()} 升序执行），
     * 单个模块的异常会被捕获记录，不影响其他模块。
     *
     * @param ctx 上下文（关闭阶段不应再登记新对象）
     */
    default void shutdown(ModuleContext ctx) {
    }
}
