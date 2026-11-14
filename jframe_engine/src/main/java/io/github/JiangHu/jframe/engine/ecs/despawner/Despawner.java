package io.github.JiangHu.jframe.engine.ecs.despawner;

/**
 * 实体回收器 — 按注册名 + 参数表驱动，gameobject 销毁时执行世界侧回收。
 *
 * <h3>注册类 + 参数表（与组件/选择器/生成器同构）</h3>
 * <p>
 * 回收器是开发者定义并注册的类；定义文件中以 {@code despawn: { use: 名称, params: {...} }}
 * 引用，加载器在<b>定义加载时</b>按注册名实例化并注入参数（属性即子类字段）。
 * 实体销毁（显式 {@code destroy()} 或世界侧销毁通知：生物死亡 / 方块破坏 / 区块卸载）
 * 且其出生定义配了 despawn 时，引擎在<b>全部组件逆序 onUnmount 之后</b>回调
 * {@link #despawn(DespawnContext)}。
 *
 * <h3>契约对象是"产物"而非实体</h3>
 * <p>
 * {@link DespawnContext#product()} 传回实体代理的游戏内容引用（即 spawn 产物 / spawnBound 目标，
 * 可为 null = 虚拟产物）——回收器据此做世界侧清理：卸载 level、删除模板文件、清理辅助资源等
 * （创建与回收 level 伴随文件 IO 的典型场景即写在这里）。
 *
 * <h3>触发边界</h3>
 * <ul>
 *   <li>触发：显式 {@code destroy()}；引擎跟踪到的世界侧销毁通知（tracker → removeByBinding）</li>
 *   <li>不触发：引擎 {@code stop()}（产物随服务器/插件生命周期存续，不做回收）；
 *       引擎未跟踪的销毁事件（如 LevelUnload）——在 component 里加 {@code @OnEcsEvent}
 *       监听方法手动处理</li>
 *   <li>非 spawn/spawnBound 出生的实体（tracker 收养 / 手动注册）无出生记录，永不触发</li>
 * </ul>
 *
 * <p>回调在主线程同步执行；单个回收器抛异常只记录日志，不影响实体移除。
 *
 * @see io.github.JiangHu.jframe.engine.ecs.annotation.EcsDespawner
 * @see DespawnContext
 */
public interface Despawner {

    /**
     * 执行世界侧回收（在全部组件 onUnmount 之后调用；product 可能已失效，注意 instanceof/状态守卫）。
     *
     * @param context 回收上下文（定义名 / 产物 / 引擎）
     */
    void despawn(DespawnContext context);
}
