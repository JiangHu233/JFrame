package io.github.JiangHu.jframe.engine.behavior.builtin;

import io.github.JiangHu.jframe.engine.behavior.BtCondition;
import io.github.JiangHu.jframe.engine.behavior.BtConditionWord;
import io.github.JiangHu.jframe.engine.behavior.BtContext;
import io.github.JiangHu.jframe.engine.behavior.BtEcsAware;
import io.github.JiangHu.jframe.engine.ecs.registry.ComponentRegistry;

/**
 * 内置条件：宿主实体挂有指定组件（<b>能力直读</b>——ECS 集成点，
 * 能力=组件存在性的判别入口；宿主无实体/组件 id 未注册视为不满足）。
 *
 * <p>YAML：{@code {cond: ecs:has-component, params: {component: fapeft-ai-cap-melee}}}
 */
@BtConditionWord("ecs:has-component")
public final class HasComponentCondition extends BtCondition implements BtEcsAware {

    /** 组件注册 id（公有字段=参数键） */
    public String component;

    /** 组件注册表（实例化注入） */
    private ComponentRegistry componentRegistry;

    @Override
    public void bindComponentRegistry(ComponentRegistry registry) {
        this.componentRegistry = registry;
    }

    @Override
    public boolean test(BtContext context) {
        if (component == null || componentRegistry == null
                || context.entity() == null || !componentRegistry.contains(component)) {
            return false;
        }
        try {
            return context.entity().has(componentRegistry.require(component));
        } catch (Exception e) {
            return false; // 解析失败按不满足
        }
    }
}
