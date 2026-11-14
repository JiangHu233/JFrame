package io.github.JiangHu.jframe.engine.ecs.entity;

/**
 * 组件抽象基类 — 持有宿主字段，业务组件继承后任意时刻 {@link #host()} 可用。
 *
 * <h3>设计动机</h3>
 * <p>
 * {@link Component} 接口无法持有状态，宿主字段由引擎经 {@code bindHost} 注入。
 * 本基类封装该字段的存取，业务组件继承后即可在生命周期钩子、事件回调及任意业务方法中
 * 使用 {@code host()} 识别自己挂载的对象。
 *
 * <h3>注意</h3>
 * <ul>
 *   <li>{@code host} 为 {@code transient} 字段，不会参与 {@code params} 字段注入</li>
 *   <li>若不继承本类而直接实现 {@link Component}，需自行实现 {@code bindHost}/{@code host}</li>
 * </ul>
 *
 * @see Component
 */
public abstract class ComponentBase implements Component {

    /** 宿主实体（引擎注入；transient → 不参与 params 字段注入） */
    private transient EcsEntity host;

    /** 挂载来源定义名（引擎注入，与 host 同期；transient → 不参与 params 字段注入；
     *  null = 手动挂载 —— 定义重载不剥除） */
    private transient String mountedFrom;

    @Override
    public final void bindHost(EcsEntity host) {
        this.host = host;
    }

    @Override
    public final EcsEntity host() {
        if (host == null) {
            throw new IllegalStateException("组件尚未绑定宿主（bindHost 由引擎在挂载时调用）");
        }
        return host;
    }

    @Override
    public final void bindSource(String definitionName) {
        this.mountedFrom = definitionName;
    }

    @Override
    public final String mountedFrom() {
        return mountedFrom;
    }
}
