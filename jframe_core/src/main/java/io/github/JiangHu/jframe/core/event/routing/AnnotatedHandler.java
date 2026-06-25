package io.github.JiangHu.jframe.core.event.routing;

import cn.nukkit.Server;
import cn.nukkit.event.Event;
import cn.nukkit.event.EventPriority;
import io.github.JiangHu.jframe.core.event.annotation.NukkitEvent;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;

/**
 * 注解处理器：封装一个被 {@link NukkitEvent} 标注的方法及其调用上下文。
 * <p>
 * 在注册时（而非每次事件触发时）完成以下预编译工作：
 * <ul>
 *   <li>解析事件类型（从 {@link NukkitEvent#value()} 或方法参数自动推断）</li>
 *   <li>解析 SpEL 条件表达式为 {@link Expression} 对象</li>
 *   <li>查找并缓存 filter 方法（如果指定了 {@link NukkitEvent#filter()}）</li>
 *   <li>设置方法 {@code accessible = true}，避免每次反射的访问检查</li>
 * </ul>
 * 运行时只需：① 求值条件/调用 filter（如有）→ ② 反射调用方法，开销极小。
 *
 * <h3>不可变性</h3>
 * 本类是不可变的（创建后字段不再变化），线程安全。
 */
public class AnnotatedHandler {

    private static final SpelExpressionParser SPEL_PARSER = new SpelExpressionParser();

    /** 目标对象（Spring Bean 或包装类实例） */
    private final Object target;

    /** 要调用的方法（已设置 accessible） */
    private final Method method;

    /** 处理的事件类型 */
    private final Class<? extends Event> eventType;

    /** 预编译的 SpEL 条件表达式，null 表示无 SpEL 条件 */
    private final Expression conditionExpression;

    /** filter 筛选方法（已设置 accessible），null 表示无 filter */
    private final Method filterMethod;

    /** 事件优先级 */
    private final EventPriority priority;

    /**
     * 从 {@link NukkitEvent} 注解创建处理器。
     *
     * @param target   目标对象
     * @param method   标注了 {@link NukkitEvent} 的方法
     * @param annotation 注解实例
     * @throws IllegalArgumentException 如果事件类型无法确定，或 filter 方法不存在/签名不匹配
     */
    @SuppressWarnings("unchecked")
    public AnnotatedHandler(Object target, Method method, NukkitEvent annotation) {
        this.target = target;
        this.method = method;
        this.method.setAccessible(true);
        this.priority = annotation.priority();

        // ① 解析事件类型：优先用注解显式指定的，否则从方法参数推断
        Class<? extends Event> type = annotation.value();
        if (type == Event.class) {
            // 未显式指定，从方法第一个参数推断
            type = resolveEventTypeFromParameter(method);
        }
        this.eventType = type;

        // ② 解析条件过滤（condition 和 filter 互斥）
        String condition = annotation.condition();
        String filter = annotation.filter();

        if (!condition.isEmpty() && !filter.isEmpty()) {
            throw new IllegalArgumentException(
                    "@NukkitEvent 的 condition 和 filter 不能同时使用（方法: "
                            + method.getName() + "）");
        }

        if (!condition.isEmpty()) {
            // SpEL 条件
            this.conditionExpression = SPEL_PARSER.parseExpression(condition);
            this.filterMethod = null;
        } else if (!filter.isEmpty()) {
            // filter 方法引用
            this.conditionExpression = null;
            this.filterMethod = resolveFilterMethod(target.getClass(), filter, type);
        } else {
            // 无条件
            this.conditionExpression = null;
            this.filterMethod = null;
        }
    }

