package io.github.JiangHu.jframe.engine.behavior;

import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;
import io.github.JiangHu.jframe.engine.ecs.registry.ComponentRegistry;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行为词表注册表 — 词名 → 类（行为/条件叶工厂）。
 *
 * <p>词表类经注解扫描注册（@{@link BtActionWord}/@{@link BtConditionWord}）；
 * 构造节点实例时反射注入 params 到公有字段（字段名=参数键，写错键加载期
 * {@link EcsConfigException}——fail fast）；实现 {@link BtEcsAware} 的词表
 * 实例化后回调注入组件注册表（能力直读词表用）。
 */
public final class BtRegistry {

    /** 词名 → 行为叶类 */
    private final Map<String, Class<? extends BtAction>> actions = new ConcurrentHashMap<>();

    /** 词名 → 条件叶类 */
    private final Map<String, Class<? extends BtCondition>> conditions = new ConcurrentHashMap<>();

    /** 组件注册表（BtAPI 装配时注入；BtEcsAware 词表回调用，可 null） */
    private volatile ComponentRegistry componentRegistry;

    /**
     * 注入组件注册表（BtAPI 构造时调用一次）。
     *
     * @param registry 组件注册表
     */
    public void bindComponentRegistry(ComponentRegistry registry) {
        this.componentRegistry = registry;
    }

    /**
     * 注册行为词。
     *
     * @param name 词名
     * @param type 行为叶类
     */
    public void registerAction(String name, Class<? extends BtAction> type) {
        if (actions.putIfAbsent(name, type) != null || conditions.containsKey(name)) {
            throw new EcsConfigException("BT 词名冲突: '" + name + "'");
        }
    }

    /**
     * 注册条件词。
     *
     * @param name 词名
     * @param type 条件叶类
     */
    public void registerCondition(String name, Class<? extends BtCondition> type) {
        if (conditions.putIfAbsent(name, type) != null || actions.containsKey(name)) {
            throw new EcsConfigException("BT 词名冲突: '" + name + "'");
        }
    }

    /**
     * 实例化行为叶（params 注入公有字段 + BtEcsAware 回调）。
     *
     * @param name   词名
     * @param params 参数（可空）
     * @return 节点实例
     * @throws EcsConfigException 词名未注册 / 参数注入失败（键名拼错等）
     */
    public BtAction newAction(String name, Map<String, Object> params) {
        Class<? extends BtAction> type = actions.get(name);
        if (type == null) {
            throw new EcsConfigException("BT 行为词未注册: '" + name + "'");
        }
        return (BtAction) postInstantiate(instantiate(type, name, params));
    }

    /**
     * 实例化条件叶（params 注入公有字段 + BtEcsAware 回调）。
     *
     * @param name   词名
     * @param params 参数（可空）
     * @return 节点实例
     * @throws EcsConfigException 词名未注册 / 参数注入失败
     */
    public BtCondition newCondition(String name, Map<String, Object> params) {
        Class<? extends BtCondition> type = conditions.get(name);
        if (type == null) {
            throw new EcsConfigException("BT 条件词未注册: '" + name + "'");
        }
        return (BtCondition) postInstantiate(instantiate(type, name, params));
    }

    /** BtEcsAware 词表注入组件注册表后原样返回 */
    private BtNode postInstantiate(BtNode node) {
        if (node instanceof BtEcsAware aware) {
            aware.bindComponentRegistry(componentRegistry);
        }
        return node;
    }

    /** 反射实例化 + 公有字段注入（键名拼错 fail fast） */
    private <T extends BtNode> T instantiate(Class<T> type, String wordName,
                                             Map<String, Object> params) {
        try {
            T instance = type.getDeclaredConstructor().newInstance();
            Map<String, Object> safe = params != null ? params : Map.of();
            for (Map.Entry<String, Object> entry : safe.entrySet()) {
                Field field = findField(type, entry.getKey());
                if (field == null) {
                    throw new EcsConfigException("BT 词 '" + wordName + "' 无参数字段 '"
                            + entry.getKey() + "'（类 " + type.getSimpleName()
                            + "；词表公有字段名即参数键，请核对拼写）");
                }
                Object coerced = coerce(entry.getValue(), field.getType(), wordName, entry.getKey());
                field.setAccessible(true);
                field.set(instance, coerced);
            }
            return instance;
        } catch (EcsConfigException e) {
            throw e;
        } catch (Exception e) {
            throw new EcsConfigException("BT 词 '" + wordName + "' 实例化失败: " + e.getMessage(), e);
        }
    }

    /** 沿继承链找公有字段 */
    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                return current.getField(name);
            } catch (NoSuchFieldException ignored) {
                // 沿链继续
            }
        }
        return null;
    }

    /** 标量强转（数值/字符串/布尔互通，宽松于 ParamBinder 的一段子集） */
    private static Object coerce(Object value, Class<?> target, String word, String key) {
        if (value == null || target.isInstance(value)) {
            return value;
        }
        try {
            if (target == int.class || target == Integer.class) {
                return ((Number) value).intValue();
            }
            if (target == long.class || target == Long.class) {
                return ((Number) value).longValue();
            }
            if (target == double.class || target == Double.class) {
                return ((Number) value).doubleValue();
            }
            if (target == float.class || target == Float.class) {
                return ((Number) value).floatValue();
            }
            if (target == boolean.class || target == Boolean.class) {
                return Boolean.parseBoolean(String.valueOf(value));
            }
            if (target == String.class) {
                return String.valueOf(value);
            }
        } catch (Exception e) {
            // 落入下方类型不匹配报错
        }
        throw new EcsConfigException("BT 词 '" + word + "' 参数 '" + key
                + "' 类型不匹配: " + value.getClass().getSimpleName() + " → " + target.getSimpleName());
    }

    /**
     * 已注册行为词清单（诊断）。
     *
     * @return 词名列表
     */
    public List<String> actionWords() {
        return List.copyOf(actions.keySet());
    }

    /**
     * 已注册条件词清单（诊断）。
     *
     * @return 词名列表
     */
    public List<String> conditionWords() {
        return List.copyOf(conditions.keySet());
    }

    /**
     * 内部词表预注册（模块装配时调用）。
     *
     * @param builtin 内置词表集合
     */
    public void registerBuiltins(Iterable<BtNode> builtin) {
        // 预留（内置词表经注解类注册走同一 registerXxx 路径）
    }

    /**
     * 空参数表（加载器便利）。
     *
     * @return 空映射
     */
    public static Map<String, Object> noParams() {
        return new LinkedHashMap<>();
    }
}
