package io.github.JiangHu.jframe.engine.ecs.entity;

import io.github.JiangHu.jframe.engine.ecs.despawner.Despawner;
import io.github.JiangHu.jframe.engine.ecs.engine.EcsEngine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * ECS 实体 — gameobject 对游戏内容引用的<b>代理</b>（long id + 标签集合 + 万能引用 + 组件表）。
 *
 * <h3>代理模型（统一 Object 引用）</h3>
 * <p>实体持有唯一 {@link #binding()} 引用，指向其代理的游戏内容——{@code cn.nukkit.entity.Entity}、
 * {@code BlockEntity}、{@code Level}、{@code Server}、任意资源 POJO 皆可；引用为 null 即<b>纯虚拟
 * gameobject</b>（如波次管理器等无世界对象）。不按类型枚举绑定字段，也不提供类型捷径方法：
 *
 * <ul>
 *   <li>{@code Level lvl = entity.binding();} — 无参自动泛型识别，按赋值目标强转；
 *       类型不符抛 {@link ClassCastException}，引用为 null（虚拟）返回 null 不抛</li>
 *   <li>{@code if (entity.binding() instanceof BlockEntity chest) { ... }} — 判型用 instanceof 模式匹配</li>
 *   <li>{@link #isVirtual()} — 是否纯虚拟</li>
 * </ul>
 *
 * <h3>组件访问</h3>
 * <ul>
 *   <li>{@link #get(Class)} — 取已挂组件实例（按组件类精确匹配），可直接读写字段</li>
 *   <li>{@link #has(Class)} — 是否已挂某组件</li>
 *   <li>{@link #componentTypes()} — 已挂组件类型快照</li>
 * </ul>
 *
 * <p>实例由引擎创建与管理，业务代码不应自行构造。
 *
 * @see EcsEngine
 */
public final class EcsEntity {

    /** 引擎分配的唯一 id（去 final 支持壳复用；<b>id 单调递增永不复用</b>，recycle 时重写） */
    private long id;

    /** 被代理的游戏内容引用（可空 = 纯虚拟；去 final 支持壳复用） */
    private Object binding;

    /** 标签集合（定义文件 tags / 运行时 addTag；invalidate 时快照化换新实例） */
    private Set<String> tags = new LinkedHashSet<>();

    /** 已挂组件（按挂载顺序；键为组件实际类） */
    private final Map<Class<?>, Component> components = new LinkedHashMap<>();

    /** 所属引擎（用于 destroy 委托；恒定不变） */
    private final EcsEngine engine;

    /** 出生记录（仅 spawn/spawnBound 填写；despawn 回调依据） */
    private SpawnRecord spawnRecord;

    /** 有效性标记（引擎 removeEntity 完成逆序卸载后置 false） */
    private boolean valid = true;

    /** 退役标签快照（invalidate 时交接；失效后 tags() 的读取源，保证「失效后仍可读」读到退役内容） */
    private Set<String> retiredTags;

    /**
     * 构造实体（仅引擎调用，业务代码不得自行构造）。
     *
     * @param id      引擎分配 id
     * @param binding 被代理的游戏内容引用（可空）
     * @param engine  所属引擎
     */
    public EcsEntity(long id, Object binding, EcsEngine engine) {
        this.id = id;
        this.binding = binding;
        this.engine = engine;
    }

    /**
     * 实体是否有效（未被引擎移除）。
     * <p>destroy 后为 false；组件访问类方法抛 {@link IllegalStateException}，
     * 标签与代理引用（{@link #tags()}/{@link #hasTag}/{@link #binding()}）保留可用
     * （事后日志与 despawn 回调场景——回调发生在组件表已清之后）。
     *
     * @return 有效 true
     */
    public boolean valid() {
        return valid;
    }

    /**
     * 失效（引擎 removeEntity 在逆序卸载与 despawn 回调<b>完成后</b>调用）。
     * <p>onUnmount 回调期间本实体仍 valid——兄弟组件查询可用（逆序语义：
     * 先卸载的组件此时已返回 null，后卸载的组件仍可读取）。
     * <p><b>仅引擎调用</b>，业务代码不得调用。
     * <p>标签快照化：旧集合交接给 {@link #retiredTags}，实体换新空集合——
     * 失效前取到的 {@link #tags()} 包装视图继续读到<b>退役内容</b>，
     * 壳复用后新实体的标签与之完全隔离（见 {@link #recycle}）。
     */
    public void invalidate() {
        this.valid = false;
        this.retiredTags = this.tags;
        this.tags = new LinkedHashSet<>();
    }

    /**
     * 壳复用重生（<b>仅引擎调用</b>：从池中取出壳后在 enterBound 里重写身份）。
     * <p>{@code id} 为引擎新分配的单调值——<b>只复用壳，不复用身份</b>，
     * 外部持有的旧 id / 旧实体引用不会因复用而「复活」。
     * 组件表在卸载管线结束时已清空；tags 已在 invalidate 时快照化换新。
     *
     * @param id      新 id（单调递增）
     * @param binding 新的被代理引用（可空 = 纯虚拟）
     */
    public void recycle(long id, Object binding) {
        this.id = id;
        this.binding = binding;
        this.spawnRecord = null;
        this.retiredTags = null;
        this.components.clear();
        this.valid = true;
    }

    /**
     * 引擎分配的唯一 id。
     *
     * @return 实体 id
     */
    public long id() {
        return id;
    }

    /**
     * 取被代理的游戏内容引用 — 无参自动泛型识别（按赋值目标强转）。
     *
     * <pre>{@code
     * Level lvl = entity.binding();                          // 强转目标类型
     * if (entity.binding() instanceof BlockEntity chest) {}   // 判型
     * }</pre>
     *
     * @param <T> 目标类型（由赋值处推断；写 var 时为 Object）
     * @return 代理引用；纯虚拟返回 null（不抛）
     * @throws ClassCastException 引用存在但与目标类型不符（强转语义，调用点即失败点）
     */
    @SuppressWarnings("unchecked")
    public <T> T binding() {
        return (T) binding;
    }

    /**
     * 是否纯虚拟 gameobject（无被代理的游戏内容引用）。
     *
     * @return 虚拟 true
     */
    public boolean isVirtual() {
        return binding == null;
    }

    /**
     * 是否拥有指定标签（失效后读退役快照，语义同 {@link #tags()}）。
     *
     * @param tag 标签名
     * @return 有则 true
     */
    public boolean hasTag(String tag) {
        return currentTags().contains(tag);
    }

    /**
     * 添加标签。
     *
     * @param tag 标签名
     */
    public void addTag(String tag) {
        if (tag != null && !tag.isBlank()) {
            currentTags().add(tag);
        }
    }

    /**
     * 移除标签。
     *
     * @param tag 标签名
     */
    public void removeTag(String tag) {
        currentTags().remove(tag);
    }

    /**
     * 标签快照。
     * <p>失效后读取<b>退役快照</b>（invalidate 时刻的内容），与壳复用后的新实体隔离。
     *
     * @return 不可变标签集合
     */
    public Set<String> tags() {
        return Collections.unmodifiableSet(currentTags());
    }

    /**
     * 当前生效的标签容器（有效 = 实时容器；失效 = 退役快照）。
     */
    private Set<String> currentTags() {
        return valid ? tags : retiredTags;
    }

    /**
     * 取已挂组件实例（按组件类精确匹配）。
     *
     * @param componentType 组件类
     * @param <T>           组件类型
     * @return 组件实例；未挂载返回 null
     * @throws IllegalStateException 实体已销毁
     */
    public <T extends Component> T get(Class<T> componentType) {
        requireValid();
        return componentType.cast(components.get(componentType));
    }

    /**
     * Optional 风格取已挂组件实例。
     *
     * @param componentType 组件类
     * @param <T>           组件类型
     * @return Optional 包装的组件实例
     * @throws IllegalStateException 实体已销毁
     */
    public <T extends Component> java.util.Optional<T> find(Class<T> componentType) {
        return java.util.Optional.ofNullable(get(componentType));
    }

    /**
     * 是否已挂某组件。
     *
     * @param componentType 组件类
     * @return 已挂则 true
     * @throws IllegalStateException 实体已销毁
     */
    public boolean has(Class<?> componentType) {
        requireValid();
        return components.containsKey(componentType);
    }

    /**
     * 已挂组件类型快照（按挂载顺序）。
     *
     * @return 不可变类型集合
     * @throws IllegalStateException 实体已销毁
     */
    public Set<Class<?>> componentTypes() {
        requireValid();
        return Collections.unmodifiableSet(new LinkedHashSet<>(components.keySet()));
    }

    /**
     * 实例级挂载兄弟组件（委托引擎，与定义挂载共用去重与回滚语义）。
     * <p>可在组件回调（onMount/事件处理器）内调用——引擎容器遍历均为快照或独立结构，无并发修改冲突。
     *
     * @param type   组件类
     * @param params 参数表（可 null）
     * @throws IllegalStateException 实体已销毁
     */
    public void mount(Class<? extends Component> type, Map<String, Object> params) {
        requireValid();
        engine.mountComponent(this, type, params);
    }

    /**
     * 实例级按注册 id 卸载组件（回调 onUnmount）。
     *
     * @param componentId 组件注册 id
     * @throws IllegalStateException 实体已销毁
     */
    public void unmount(String componentId) {
        requireValid();
        engine.unmountComponent(this, componentId);
    }

    /**
     * 销毁实体 — 卸载全部组件（依逆序回调 onUnmount）并从引擎移除；
     * 若实体经 spawn/spawnBound 出生且定义配了 despawn，在世界侧执行回收回调（见 {@link EcsEngine#removeEntity}）。
     */
    public void destroy() {
        engine.removeEntity(this);
    }

    /**
     * 组件访问守卫。
     */
    private void requireValid() {
        if (!valid) {
            throw new IllegalStateException("实体已销毁 #" + id + "（组件访问不可用；tags/binding 读取仍允许）");
        }
    }

    @Override
    public String toString() {
        String bound;
        if (binding instanceof cn.nukkit.blockentity.BlockEntity blockEntity) {
            bound = "blockEntity:" + blockEntity.getName();
        } else if (binding instanceof cn.nukkit.entity.Entity nukkitEntity) {
            bound = "entity:" + nukkitEntity.getId();
        } else if (binding != null) {
            bound = binding.getClass().getSimpleName();
        } else {
            bound = "virtual";
        }
        return "EcsEntity{" + id + ", " + bound + ", tags=" + tags + "}";
    }

    // -------------------- 以下为引擎内部维护方法（业务代码不得调用） --------------------

    /**
     * 记录组件（引擎挂载管线调用）。
     *
     * @param component 组件实例
     */
    public void putComponent(Component component) {
        components.put(component.getClass(), component);
    }

    /**
     * 移除组件记录（引擎卸载管线调用）。
     *
     * @param componentType 组件类
     */
    public void removeComponent(Class<? extends Component> componentType) {
        components.remove(componentType);
    }

    /**
     * 已挂组件快照（按挂载顺序）。
     *
     * @return 组件实例列表副本
     */
    public java.util.List<Component> mountedComponents() {
        return new java.util.ArrayList<>(components.values());
    }

    /**
     * 记录出生（仅引擎 spawn/spawnBound 调用；despawn 回调依据）。
     *
     * @param spawnRecord 出生记录
     */
    public void recordSpawn(SpawnRecord spawnRecord) {
        this.spawnRecord = spawnRecord;
    }

    /**
     * 出生记录（引擎 removeEntity 触发 despawn 用；可空）。
     *
     * @return 记录；非 spawn 出生返回 null
     */
    public SpawnRecord spawnRecord() {
        return spawnRecord;
    }

    /**
     * 出生记录 — 定义名 + 回收器（定义 despawn 节点加载期实例化注入的 Despawner）。
     *
     * @param definitionName 出生定义名
     * @param despawner      回收器（可空 = 定义未配 despawn）
     */
    public record SpawnRecord(String definitionName, Despawner despawner) {
    }
}
