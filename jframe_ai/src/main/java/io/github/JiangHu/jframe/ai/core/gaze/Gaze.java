package io.github.JiangHu.jframe.ai.core.gaze;

/**
 * 视角修正器(接口):导航器每 tick 调用一次,决定实体的朝向表现。
 * <p>
 * 修正器拥有对 {@code entity.yaw / headYaw / pitch} 的完全写权——
 * 可以实现"头身同向朝移动方向"(默认)、"头朝目标、身体沿移动方向"(横向走位)、
 * "固定朝向"等任意视角策略;实现类可保存内部状态(如平滑转头的当前显示朝向),
 * 实例随 {@link io.github.JiangHu.jframe.ai.core.navigation.Navigator} 生命周期独享,不会跨实体共享。
 *
 * <h3>内置实现(手动 new,同 tactical 层风格)</h3>
 * <ul>
 *   <li>{@link MovementGaze} —— 头+身朝移动方向(默认)</li>
 *   <li>{@link TargetGaze} —— 头(含 pitch)朝目标、身体沿移动方向;{@code bodyFollow()} 选项让身体也朝目标</li>
 *   <li>{@link FixedGaze} —— 头身固定朝向</li>
 *   <li>{@link SmoothGaze} —— 装饰器:有限角速度逼近 + 反应延迟,模拟真人转头</li>
 * </ul>
 *
 * <h3>自定义示例</h3>
 * <pre>{@code
 * // 走位时盯着敌人,头以每 tick 最多 10° 平滑转向
 * ai.walk(npc).to(flankPos)
 *         .gaze(new SmoothGaze(new TargetGaze(enemy)).maxTurnSpeed(10))
 *         .start();
 *
 * // 完全自定义(lambda 即可)
 * ai.walk(npc).to(pos).gaze(ctx -> {
 *     ctx.entity().headYaw = myLookLogic(ctx);
 * }).start();
 * }</pre>
 *
 * @see GazeContext
 */
@FunctionalInterface
public interface Gaze {

    /**
     * 应用视角修正:直接写入实体的 {@code yaw / headYaw / pitch}。
     * <p>
     * 由导航器在每 tick 设置完速度向量后调用(主线程)。
     *
     * @param ctx 修正上下文(实体、本 tick 移动向量、当前路径节点)
     */
    void apply(GazeContext ctx);
}
