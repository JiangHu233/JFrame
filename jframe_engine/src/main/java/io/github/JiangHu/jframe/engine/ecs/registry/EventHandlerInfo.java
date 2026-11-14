package io.github.JiangHu.jframe.engine.ecs.registry;

import cn.nukkit.event.Event;
import io.github.JiangHu.jframe.engine.ecs.entity.Component;

import java.lang.reflect.Method;

/**
 * 组件事件处理器元数据 — 组件注册时由 {@link ComponentRegistry} 扫描 {@code @OnEcsEvent}
 * 方法建立；方法级过滤由<b>方法内首行守卫</b>承担（无声明式机制）。
 *
 * <p>顺序与截断：{@link #order()} 决定同事件处理器组内执行序（小者先，平级按注册序）；
 * {@link #truncate()} 声明执行后截断同实体更低 order 分发（见 {@link OnEcsEvent}）。
 *
 * @see io.github.JiangHu.jframe.engine.ecs.annotation.OnEcsEvent
 */
public final class EventHandlerInfo {

    /** 所属组件注册 id */
    private final String componentId;

    /** 所属组件类（从宿主实体取已挂实例） */
    private final Class<? extends Component> componentClass;

    /** 监听的事件类型（方法参数类型） */
    private final Class<? extends Event> eventType;

    /** 处理方法（已 setAccessible） */
    private final Method method;

    /** 是否忽略已取消事件 */
    private final boolean ignoreCancelled;

    /** 执行顺序（小者先；平级按注册序） */
    private final int order;

    /** 执行后是否截断同实体更低 order 分发 */
    private final boolean truncate;

    /**
     * 构造处理器元数据。
     *
     * @param componentId     组件注册 id
     * @param componentClass  组件类
     * @param eventType       事件类型
     * @param method          处理方法
     * @param ignoreCancelled 是否忽略已取消事件
     * @param order           执行顺序（小者先）
     * @param truncate        执行后是否截断下层分发
     */
    EventHandlerInfo(String componentId, Class<? extends Component> componentClass,
                     Class<? extends Event> eventType, Method method,
                     boolean ignoreCancelled, int order, boolean truncate) {
        this.componentId = componentId;
        this.componentClass = componentClass;
        this.eventType = eventType;
        this.method = method;
        this.ignoreCancelled = ignoreCancelled;
        this.order = order;
        this.truncate = truncate;
    }

    /**
     * 组件注册 id。
     *
     * @return id
     */
    public String componentId() {
        return componentId;
    }

    /**
     * 组件类。
     *
     * @return 组件类
     */
    public Class<? extends Component> componentClass() {
        return componentClass;
    }

    /**
     * 监听的事件类型。
     *
     * @return 事件类型
     */
    public Class<? extends Event> eventType() {
        return eventType;
    }

    /**
     * 处理方法（已可访问）。
     *
     * @return 方法
     */
    public Method method() {
        return method;
    }

    /**
     * 是否忽略已取消事件。
     *
     * @return true 表示跳过已取消事件
     */
    public boolean ignoreCancelled() {
        return ignoreCancelled;
    }

    /**
     * 执行顺序（小者先；平级按注册序）。
     *
     * @return 顺序值
     */
    public int order() {
        return order;
    }

    /**
     * 执行后是否截断同实体更低 order 分发。
     *
     * @return true 表示截断
     */
    public boolean truncate() {
        return truncate;
    }

    @Override
    public String toString() {
        return componentClass.getSimpleName() + "#" + method.getName()
                + "(" + eventType.getSimpleName() + ")";
    }
}
