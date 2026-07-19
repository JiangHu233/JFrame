package io.github.JiangHu.jframe.event.routing;

import cn.nukkit.Server;
import cn.nukkit.event.Event;
import cn.nukkit.event.EventPriority;
import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 处理器模板：封装一个被 {@link EventRoute} + {@link EventHandler} 标注的方法。
 * <p>
 * 与旧版 {@code AnnotatedHandler} 的关键区别：
 * <ul>
 *   <li><b>不存储 target</b> — target 在 {@link #handle(Object, Event)} 时传入</li>
 *   <li>从 {@link EventRoute} 读取事件类型/条件，从 {@link EventHandler} 读取优先级/独占</li>
 *   <li>{@link #handle(Object, Event)} 返回 {@code boolean}：true = 声明独占</li>
 * </ul>
 *
 * <h3>不可变性</h3>
 * 本类是不可变的（创建后字段不再变化），线程安全。
 * 同一个 HandlerTemplate 可被多个实例共享（每次 dispatch 绑定不同的 target）。
 */
public class HandlerTemplate {

    private static final SpelExpressionParser SPEL_PARSER = new SpelExpressionParser();

    /** 声明此处理器的包装类（用于 filter 方法查找） */
    private final Class<?> declaringClass;

    /** 要调用的方法（已设置 accessible，保留用于日志/调试） */
    private final Method method;

    /** 方法的 MethodHandle（高效调用，替代反射） */
    private final MethodHandle methodHandle;

    /** 方法是否为 static（决定 invoke 时是否传入 target receiver） */
    private final boolean handlerStatic;

    /** 处理的事件类型 */
    private final Class<? extends Event> eventType;

    /** 预编译的 SpEL 条件表达式，null 表示无 SpEL 条件 */
    private final Expression conditionExpression;

    /** filter 筛选方法（已设置 accessible，保留用于日志），null 表示无 filter */
    private final Method filterMethod;

    /** filter 方法的 MethodHandle，null 表示无 filter */
    private final MethodHandle filterHandle;

    /** filter 方法是否为 static（决定 invoke 时是否传入 target receiver） */
    private final boolean filterStatic;

    /** 事件优先级 */
    private final EventPriority priority;

    /** 跨优先级独占标记 */
    private final boolean exclusive;

    /**
     * 从 {@link EventRoute} + {@link EventHandler} 注解创建处理器模板。
     *
     * @param declaringClass 声明此方法的类（用于 filter 方法查找）
     * @param method         标注了 {@link EventHandler} 的方法
     * @param route          {@link EventRoute} 注解实例
     * @param handler        {@link EventHandler} 注解实例
     * @throws IllegalArgumentException 如果事件类型无法确定、condition 与 filter 同时指定、
     *                                  或 filter 方法不存在/签名不匹配
     */
    @SuppressWarnings("unchecked")
    public HandlerTemplate(Class<?> declaringClass, Method method,
                           EventRoute route, EventHandler handler) {
        this.declaringClass = declaringClass;
        this.method = method;
        this.method.setAccessible(true);
        this.methodHandle = createMethodHandle(method);
        this.handlerStatic = Modifier.isStatic(method.getModifiers());
        this.priority = handler.priority();
        this.exclusive = handler.exclusive();

        // ① 解析事件类型：优先用注解显式指定的，否则从方法参数推断
        Class<? extends Event> type = route.value();
        if (type == Event.class) {
            type = resolveEventTypeFromParameter(method);
        }
        this.eventType = type;

        // ② 解析条件过滤（condition 和 filter 互斥）
        String condition = route.condition();
        String filter = route.filter();

        if (!condition.isEmpty() && !filter.isEmpty()) {
            throw new IllegalArgumentException(
                    "@EventRoute 的 condition 和 filter 不能同时使用（方法: "
                            + declaringClass.getName() + "." + method.getName() + "）");
        }

        if (!condition.isEmpty()) {
            this.conditionExpression = SPEL_PARSER.parseExpression(condition);
            this.filterMethod = null;
        } else if (!filter.isEmpty()) {
            this.conditionExpression = null;
            this.filterMethod = resolveFilterMethod(declaringClass, filter, type);
        } else {
            this.conditionExpression = null;
            this.filterMethod = null;
        }

        // 预编译 filter 的 MethodHandle（filterMethod 已在 resolveFilterMethod 中 setAccessible）
        this.filterHandle = this.filterMethod != null ? createMethodHandle(this.filterMethod) : null;
        // 预计算 filter 是否为 static：static 方法的 MethodHandle 不含 receiver 参数，
        // invoke 时只能传 event，否则抛 WrongMethodTypeException
        this.filterStatic = this.filterMethod != null && Modifier.isStatic(this.filterMethod.getModifiers());
    }

    /**
     * 在给定实例上处理事件。
     * <p>
     * 先检查条件（SpEL 或 filter），通过后反射调用方法。
     *
     * @param target 目标实例（运行时由 HandlerRegistry 通过工厂方法获取）
     * @param event  事件对象
     * @return {@code true} = 方法执行了且声明独占；{@code false} = 未执行或未独占
     */
    public boolean handle(Object target, Event event) {
        try {
            if (!passesCondition(target, event)) {
                return false; // 条件不满足，未执行，不独占
            }
            // MethodHandle 直接调用，JIT 可内联（替代反射 method.invoke）
            // static 方法无 receiver，只需传入 event；实例方法需传入 target
            if (handlerStatic) {
                methodHandle.invoke(event);
            } else {
                methodHandle.invoke(target, event);
            }
            return exclusive; // 执行了，返回 exclusive 标记
        } catch (Throwable e) {
            // MethodHandle 直接传播目标方法异常（不像反射包装为 InvocationTargetException），
            // 故用 catch(Throwable) 确保管线不被中断
            throw new RuntimeException(
                    "事件处理器执行失败: " + target.getClass().getName() + "." + method.getName(), e);
        }
    }

    /**
     * 检查事件是否满足过滤条件。
     * <p>
     * filter 方法优先（直接 Java 调用），其次 SpEL 求值。
     * filter 异常时返回 false（安全降级，不中断事件管线）。
     */
    private boolean passesCondition(Object target, Event event) {
        // filter 方法
        if (filterMethod != null) {
            try {
                // static filter 无 receiver，只需传入 event；实例 filter 需传入 target
                Object result = filterStatic
                        ? filterHandle.invoke(event)
                        : filterHandle.invoke(target, event);
                if (result instanceof Boolean b) {
                    return b;
                }
                return false;
            } catch (Throwable e) {
                Server.getInstance().getLogger().error(
                        "filter 方法执行失败: " + filterMethod.getName(), e);
                return false;
            }
        }

        // SpEL 条件
        if (conditionExpression != null) {
            EvaluationContext ctx = new StandardEvaluationContext();
            ctx.setVariable("event", event);
            ctx.setVariable("target", target);
            Boolean result = conditionExpression.getValue(ctx, Boolean.class);
            return result != null && result;
        }

        // 无条件
        return true;
    }

    // ========== 静态工具方法 ==========

    /**
     * 为方法创建 MethodHandle（高效调用替代反射）。
     * <p>
     * 方法必须已通过 {@code setAccessible(true)} 取消访问限制。
     * MethodHandle 经 JIT 编译后接近直接调用，比 {@code Method.invoke} 快 5~50 倍。
     *
     * @param method 已设置 accessible 的方法
     * @return 对应的 MethodHandle
     */
    private static MethodHandle createMethodHandle(Method method) {
        try {
            return MethodHandles.lookup().unreflect(method);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(
                    "无法为方法创建 MethodHandle: " + method.getDeclaringClass().getName()
                            + "." + method.getName(), e);
        }
    }

    /**
     * 从方法的第一个参数推断事件类型。
     */
    @SuppressWarnings("unchecked")
    private static Class<? extends Event> resolveEventTypeFromParameter(Method method) {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 0) {
            throw new IllegalArgumentException(
                    "未指定事件类型，且方法 " + method.getName()
                            + " 无参数，无法推断事件类型。"
                            + "请在 @EventRoute 中指定事件类或为方法添加 Event 参数。");
        }
        Class<?> firstParam = paramTypes[0];
        if (!Event.class.isAssignableFrom(firstParam)) {
            throw new IllegalArgumentException(
                    "方法 " + method.getName()
                            + " 的第一个参数 " + firstParam.getName()
                            + " 不是 Event 子类。");
        }
        return (Class<? extends Event>) firstParam;
    }

    /**
     * 在目标类（及其父类）中查找 filter 方法。
     * <p>
     * 要求：方法名匹配、单参数兼容事件类型、返回 boolean。
     */
    private static Method resolveFilterMethod(Class<?> targetClass, String methodName,
                                              Class<? extends Event> eventType) {
        Class<?> current = targetClass;
        while (current != null && current != Object.class) {
            for (Method m : current.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 1) continue;
                if (!params[0].isAssignableFrom(eventType)) continue;
                if (m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) continue;
                m.setAccessible(true);
                return m;
            }
            current = current.getSuperclass();
        }
        throw new IllegalArgumentException(
                "在 " + targetClass.getName() + " 中找不到 filter 方法 '"
                        + methodName + "'。要求：单参数（兼容 " + eventType.getSimpleName()
                        + "），返回 boolean。");
    }

    // ========== Getter ==========

    public Class<? extends Event> getEventType() {
        return eventType;
    }

    public EventPriority getPriority() {
        return priority;
    }

    public boolean isExclusive() {
        return exclusive;
    }

    public Method getMethod() {
        return method;
    }

    public Class<?> getDeclaringClass() {
        return declaringClass;
    }
}
