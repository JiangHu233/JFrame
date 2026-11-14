package io.github.JiangHu.jframe.engine.ecs.entity;

/**
 * ECS 组件契约 — 属性即子类字段，宿主随取随用。
 *
 * <h3>核心约定</h3>
 * <ul>
 *   <li><b>属性即字段</b>：组件的配置属性就是子类中声明的字段。定义文件 {@code params}
 *       的 key 与字段名按名匹配，挂载时反射注入（见 {@code ParamBinder}）；
 *       未出现的字段保留代码初始值（可选参数语义）</li>
 *   <li><b>宿主识别</b>：引擎在实例化后立即调用 {@link #bindHost(EcsEntity)}，
 *       之后任意时刻（含 {@code onMount}/{@code onUnmount}/事件回调内）都可通过
 *       {@link #host()} 取回宿主实体</li>
 *   <li><b>每实体一实例</b>：组件按宿主粒度创建，不同实体持有不同实例</li>
 *   <li><b>生命周期钩子</b>：{@link #onMount()} / {@link #onUnmount()} 为无参可重写方法</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @EcsComponent("magic-alarm")
 * public class MagicAlarm extends ComponentBase {
 *     private String sound = "random.orb";   // params.sound 可覆盖
 *     private double radius = 5.0;
 *
 *     @Override
 *     public void onMount() {
 *         if (host().binding() instanceof BlockEntity chest) {   // 挂载时初始化（代理引用判型）
 *             // ...
 *         }
 *     }
 *
 *     @Override
 *     public void onUnmount() { /* 箱子被破坏 / 引擎 stop 时清理 *\/ }
 * }
 * }</pre>
 *
 * <p>业务组件通常继承 {@link ComponentBase}（已持有 host 字段）而非直接实现本接口。
 *
 * @see ComponentBase
 * @see EcsEntity
 */
public interface Component {

    /**
     * 绑定宿主实体（<b>仅引擎调用</b>，实例化后、参数注入前调用一次）。
     *
     * @param host 宿主实体，不为 null
     */
    void bindHost(EcsEntity host);

    /**
     * 取回宿主实体（挂载期间任意时刻可用）。
     *
     * @return 宿主实体
     * @throws IllegalStateException 尚未绑定宿主时抛出
     */
    EcsEntity host();

    /**
     * 绑定挂载来源定义（<b>仅引擎调用</b>，与 {@link #bindHost} 同期注入一次）。
     *
     * <p>定义驱动挂载（spawn / selector）写入定义名；手动挂载
     * （{@code EcsEngine.mountComponent} / {@code EcsEntity.mount}）为 null。
     * {@code reloadDefinition} 据此判定组件归属：只卸载真正由本定义挂上的实例，
     * 组件 id 相同（多定义共享载体组件）不再误判。
     *
     * <p>默认空实现 —— 直接实现本接口的组件视为来源未知（同手动挂载，
     * 定义重载不剥除）；{@link ComponentBase} 已覆写。
     *
     * @param definitionName 挂载来源定义名；null = 手动挂载
     */
    default void bindSource(String definitionName) {
    }

    /**
     * 挂载来源定义名（见 {@link #bindSource}）。
     *
     * @return 定义名；null = 手动挂载或来源未知（直接实现本接口未覆写时）
     */
    default String mountedFrom() {
        return null;
    }

    /**
     * 挂载钩子 — 组件成功挂到宿主上之后调用（此时参数已注入、{@link #host()} 可用）。
     * <p>
     * 抛出异常时引擎会回滚该组件（不留半挂载状态），不影响同实体其他组件。
     */
    default void onMount() {
    }

    /**
     * 卸载钩子 — 组件从宿主上卸载之前调用（宿主消失 / 实体销毁 / 引擎 stop）。
     * <p>
     * 单个组件抛出异常只会被记录，不影响其余组件的卸载。
     */
    default void onUnmount() {
    }

    /**
     * 重置钩子（<b>池化组件契约</b>）— 归池前由引擎调用（在 {@link #onUnmount()} 之后）。
     * <p>
     * 仅 {@code @EcsComponent(pooled = true)} 的组件需要实现：清空 <b>params 注入之外
     * 累积的运行时字段</b>（集合、缓存、监听登记等）；params 键在下次挂载时全量覆盖注入，
     * 无需在此清理；框架注入字段（host / mountedFrom）由引擎强制清空，亦无需处理。
     * <p>
     * 抛出异常时该实例被丢弃不入池（记录日志），不影响其余流程。
     */
    default void reset() {
    }
}
