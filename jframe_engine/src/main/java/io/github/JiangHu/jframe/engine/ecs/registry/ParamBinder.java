package io.github.JiangHu.jframe.engine.ecs.registry;

import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 参数表 → 字段反射注入工具 — 组件 / 选择器 / 生成器三处共用。
 *
 * <h3>核心规则（"属性即子类字段"）</h3>
 * <ul>
 *   <li>参数表 key 与类字段<b>按名匹配</b>，实例化后反射注入（{@code setAccessible(true)}）</li>
 *   <li>字段收集：沿继承链从子类向上收集全部非 {@code static}、非 {@code final}、非 {@code transient}、
 *       非合成字段（子类同名字段覆盖父类；{@code ComponentBase.host} 为 transient 自动排除）</li>
 *   <li>未知字段名 → {@link EcsConfigException}（加载期 fail fast）</li>
 *   <li>未出现的字段保留代码初始值（可选参数语义）</li>
 * </ul>
 *
 * <h3>类型强转</h3>
 * <ul>
 *   <li>基本类型/包装类：{@code Number} 之间互转（intValue/longValue/doubleValue/...）</li>
 *   <li>String → boolean / char / enum（按名）</li>
 *   <li>Number/Boolean → String（{@code String.valueOf}）</li>
 *   <li>{@code List}/{@code Map} 同族直接赋值</li>
 *   <li>转不动抛 {@link EcsConfigException}</li>
 * </ul>
 *
 * <h3>类名解析</h3>
 * {@link #resolveClass(String)} 支持完整类名或简单名（自动在 {@code cn.nukkit.entity.*}、
 * {@code cn.nukkit.entity.mob.*}、{@code cn.nukkit.entity.passive.*}、
 * {@code cn.nukkit.blockentity.*} 下解析），供内置类型选择器等使用，结果缓存。
 */
public final class ParamBinder {

    /** 简单名解析时尝试的 Nukkit 包前缀（实体基础包 + MOT 的 mob/passive 子包 + 方块实体包） */
    private static final String[] NUKKIT_PREFIXES = {
            "cn.nukkit.entity.",
            "cn.nukkit.entity.mob.",
            "cn.nukkit.entity.passive.",
            "cn.nukkit.blockentity."
    };

    /** 类名解析缓存（name → Class；NULL_VALUE 哨兵避免重复失败查找） */
    private static final Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();

    private static final Class<?> CACHE_MISS = Void.class;

    private ParamBinder() {
    }

    /**
     * 把参数表注入目标实例字段。
     *
     * @param target 目标实例（组件/选择器/生成器）
     * @param params 参数表（null 视为空表）
     * @throws EcsConfigException 未知字段名或类型强转失败
     */
    public static void bind(Object target, Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return;
        }
        Map<String, Field> fields = collectFields(target.getClass());
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            Field field = fields.get(key);
            if (field == null) {
                throw new EcsConfigException(
                        target.getClass().getSimpleName() + " 不存在参数字段 '" + key + "'（可用字段: " + fields.keySet() + "）");
            }
            Object value = coerce(entry.getValue(), field.getType(), target.getClass(), key);
            try {
                field.setAccessible(true);
                field.set(target, value);
            } catch (IllegalAccessException e) {
                throw new EcsConfigException("注入字段失败: " + target.getClass().getName() + "." + key, e);
            }
        }
    }

    /**
     * 静态校验参数表（不实例化）：key 存在性 + 值可强转。定义加载期 fail fast 用。
     *
     * @param type   目标类
     * @param params 参数表（null 视为空表）
     * @throws EcsConfigException 未知字段名或值不可强转
     */
    public static void validate(Class<?> type, Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return;
        }
        Map<String, Field> fields = collectFields(type);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            Field field = fields.get(key);
            if (field == null) {
                throw new EcsConfigException(
                        type.getSimpleName() + " 不存在参数字段 '" + key + "'（可用字段: " + fields.keySet() + "）");
            }
            coerce(entry.getValue(), field.getType(), type, key);
        }
    }

    /**
     * 收集类继承链上的可注入字段（子类优先）。
     *
     * @param type 目标类
     * @return 字段名 → Field（保持子类→父类顺序）
     */
    public static Map<String, Field> collectFields(Class<?> type) {
        Map<String, Field> result = new LinkedHashMap<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                int mods = field.getModifiers();
                if (Modifier.isStatic(mods) || Modifier.isFinal(mods)
                        || Modifier.isTransient(mods) || field.isSynthetic()) {
                    continue;
                }
                result.putIfAbsent(field.getName(), field);
            }
        }
        return result;
    }

    /**
     * 类型强转。
     *
     * @param value      原值
     * @param targetType 目标类型
     * @param owner      所属类（异常消息用）
     * @param key        参数名（异常消息用）
     * @return 强转后的值
     * @throws EcsConfigException 无法强转
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object coerce(Object value, Class<?> targetType, Class<?> owner, String key) {
        if (value == null) {
            return null;
        }
        Class<?> target = targetType.isPrimitive() ? wrapperOf(targetType) : targetType;
        if (target.isInstance(value)) {
            return value;
        }
        if (value instanceof Number number) {
            if (target == Integer.class) return number.intValue();
            if (target == Long.class) return number.longValue();
            if (target == Double.class) return number.doubleValue();
            if (target == Float.class) return number.floatValue();
            if (target == Short.class) return number.shortValue();
            if (target == Byte.class) return number.byteValue();
            if (target == String.class) return String.valueOf(value);
        }
        if (value instanceof Boolean bool) {
            if (target == String.class) return String.valueOf(bool);
        }
        if (value instanceof String str) {
            if (target == Boolean.class) return Boolean.parseBoolean(str);
            if (target == Character.class && str.length() == 1) return str.charAt(0);
            if (target == Integer.class) return parseNumber(str, Integer::parseInt, target, owner, key);
            if (target == Long.class) return parseNumber(str, Long::parseLong, target, owner, key);
            if (target == Double.class) return parseNumber(str, Double::parseDouble, target, owner, key);
            if (target == Float.class) return parseNumber(str, Float::parseFloat, target, owner, key);
            if (target.isEnum()) {
                try {
                    return Enum.valueOf((Class<? extends Enum>) target.asSubclass(Enum.class), str);
                } catch (IllegalArgumentException e) {
                    throw new EcsConfigException(owner.getSimpleName() + "." + key
                            + ": 枚举 " + target.getSimpleName() + " 无值 '" + str + "'");
                }
            }
        }
        throw new EcsConfigException(owner.getSimpleName() + "." + key
                + ": 无法把 " + value.getClass().getSimpleName() + " 转为 " + targetType.getSimpleName());
    }

    /**
     * 字符串数值解析（NumberFormatException 统一转为 EcsConfigException）。
     *
     * @param str      字符串值
     * @param parser   解析函数
     * @param target   目标类型（异常消息用）
     * @param owner    所属类（异常消息用）
     * @param key      参数名（异常消息用）
     * @return 解析结果
     */
    private static Object parseNumber(String str, java.util.function.Function<String, Object> parser,
                                      Class<?> target, Class<?> owner, String key) {
        try {
            return parser.apply(str.trim());
        } catch (RuntimeException e) {
            throw new EcsConfigException(owner.getSimpleName() + "." + key
                    + ": 无法把 '" + str + "' 转为 " + target.getSimpleName());
        }
    }

    /**
     * 基本类型 → 包装类。
     *
     * @param primitive 基本类型
     * @return 包装类（非基本类型原样返回）
     */
    private static Class<?> wrapperOf(Class<?> primitive) {
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == double.class) return Double.class;
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == float.class) return Float.class;
        if (primitive == short.class) return Short.class;
        if (primitive == byte.class) return Byte.class;
        if (primitive == char.class) return Character.class;
        return primitive;
    }

    /**
     * 解析类名：完整类名优先，简单名自动在 {@code cn.nukkit.entity.*} /
     * {@code cn.nukkit.blockentity.*} 下查找。
     *
     * @param name 类名（完整或简单）
     * @return 解析到的类
     * @throws EcsConfigException 解析不到
     */
    public static Class<?> resolveClass(String name) {
        if (name == null || name.isBlank()) {
            throw new EcsConfigException("类名不得为空");
        }
        String trimmed = name.trim();
        Class<?> cached = CLASS_CACHE.get(trimmed);
        if (cached != null) {
            if (cached == CACHE_MISS) {
                throw new EcsConfigException("类名解析失败: " + name);
            }
            return cached;
        }
        Class<?> resolved = tryResolve(trimmed);
        CLASS_CACHE.put(trimmed, resolved != null ? resolved : CACHE_MISS);
        if (resolved == null) {
            throw new EcsConfigException("类名解析失败: " + name
                    + "（支持完整类名或 cn.nukkit.entity.* / cn.nukkit.blockentity.* 下的简单名）");
        }
        return resolved;
    }

    /**
     * 依序尝试完整类名与 Nukkit 包前缀。
     *
     * @param trimmed 去空格类名
     * @return 解析到的类；失败 null
     */
    private static Class<?> tryResolve(String trimmed) {
        ClassLoader[] loaders = {
                Thread.currentThread().getContextClassLoader(),
                ParamBinder.class.getClassLoader()
        };
        List<String> candidates = new ArrayList<>();
        candidates.add(trimmed);
        if (!trimmed.contains(".")) {
            for (String prefix : NUKKIT_PREFIXES) {
                candidates.add(prefix + trimmed);
            }
        }
        for (String candidate : candidates) {
            for (ClassLoader loader : loaders) {
                if (loader == null) {
                    continue;
                }
                try {
                    return Class.forName(candidate, true, loader);
                } catch (ClassNotFoundException ignore) {
                    // 尝试下一个候选
                }
            }
        }
        return null;
    }
}
