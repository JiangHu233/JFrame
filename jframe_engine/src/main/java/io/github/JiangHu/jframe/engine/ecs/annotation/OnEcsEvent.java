package io.github.JiangHu.jframe.engine.ecs.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 组件/系统事件方法注解 — 方法<b>参数类型即监听的事件类型</b>。
 *
 * <h3>标注位置与路由</h3>
 * <ul>
 *   <li><b>组件实例方法</b>：事件经宿主解析后分发给宿主身上已挂该组件的实例（挂载即订阅、卸载即退订）</li>
 *   <li><b>系统类方法</b>：全局处理器，事件触发即调用一次（无需宿主解析器）</li>
 * </ul>
 *
 * <h3>顺序与截断（确定性契约）</h3>
 * <ul>
 *   <li>组间：同一事件<b>先系统（全局）后组件（宿主路由）</b>，固定不变</li>
 *   <li>组内：系统处理器与组件处理器<b>各自独立</b>按 {@link #order()} 升序执行；
 *       <b>平级（同 order）按注册顺序执行</b>——这是文档化契约，可依赖</li>
 *   <li>跨宿主：多个宿主实体按解析器注册顺序依次分发（文档化契约）</li>
 *   <li>截断：{@link #truncate()} 声明「本处理器执行后垄断事件」，见其 javadoc</li>
 * </ul>
 *
 * <h3>方法级过滤 = 方法内首行守卫（纯代码，无声明式机制）</h3>
 * <p>
 * 需要条件判断时直接在方法首行 {@code if} 返回——可直接读组件自身字段（无间接查找）：
 * <pre>{@code
 * @OnEcsEvent(ignoreCancelled = true, order = -10)
 * public void onHit(EntityDamageEvent event) {
 *     if (event.getDamage() <= hitThreshold) {
 *         return;   // 守卫：直接读 this 字段
 *     }
 *     ...
 * }
 * }</pre>
 *
 * <h3>方法要求</h3>
 * <ul>
 *   <li>public 非静态，恰好 1 个 {@code cn.nukkit.event.Event} 子类参数</li>
 *   <li>组件方法内可用 {@code host()} 识别宿主</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnEcsEvent {

    /**
     * 是否忽略已取消事件（默认不忽略）。
     *
     * @return true 表示跳过已取消事件
     */
    boolean ignoreCancelled() default false;

    /**
     * 执行顺序（int，默认 0，<b>小者先</b>）。
     *
     * <p>系统处理器组与组件处理器组<b>各自独立</b>按 order 升序执行；
     * <b>平级（同 order）按注册顺序执行</b>（文档化契约）。
     * 数值语义建议：预处理/前置加工用负值，核心逻辑用 0（默认），观察者/监控/统计用正值。
     *
     * <p>与 {@code EcsSystem#order()}（系统 tick 顺序）词汇统一。
     *
     * @return 执行顺序
     */
    int order() default 0;

    /**
     * 下层截断声明（默认 false）——「本处理器执行后垄断事件」，语义对齐
     * {@code jframe_listener} 的 {@code @EventHandler(exclusive = true)}（事件级独占）。
     *
     * <h3>作用范围</h3>
     * <ul>
     *   <li><b>系统处理器</b>声明截断 → 同层更低 order 的系统处理器跳过，
     *       且本事件<b>整个组件层不再分发</b>（含宿主解析与惰性收养）</li>
     *   <li><b>组件处理器</b>声明截断 → 仅<b>同实体</b>上更低 order 的组件处理器跳过
     *       （其他宿主实体不受影响）</li>
     * </ul>
     *
     * <h3>生效规则</h3>
     * <ul>
     *   <li><b>组边界检查</b>：同 order 平级处理器仍<b>全部执行</b>（对齐 jframe_listener
     *       exclusive 的组边界语义；平级间相对顺序不保证，不得依赖）</li>
     *   <li><b>执行后生效</b>：方法正常返回后截断才生效；方法抛异常 → 不截断（仅记录日志）</li>
     *   <li>截断与取消<b>正交</b>：截断只影响 ECS 内部分发，不改变 Nukkit 下游
     *       （其他插件、原版结算照常收到未取消事件）</li>
     * </ul>
     *
     * <h3>⚠ 守卫陷阱</h3>
     * <p>方法内首行守卫 {@code return} 也算「执行过」→ 会截断。因此 {@code truncate}
     * 仅用于<b>无条件终结型</b>处理器（如大厅保护这类「这个事件归我了」的场景）；
     * <b>条件性垄断</b>请用 {@code event.setCancelled + 下游 ignoreCancelled} 组合表达
     * （取消发生在守卫之后，天然精确）。
     *
     * @return true 表示执行后截断下层分发
     */
    boolean truncate() default false;
}
