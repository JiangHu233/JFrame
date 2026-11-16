package io.github.JiangHu.jframe.transform.bridge;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 钩子调用上下文（bridge 包，系统类加载器唯一副本）——每个相位由框架分配。
 * <p>
 * 方法调用四要素：{@link #getInstance()}/{@link #getArgs()}/{@link #getReturn()}/
 * {@link #getThrowable()}；控制操作：{@link #skip()}/{@link #skipWith(Object)}/
 * {@link #forceThrow(Throwable)}；字段/私有成员便捷访问（缓存反射 + 父类链）：
 * {@link #field(String)}/{@link #setField(String, Object)}/{@link #call(String, Object...)}。
 */
public final class HookContext {

    /** 反射字段缓存：类名#字段名 → Field（或 NoSuchFieldException 哨兵） */
    private static final Map<String, Object> FIELD_CACHE = new ConcurrentHashMap<>();

    /** 反射方法缓存：类名#方法名/参数个数 → Method（或 NoSuchMethodException 哨兵） */
    private static final Map<String, Object> METHOD_CACHE = new ConcurrentHashMap<>();

    private final Object instance;
    private final Object[] args;
    private final String targetClass;
    private final String targetMethod;

    /** after 阶段：原方法返回值（before 阶段为 null） */
    private Object ret;
    /** after 阶段：原方法抛出的异常（未抛出为 null） */
    private Throwable throwable;
    /** args 数组是否可写（仅 before 阶段为 true） */
    private final boolean argsWritable;

    // ---- 控制标志（框架读取） ----
    private boolean skip;
    private Object skipValue;
    private boolean retModified;
    private Object newReturn;
    private Throwable forceThrow;

    /**
     * 框架内部构造（业务插件不应自行构造）。
     *
     * @param instance      目标实例（static 方法为 null）
     * @param args          参数数组（before 阶段 needsArgs 时非空；after 阶段为只读快照）
     * @param argsWritable  args 是否可写（仅 before 阶段）
     * @param targetClass   目标类名
     * @param targetMethod  目标方法名
     */
    public HookContext(Object instance, Object[] args, boolean argsWritable,
                       String targetClass, String targetMethod) {
        this.instance = instance;
        this.args = args;
        this.argsWritable = argsWritable;
        this.targetClass = targetClass;
        this.targetMethod = targetMethod;
    }

    // ==================== 读取 ====================

    /**
     * 目标实例（static 方法为 {@code null}）。
     *
     * @return 实例对象
     */
    public Object getInstance() {
        return instance;
    }

    /**
     * 参数数组：before 阶段在注册时 {@code needsArgs=true} 才非空；after 阶段总是
     * 提供只读快照。基本类型参数以包装类传递。
     *
     * @return 参数数组（可能为 {@code null}）
     */
    public Object[] getArgs() {
        return args;
    }

    /**
     * 修改参数（仅 before 阶段、needsArgs=true 且方法元数 ≤6 有写回模板时生效；
     * 高元只读模板 / after 阶段抛 {@link IllegalStateException}）。
     *
     * @param index 参数下标
     * @param value 新值（基本类型传包装类）
     */
    public void setArg(int index, Object value) {
        if (args == null) {
            throw new IllegalStateException("当前无参数数组：before 阶段需注册时 needsArgs=true");
        }
        if (!argsWritable) {
            throw new IllegalStateException("当前参数数组不可写（超过 6 参的只读模板 / after 阶段原体已执行）");
        }
        args[index] = value;
    }

    /**
     * 原方法返回值（仅 after 阶段；基本类型为包装类）。
     *
     * @return 返回值
     */
    public Object getReturn() {
        return ret;
    }

    /**
     * 修改返回值（仅 after 阶段、原方法正常返回路径生效；抛出路径忽略）。
     *
     * @param value 新返回值（基本类型传包装类）
     */
    public void setReturn(Object value) {
        this.newReturn = value;
        this.retModified = true;
    }

    /**
     * 原方法抛出的异常（未抛出为 {@code null}；仅 after 阶段有值）。
     *
     * @return 异常
     */
    public Throwable getThrowable() {
        return throwable;
    }

    // ==================== 控制 ====================

    /**
     * 跳过原方法体（仅 before 阶段有效）：返回值走声明类型默认值（null/0/false）。
     */
    public void skip() {
        this.skip = true;
    }

    /**
     * 跳过原方法体并指定返回值。
     *
     * @param value 返回值（基本类型传包装类；void 方法忽略）
     */
    public void skipWith(Object value) {
        this.skip = true;
        this.skipValue = value;
    }

    /**
     * 让本次调用抛出指定异常：before 阶段 = 跳过原体并抛出；after 阶段 = 覆盖返回/原异常抛出。
     *
     * @param t 要抛出的异常
     */
    public void forceThrow(Throwable t) {
        this.forceThrow = t;
        this.skip = true;
    }

    // ==================== 元数据 ====================

    /**
     * 目标类名（注册时字符串，非重解析）。
     *
     * @return 类全限定名
     */
    public String getTargetClass() {
        return targetClass;
    }

    /**
     * 目标方法名。
     *
     * @return 方法名
     */
    public String getTargetMethod() {
        return targetMethod;
    }

    // ==================== 字段 / 私有成员便捷访问（缓存反射 + 父类链） ====================

    /**
     * 读取目标实例字段（含 private / 父类字段）。
     *
     * @param name 字段名
     * @return 字段值（基本类型为包装类；static 目标方法时 instance 为 null 可读 static 字段）
     */
    public Object field(String name) {
        return readField(instance, name);
    }

    /**
     * 写入目标实例字段（含 private / 父类字段）。
     *
     * @param name  字段名
     * @param value 新值（基本类型传包装类）
     */
    public void setField(String name, Object value) {
        writeField(instance, name, value);
    }

    /**
     * 调用目标实例方法（含 private / 父类方法；按 方法名+实参个数 匹配，重载同参个数时取首个）。
     *
     * @param method 方法名
     * @param invokeArgs 实参
     * @return 返回值（基本类型为包装类）
     */
    public Object call(String method, Object... invokeArgs) {
        return invokeMethod(instance, method, invokeArgs);
    }

    // ==================== 静态反射工具（与 PrivateAccess 同源逻辑，供 ctx 便捷法使用） ====================

    /**
     * 读字段（缓存反射 + 父类链查找）。
     *
     * @param target 目标对象（读 static 字段可为 null，需 targetClass 提供类）
     * @param name   字段名
     * @return 字段值
     */
    static Object readField(Object target, String name) {
        Class<?> cls = target == null ? null : target.getClass();
        if (cls == null) {
            throw new IllegalArgumentException("读取字段需要非 null 目标（static 方法钩子请用 PrivateAccess.get(class,...)）");
        }
        try {
            Field f = findField(cls, name);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读取字段失败: " + cls.getName() + "#" + name, e);
        }
    }

    /**
     * 写字段（缓存反射 + 父类链查找）。
     *
     * @param target 目标对象
     * @param name   字段名
     * @param value  新值
     */
    static void writeField(Object target, String name, Object value) {
        if (target == null) {
            throw new IllegalArgumentException("写入字段需要非 null 目标");
        }
        Class<?> cls = target.getClass();
        try {
            Field f = findField(cls, name);
            if (Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers())) {
                throw new IllegalStateException("拒绝写入 static final 字段: " + cls.getName() + "#" + name);
            }
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("写入字段失败: " + cls.getName() + "#" + name, e);
        }
    }

    /**
     * 调方法（缓存反射 + 父类链查找，按名 + 参数个数匹配）。
     *
     * @param target 目标对象（static 方法可为 null，此时需显式类）
     * @param name   方法名
     * @param invokeArgs 实参
     * @return 返回值
     */
    static Object invokeMethod(Object target, String name, Object[] invokeArgs) {
        Class<?> cls = target == null ? null : target.getClass();
        if (cls == null) {
            throw new IllegalArgumentException("调用方法需要非 null 目标");
        }
        int argc = invokeArgs == null ? 0 : invokeArgs.length;
        try {
            Method m = findMethod(cls, name, argc);
            return m.invoke(target, invokeArgs);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("反射调用目标方法抛出异常: " + cls.getName() + "#" + name, cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("调用方法失败: " + cls.getName() + "#" + name
                    + "/" + argc + "参", e);
        }
    }

    private static Field findField(Class<?> cls, String name) {
        String key = cls.getName() + "#" + name;
        Object cached = FIELD_CACHE.get(key);
        if (cached instanceof Field field) {
            return field;
        }
        if (cached instanceof NoSuchFieldException notFound) {
            throw new IllegalStateException("字段不存在: " + key, notFound);
        }
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                ensureAccessible(f, cls);
                FIELD_CACHE.putIfAbsent(key, f);
                return f;
            } catch (NoSuchFieldException ignored) {
                // 沿父类链继续
            }
        }
        NoSuchFieldException marker = new NoSuchFieldException(key);
        FIELD_CACHE.putIfAbsent(key, marker);
        throw new IllegalStateException("字段不存在（含父类链）: " + key, marker);
    }

    private static Method findMethod(Class<?> cls, String name, int argc) {
        String key = cls.getName() + "#" + name + "/" + argc;
        Object cached = METHOD_CACHE.get(key);
        if (cached instanceof Method method) {
            return method;
        }
        if (cached instanceof NoSuchMethodException notFound) {
            throw new IllegalStateException("方法不存在: " + key, notFound);
        }
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == argc) {
                    ensureAccessible(m, cls);
                    METHOD_CACHE.putIfAbsent(key, m);
                    return m;
                }
            }
        }
        NoSuchMethodException marker = new NoSuchMethodException(key);
        METHOD_CACHE.putIfAbsent(key, marker);
        throw new IllegalStateException("方法不存在（含父类链）: " + key, marker);
    }

    private static void ensureAccessible(AccessibleObject obj, Class<?> owner) {
        try {
            obj.setAccessible(true);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "反射访问被拒（JDK/模块封装类不支持）: " + owner.getName(), e);
        }
    }

    // ==================== 框架内部读写（sink 使用，业务插件勿用） ====================

    /**
     * 框架内部：after 阶段注入返回值与异常。
     *
     * @param ret       返回值
     * @param throwable 异常（无则 null）
     */
    public void internalBindExit(Object ret, Throwable throwable) {
        this.ret = ret;
        this.throwable = throwable;
    }

    /** @return 是否请求短路 */
    public boolean internalSkip() {
        return skip;
    }

    /** @return 短路时指定的返回值 */
    public Object internalSkipValue() {
        return skipValue;
    }

    /** @return 是否修改了返回值 */
    public boolean internalRetModified() {
        return retModified;
    }

    /** @return 修改后的返回值 */
    public Object internalNewReturn() {
        return newReturn;
    }

    /** @return 强制抛出的异常 */
    public Throwable internalForceThrow() {
        return forceThrow;
    }

    /** 框架内部 toString（排查用）。 */
    @Override
    public String toString() {
        return "HookContext{" + targetClass + "#" + targetMethod
                + ", args=" + (args == null ? "null" : Arrays.toString(args)) + "}";
    }
}
