package io.github.JiangHu.jframe.engine.ecs.spawner;

/**
 * 实体生成器 — 按注册名 + 参数表驱动，现场产出 gameobject 代理的游戏内容。
 *
 * <h3>注册类 + 参数表（与组件/选择器/回收器同构）</h3>
 * <p>
 * 生成器是开发者定义并注册的类；定义文件中以 {@code spawn: { use: 名称, params: {...} }}
 * 引用，引擎在<b>定义加载时</b>按注册名实例化并注入参数（属性即子类字段）。
 * 调用 {@code ecs.spawn(name, position)} 时取该实例执行 {@link #spawn(SpawnContext)}。
 *
 * <h3>返回值 = 产物（任意对象）</h3>
 * <p>
 * 返回值即实体将代理的游戏内容引用——不必是实体类对象：
 * <ul>
 *   <li>{@code cn.nukkit.entity.Entity} / {@code cn.nukkit.blockentity.BlockEntity} — 世界对象</li>
 *   <li>{@code Level}、任意资源 POJO（如 ArenaRef）——代理目标不限于 entity</li>
 *   <li>{@code null} — 纯虚拟实体（无代理引用）</li>
 * </ul>
 * 返回后引擎统一走进入管线（建实体代理产物 → 本定义组件 + 其他匹配定义并集挂载）。
 *
 * <h3>内置生成器</h3>
 * <ul>
 *   <li>{@code virtual} — 纯虚拟</li>
 *   <li>{@code entity} — 按 network id 或类型名生成 Nukkit 实体</li>
 *   <li>{@code block} — 放置方块产生方块实体（如箱子）</li>
 * </ul>
 *
 * @see io.github.JiangHu.jframe.engine.ecs.annotation.EcsSpawner
 * @see SpawnContext
 * @see Despawner
 */
public interface Spawner {

    /**
     * 执行世界侧生产。
     *
     * @param context 生成上下文（定义名 / 位置 / 引擎）
     * @return 产物（任意对象，将成为实体的代理引用）；null = 纯虚拟
     */
    Object spawn(SpawnContext context);
}
