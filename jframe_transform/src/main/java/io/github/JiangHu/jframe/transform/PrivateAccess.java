package io.github.JiangHu.jframe.transform;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 私有字段/方法读写工具（门面侧，纯反射 setAccessible——同 JVM 非 JDK 类无模块限制）。
 * <p>
 * 观测目标对象的私有状态<b>无需动字节码</b>；回调内亦可用
 * {@code HookContext.field/setField/call} 便捷法（带目标实例语义）。
 * JDK/模块封装类反射会被拒绝并抛 {@link IllegalStateException}。
 */
public final class PrivateAccess {

    /** 字段缓存：类名#字段名 → Field（或 NoSuchFieldException 哨兵） */
    private static final Map<String, Object> FIELD_CACHE = new ConcurrentHashMap<>();

    /** 方法缓存：类名#方法名/参数个数 → Method（或 NoSuchMethodException 哨兵） */
    private static final Map<String, Object> METHOD_CACHE = new ConcurrentHashMap<>();

    private PrivateAccess() {
    }

    /**
     * 读字段（含 private / 父类字段）。
     *
     * @param target    目标对象（读实例字段）
     * @param fieldName 字段名
     * @return 字段值（基本类型为包装类）
     */
    public static Object get(Object target, String fieldName) {
        if (target == null) {
            throw new IllegalArgumentException("target 不能为 null（static 字段请用 getStatic）");
        }
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读取字段失败: " + fieldName, e);
        }
    }

    /**
     * 读 static 字段。
     *
     * @param targetClass 目标类
     * @param fieldName   字段名
     * @return 字段值
     */
    public static Object getStatic(Class<?> targetClass, String fieldName) {
        try {
            return findField(targetClass, fieldName).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读取 static 字段失败: " + fieldName, e);
        }
    }

    /**
     * 写字段（含 private / 父类字段；拒绝 static final）。
     *
     * @param target    目标对象
     * @param fieldName 字段名
     * @param value     新值（基本类型传包装类）
     */
    public static void set(Object target, String fieldName, Object value) {
        if (target == null) {
            throw new IllegalArgumentException("target 不能为 null");
        }
        try {
            Field f = findField(target.getClass(), fieldName);
            if (Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers())) {
                throw new IllegalStateException("拒绝写入 static final 字段: " + fieldName);
            }
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("写入字段失败: " + fieldName, e);
        }
    }

    /**
     * 调方法（含 private / 父类方法；按 方法名+实参个数 匹配，重载同参个数时取首个）。
     *
     * @param target       目标对象
     * @param methodName   方法名
     * @param args         实参
     * @return 返回值（基本类型为包装类）
     */
    public static Object invoke(Object target, String methodName, Object... args) {
        if (target == null) {
            throw new IllegalArgumentException("target 不能为 null");
        }
        int argc = args == null ? 0 : args.length;
        try {
            Method m = findMethod(target.getClass(), methodName, argc);
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("反射调用抛出异常: " + methodName, cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("调用方法失败: " + methodName + "/" + argc + "参", e);
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
                try {
                    f.setAccessible(true);
                } catch (Exception denied) {
                    throw new IllegalStateException("反射访问被拒（JDK/模块封装类）: " + key, denied);
                }
                FIELD_CACHE.putIfAbsent(key, f);
                return f;
            } catch (NoSuchFieldException ignored) {
                // 沿父类链
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
                    try {
                        m.setAccessible(true);
                    } catch (Exception denied) {
                        throw new IllegalStateException("反射访问被拒（JDK/模块封装类）: " + key, denied);
                    }
                    METHOD_CACHE.putIfAbsent(key, m);
                    return m;
                }
            }
        }
        NoSuchMethodException marker = new NoSuchMethodException(key);
        METHOD_CACHE.putIfAbsent(key, marker);
        throw new IllegalStateException("方法不存在（含父类链）: " + key, marker);
    }
}