    /**
     * 从方法的第一个参数推断事件类型。
     *
     * @param method 标注了 @NukkitEvent 的方法
     * @return 第一个参数的事件类型
     * @throws IllegalArgumentException 如果方法无参数或第一个参数不是 Event 子类
     */
    @SuppressWarnings("unchecked")
    private static Class<? extends Event> resolveEventTypeFromParameter(Method method) {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 0) {
            throw new IllegalArgumentException(
                    "@NukkitEvent 未指定事件类型，且方法 " + method.getName()
                            + " 无参数，无法推断事件类型。"
                            + "请显式指定 @NukkitEvent(事件类.class) 或为方法添加 Event 参数。");
        }
        Class<?> firstParam = paramTypes[0];
        if (!Event.class.isAssignableFrom(firstParam)) {
            throw new IllegalArgumentException(
                    "@NukkitEvent 未指定事件类型，且方法 " + method.getName()
                            + " 的第一个参数 " + firstParam.getName()
                            + " 不是 Event 子类。");
        }
        return (Class<? extends Event>) firstParam;
    }

    /**
     * 在目标类（及其父类）中查找 filter 方法。
     * <p>
     * filter 方法的要求：
     * <ul>
     *   <li>方法名匹配</li>
     *   <li>只有一个参数，且参数类型与事件类型兼容（是事件类型或其父类）</li>
     *   <li>返回 boolean</li>
     * </ul>
     *
     * @param targetClass 目标类
     * @param methodName  filter 方法名
     * @param eventType   事件类型
     * @return 找到的 filter 方法
     * @throws IllegalArgumentException 如果方法不存在或签名不匹配
     */
    private static Method resolveFilterMethod(Class<?> targetClass, String methodName,
                                              Class<? extends Event> eventType) {
        Class<?> current = targetClass;
        while (current != null && current != Object.class) {
            for (Method m : current.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 1) {
                    continue;
                }
                if (!params[0].isAssignableFrom(eventType)) {
                    continue;
                }
                if (m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) {
                    continue;
                }
                m.setAccessible(true);
                return m;
            }
            current = current.getSuperclass();
        }
        throw new IllegalArgumentException(
                "在 " + targetClass.getName() + " 中找不到符合条件的 filter 方法 '"
                        + methodName + "'。要求：单参数（兼容 " + eventType.getSimpleName()
                        + "），返回 boolean。");
    }

    /**
     * 处理事件：先检查条件（SpEL 或 filter），通过后反射调用目标方法。
     *
     * @param event 事件对象
     */
    public void handle(Event event) {
        try {
            // ① 条件过滤
            if (!passesCondition(event)) {
                return;
            }
            // ② 反射调用处理方法
            method.invoke(target, event);
        } catch (Exception e) {
            throw new RuntimeException(
                    "事件处理器执行失败: " + target.getClass().getName() + "." + method.getName(), e);
        }
    }

    /**
     * 检查事件是否满足过滤条件。
     * <p>
     * 优先检查 filter 方法（直接 Java 调用），其次检查 SpEL 表达式。
     * 两者都没有时返回 true（无条件通过）。
     *
     * @param event 事件对象
     * @return true 表示通过条件，应继续处理；false 表示被过滤掉
     */
    private boolean passesCondition(Event event) {
        // filter 方法
        if (filterMethod != null) {
            try {
                Object result = filterMethod.invoke(target, event);
                if (result instanceof Boolean) {
                    return (Boolean) result;
                }
                return false;
            } catch (Exception e) {
                Server.getInstance().getLogger().error(
                        "filter 方法执行失败: " + filterMethod.getName(), e);
                return false;
            }
        }

        // SpEL 条件
        if (conditionExpression != null) {
            EvaluationContext ctx = new StandardEvaluationContext();
            ctx.setVariable("event", event);
            ctx.setVariable("target", target); // 允许 #target.xxx() 调用
            Boolean result = conditionExpression.getValue(ctx, Boolean.class);
            return result != null && result;
        }

        // 无条件
        return true;
    }

    public Class<? extends Event> getEventType() {
        return eventType;
    }

    public EventPriority getPriority() {
        return priority;
    }

    public Object getTarget() {
        return target;
    }

    public Method getMethod() {
        return method;
    }
}
