package io.github.JiangHu.jframe.engine.pool;

import java.util.Map;

/**
 * ECS 池指标快照（{@link EcsPool#stats()} 产物）。
 *
 * @param shellsCreated   实体壳累计新建
 * @param shellsReused    实体壳累计复用
 * @param shellsIdle      实体壳当前空闲
 * @param components      组件类简单名 → 计数（仅实际经历过回收的类出现 = 懒建池）
 * @param componentPools  已建组件池数量
 */
public record EcsPoolStats(long shellsCreated, long shellsReused, int shellsIdle,
                           Map<String, PoolCounters> components, int componentPools) {

    /**
     * 单池计数。
     *
     * @param created 累计新建
     * @param reused  累计复用
     * @param idle    当前空闲
     */
    public record PoolCounters(long created, long reused, int idle) {
    }
}
