package io.github.JiangHu.jframe.engine.ecs.gameobject;

import io.github.JiangHu.jframe.engine.ecs.despawner.Despawner;
import io.github.JiangHu.jframe.engine.ecs.selector.EntitySelector;
import io.github.JiangHu.jframe.engine.ecs.spawner.Spawner;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GameObject 定义 — 一条"选择器 + 组件列表（+ 可选 tags / 可选 spawn / 可选 despawn）"的绑定规则。
 *
 * <p>由 {@link GameObjectLoader} 从 YAML 解析并完成校验与实例化：
 * 选择器/生成器/回收器在<b>定义加载时</b>按注册名实例化并注入参数（每条定义引用独立实例）；
 * 组件规格保留 id + 参数表，在<b>每次挂载</b>时实例化注入。
 *
 * @param name       定义名（全局唯一）
 * @param selector   选择器实例（可空 = 仅用于 spawn / 手动挂载）
 * @param tags       命中/spawn 时打到实体上的标签
 * @param components 组件规格列表（注册 id + 参数表）
 * @param spawner    生成器实例（可空 = 纯绑定型定义）
 * @param despawner  回收器实例（可空 = 销毁时不执行世界侧回收）
 */
public record GameObjectDefinition(String name,
                                   EntitySelector selector,
                                   Set<String> tags,
                                   List<ComponentSpec> components,
                                   Spawner spawner,
                                   Despawner despawner) {

    /**
     * 组件规格 — 注册 id + 参数表（参数 key = 组件子类字段名，挂载时注入）。
     *
     * @param id     组件注册 id
     * @param params 参数表（可空）
     */
    public record ComponentSpec(String id, Map<String, Object> params) {
    }
}
