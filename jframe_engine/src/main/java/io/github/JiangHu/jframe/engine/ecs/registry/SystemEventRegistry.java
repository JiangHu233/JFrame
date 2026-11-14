package io.github.JiangHu.jframe.engine.ecs.registry;

import cn.nukkit.event.Event;
import io.github.JiangHu.jframe.engine.ecs.annotation.OnEcsEvent;
import io.github.JiangHu.jframe.engine.ecs.system.EcsSystem;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统事件注册表 — 系统实例上的 {@code @OnEcsEvent} 方法即<b>全局事件处理器</b>。
 *
 * <p>处理器按 {@code (order, 注册序)} 稳定排序存放——{@link #handlersFor} 返回的即最终执行序。
 *
 * <h3>与组件处理器的差异</h3>
 * <ul>
 *   <li><b>无宿主解析</b>：系统是全局单例，事件触发即调用一次（跳过宿主级/组件级过滤）；
 *       因此订阅的事件类型<b>不要求</b>存在宿主解析器</li>
 *   <li><b>方法级过滤</b>：方法内首行守卫（与组件同一纯代码方式）+ {@code ignoreCancelled}</li>
 *   <li><b>分发顺序</b>：同一事件先系统（全局）后组件（局部），见 EcsEventBridge</li>
 * </ul>
 *
 * <h3>注册期校验（fail fast）</h3>
 * <ul>
 *   <li>方法非 public / static / 参数数不为 1 / 参数非 Event 子类 → 拒绝</li>
 * </ul>
 */
public class SystemEventRegistry {

    /** 事件类型 → 系统处理器列表 */
    private final Map<Class<? extends Event>, List<SystemEventHandlerInfo>> handlersByEvent = new HashMap<>();

    /**
     * 注册系统实例：扫描其实例类继承链上的 {@code @OnEcsEvent} 方法建表。
     * <p>同一实例重复注册会重复建表（与 {@code EcsEngine.addSystem} 重复添加一致，调用方自律）。
     *
     * @param system 系统实例
     * @throws io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException 处理器校验失败
     */
    public synchronized void register(EcsSystem system) {
        List<SystemEventHandlerInfo> infos = scanHandlers(system);
        for (SystemEventHandlerInfo info : infos) {
            List<SystemEventHandlerInfo> list = handlersByEvent.computeIfAbsent(info.eventType(), k -> new ArrayList<>());
            list.add(info);
            // 稳定排序：按 order 升序，平级保持注册顺序（文档化契约）
            list.sort(Comparator.comparingInt(SystemEventHandlerInfo::order));
        }
    }

    /**
     * 指定事件类型上的全部系统处理器。
     *
     * @param eventType 事件类型
     * @return 处理器列表（不可变；无则空）
     */
    public synchronized List<SystemEventHandlerInfo> handlersFor(Class<? extends Event> eventType) {
        List<SystemEventHandlerInfo> list = handlersByEvent.get(eventType);
        return list == null ? List.of() : Collections.unmodifiableList(list);
    }

    /**
     * 有系统处理器的全部事件类型（事件桥按需注册监听用——系统订阅无需解析器）。
     *
     * @return 事件类型集合快照
     */
    public synchronized List<Class<? extends Event>> handlerEventTypes() {
        return List.copyOf(handlersByEvent.keySet());
    }

    /**
     * 扫描系统实例类（含父类）上的 {@code @OnEcsEvent} 方法并完成全部注册期校验。
     */
    private static List<SystemEventHandlerInfo> scanHandlers(EcsSystem system) {
        List<SystemEventHandlerInfo> result = new ArrayList<>();
        Class<?> type = system.getClass();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                OnEcsEvent anno = method.getAnnotation(OnEcsEvent.class);
                if (anno == null) {
                    continue;
                }
                String where = type.getSimpleName() + "#" + method.getName();
                Class<? extends Event> eventType = HandlerValidation.requireHandlerSignature(method, where);
                method.setAccessible(true);
                result.add(new SystemEventHandlerInfo(system, eventType, method,
                        anno.ignoreCancelled(), anno.order(), anno.truncate()));
            }
        }
        return result;
    }

    /**
     * 系统事件处理器元数据。
     *
     * @param system          所属系统实例（调用接收者）
     * @param eventType       监听的事件类型（方法参数类型）
     * @param method          处理方法（已可访问；签名 (EventType)）
     * @param ignoreCancelled 是否忽略已取消事件
     * @param order           执行顺序（小者先；平级按注册序）
     * @param truncate        执行后是否截断（跳过更低 order 系统 + 整个组件层）
     */
    public record SystemEventHandlerInfo(EcsSystem system,
                                         Class<? extends Event> eventType,
                                         Method method,
                                         boolean ignoreCancelled,
                                         int order,
                                         boolean truncate) {

        @Override
        public String toString() {
            return system.getClass().getSimpleName() + "#" + method.getName()
                    + "(" + eventType.getSimpleName() + ")";
        }
    }
}
