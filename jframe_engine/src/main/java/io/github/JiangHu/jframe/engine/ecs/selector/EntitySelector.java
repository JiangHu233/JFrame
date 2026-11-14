package io.github.JiangHu.jframe.engine.ecs.selector;

import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;

/**
 * 实体选择器 — 判定某个 {@link EcsEntity} 是否命中某条 GameObject 定义。
 *
 * <h3>注册类 + 参数表（与组件/生成器同构）</h3>
 * <p>
 * 选择器是开发者定义并注册的类；定义文件中以 {@code selector: { use: 名称, params: {...} }}
 * 引用，引擎在<b>定义加载时</b>按注册名实例化并把参数表按字段名注入其实例字段
 * （属性即子类字段，见 {@code ParamBinder}），之后以 {@link #matches(EcsEntity)} 判定。
 *
 * <h3>匹配时机</h3>
 * <ul>
 *   <li>实体进入（世界 diff 新增 / spawn / 手动注册）时评估全部已注册定义</li>
 *   <li>定义加载时回溯评估全部存量实体</li>
 *   <li>实体状态变化不自动重评，需手动 {@code EcsAPI.refresh(entity)}</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @EcsSelector("near-spawn")
 * public class NearSpawnSelector implements EntitySelector {
 *     private double distance = 32;   // params.distance 可覆盖
 *
 *     @Override
 *     public boolean matches(EcsEntity e) {
 *         Object binding = e.binding();
 *         Position p = binding instanceof Position pos ? pos : null;   // 代理引用判型
 *         return p != null && p.distance(p.getLevel().getSpawnLocation()) <= distance;
 *     }
 * }
 * }</pre>
 *
 * @see io.github.JiangHu.jframe.engine.ecs.annotation.EcsSelector
 */
public interface EntitySelector {

    /**
     * 判定实体是否命中定义。
     *
     * @param entity 待判定的 ECS 实体
     * @return 命中返回 true（引擎将为其挂载该定义的组件）
     */
    boolean matches(EcsEntity entity);
}
