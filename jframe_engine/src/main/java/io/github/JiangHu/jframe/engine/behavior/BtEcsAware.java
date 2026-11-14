package io.github.JiangHu.jframe.engine.behavior;

import io.github.JiangHu.jframe.engine.ecs.registry.ComponentRegistry;

/**
 * ECS 感知词表 — 内置/插件词表需要组件注册表（id→类解析）时实现本接口，
 * 实例化时由注册表回调注入（引擎装配链经 BtAPI 传入，避免词表反向依赖宿主）。
 */
public interface BtEcsAware {

    /**
     * 注入组件注册表（实例化后立即回调一次）。
     *
     * @param registry 组件注册表（可能为 null——引擎极早期；词表应容错）
     */
    void bindComponentRegistry(ComponentRegistry registry);
}
