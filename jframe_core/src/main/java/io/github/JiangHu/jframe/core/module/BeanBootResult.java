package io.github.JiangHu.jframe.core.module;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ModuleManager#boot} 批量引导结果：本次引导产出的 bean 类型与实例快照。
 * <p>
 * 供宿主插件做接口式遍历（如生命周期钩子、依赖注入满意度收集）——
 * {@link #ofType(Class)} 即"容器内某接口的全部实现"语义。
 */
public final class BeanBootResult {

    private final Map<Class<?>, Object> instances;

    BeanBootResult(Map<Class<?>, Object> instances) {
        this.instances = new LinkedHashMap<>(instances);
    }

    /**
     * 本次引导注册的全部 bean 类型（引导顺序）。
     *
     * @return 类型集合
     */
    public Set<Class<?>> beanTypes() {
        return instances.keySet();
    }

    /**
     * 本次引导的全部 bean 实例（引导顺序）。
     *
     * @return 实例列表
     */
    public List<Object> instances() {
        return List.copyOf(instances.values());
    }

    /**
     * 过滤某类型（含接口/父类）的全部 bean。
     *
     * @param type 目标类型
     * @param <T>  类型
     * @return 类全名 → 实例（保持引导顺序）
     */
    @SuppressWarnings("unchecked")
    public <T> Map<String, T> ofType(Class<T> type) {
        Map<String, T> matched = new LinkedHashMap<>();
        for (Map.Entry<Class<?>, Object> entry : instances.entrySet()) {
            if (type.isInstance(entry.getValue())) {
                matched.put(entry.getKey().getName(), (T) entry.getValue());
            }
        }
        return matched;
    }
}
