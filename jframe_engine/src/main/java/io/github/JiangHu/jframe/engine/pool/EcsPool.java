package io.github.JiangHu.jframe.engine.pool;

import io.github.JiangHu.jframe.engine.ecs.engine.EcsLog;
import io.github.JiangHu.jframe.engine.ecs.entity.Component;
import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ECS 对象池集成层 — 组件实例池（按类，懒建）+ 实体壳池。
 *
 * <h3>组件池（opt-in）</h3>
 * <p>仅 {@code @EcsComponent(pooled=true)} 或 {@code registerComponent(id, type, true)}
 * 登记的类参与（由引擎在挂载/卸载管线上判定，本类不感知注册表）。
 * 归池路径 {@link #freeComponent(Component)} 三步：
 * <ol>
 *   <li>框架强制清：{@code bindHost(null)} + {@code bindSource(null)}（框架注入字段不泄漏宿主引用）；</li>
 *   <li>组件自清：{@link Component#reset()}（清 params 之外累积的运行时字段），
 *       抛异常 → 记录日志并丢弃（不入池）；</li>
 *   <li>debug 扫描（{@link EcsPoolConfig#isDebug()}）：反射检查非静态、非 transient 字段
 *       是否偏离默认值，残留 → WARN 并丢弃——把契约违规暴露在开发期。</li>
 * </ol>
 *
 * <h3>实体壳池</h3>
 * <p>{@code obtainEntityShell()} 空闲表空时返回 null（壳只由引擎创建）；
 * 引擎取出后调用 {@code EcsEntity.recycle(newId, binding)} 重生——
 * <b>id 单调递增永不复用</b>（外部旧 id 引用不会复活），只复用壳对象本身。
 * tags 的「失效后可读」语义由 {@code EcsEntity.invalidate} 的快照化保证。
 *
 * <h3>懒建池</h3>
 * <p>组件池在首次 free 该类时才建立——池只为「实际经历过高频生灭」的类存在，
 * 长寿组件零开销；{@link #stats()} 只会出现实际回收过的类。
 *
 * <h3>线程模型</h3>
 * 单线程（Nukkit 主线程），与 {@code EcsEngine} 一致，零锁。
 */
public class EcsPool {

    /**
     * 构造一个关闭状态的池（七参引擎构造传 null 时的占位，全部方法为 no-op / 返回 null）。
     *
     * @return 关闭状态的池
     */
    public static EcsPool disabled() {
        EcsPoolConfig config = new EcsPoolConfig();
        config.setEnabled(false);
        return new EcsPool(config);
    }

    /** 配置 */
    private final EcsPoolConfig config;

    /** 实体壳池（工厂 null：壳只由引擎创建，池只负责回收） */
    private final ObjectPool<EcsEntity> shellPool;

    /** 组件池（类 → 池，懒建） */
    private final Map<Class<?>, ObjectPool<Component>> componentPools = new HashMap<>();

    /**
     * 构造池。
     *
     * @param config 配置
     */
    public EcsPool(EcsPoolConfig config) {
        this.config = config == null ? new EcsPoolConfig() : config;
        this.shellPool = new ObjectPool<>(null, null,
                this.config.getMaxEntityShells(), this.config.isDebug());
    }

    // ==================== 实体壳 ====================

    /**
     * 取实体壳：空闲表空返回 null（由引擎 new）；复用壳的 id 由引擎另行分配（单调不复用）。
     *
     * @return 壳；无空闲 null
     */
    public EcsEntity obtainEntityShell() {
        if (!config.isEnabled()) {
            return null;
        }
        return shellPool.obtain();
    }

    /**
     * 归还实体壳（引擎 removeEntity 在 invalidate 之后调用；状态清理由 recycle 承担）。
     *
     * @param entity 壳
     */
    public void freeEntityShell(EcsEntity entity) {
        if (!config.isEnabled() || entity == null) {
            return;
        }
        shellPool.free(entity);
    }

    // ==================== 组件 ====================

    /**
     * 取组件实例：该类池未建或空闲表空返回 null（由引擎反射新建）。
     *
     * @param type 组件类（须为已登记的池化类，由引擎判定）
     * @return 实例；无可用 null
     */
    public Component obtainComponent(Class<? extends Component> type) {
        if (!config.isEnabled()) {
            return null;
        }
        ObjectPool<Component> pool = componentPools.get(type);
        return pool == null ? null : pool.obtain();
    }

    /**
     * 归还组件实例：框架强制清 → reset → （debug）残留扫描 → 入池。
     * <p>任何一步失败只记录日志，实例丢弃不入池。
     *
     * @param component 组件实例
     */
    public void freeComponent(Component component) {
        if (!config.isEnabled() || component == null) {
            return;
        }
        try {
            component.bindHost(null);
            component.bindSource(null);
            component.reset();
        } catch (Exception e) {
            EcsLog.error("组件 reset 异常，实例丢弃不入池: " + component.getClass().getName(), e);
            return;
        }
        if (config.isDebug() && hasResidualFields(component)) {
            EcsLog.warning("组件 reset 后字段仍偏离默认值，实例丢弃不入池: "
                    + component.getClass().getName() + "（补齐 reset() 清理）");
            return;
        }
        ObjectPool<Component> pool = componentPools.computeIfAbsent(component.getClass(),
                k -> new ObjectPool<>(null, null,
                        config.getMaxComponentPerClass(), config.isDebug()));
        pool.free(component);
    }

    /**
     * debug 扫描：非静态、非 transient 字段是否偏离默认值（基本类型零值 / 引用 null）。
     *
     * @param component 组件实例
     * @return 残留 true
     */
    private boolean hasResidualFields(Component component) {
        for (Class<?> c = component.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(component);
                    if (value != null && !isPrimitiveDefault(value)) {
                        return true;
                    }
                } catch (IllegalAccessException e) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 装箱基本类型是否为默认值。
     */
    private boolean isPrimitiveDefault(Object value) {
        if (value instanceof Integer i) {
            return i == 0;
        }
        if (value instanceof Long l) {
            return l == 0L;
        }
        if (value instanceof Short s) {
            return s == 0;
        }
        if (value instanceof Byte b) {
            return b == 0;
        }
        if (value instanceof Character ch) {
            return ch == 0;
        }
        if (value instanceof Float f) {
            return f == 0f;
        }
        if (value instanceof Double d) {
            return d == 0d;
        }
        if (value instanceof Boolean bo) {
            return !bo;
        }
        return false;
    }

    // ==================== 指标 / 清场 ====================

    /**
     * 指标快照。
     *
     * @return 指标
     */
    public EcsPoolStats stats() {
        Map<String, EcsPoolStats.PoolCounters> components = new LinkedHashMap<>();
        for (Map.Entry<Class<?>, ObjectPool<Component>> entry : componentPools.entrySet()) {
            ObjectPool<Component> pool = entry.getValue();
            components.put(entry.getKey().getSimpleName(), new EcsPoolStats.PoolCounters(
                    pool.created(), pool.reused(), pool.size()));
        }
        return new EcsPoolStats(shellPool.created(), shellPool.reused(),
                shellPool.size(), components, componentPools.size());
    }

    /**
     * 清空全部空闲表（引擎 stop 时释放引用；池为懒建，后续使用自动重建）。
     */
    public void clear() {
        shellPool.clear();
        for (ObjectPool<Component> pool : componentPools.values()) {
            pool.clear();
        }
    }
}
