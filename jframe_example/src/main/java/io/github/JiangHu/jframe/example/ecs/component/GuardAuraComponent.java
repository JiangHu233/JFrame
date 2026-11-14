package io.github.JiangHu.jframe.example.ecs.component;

import cn.nukkit.Server;
import cn.nukkit.event.player.PlayerInteractEvent;
import io.github.JiangHu.jframe.engine.ecs.entity.ComponentBase;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsComponent;
import io.github.JiangHu.jframe.engine.ecs.annotation.OnEcsEvent;

/**
 * 演示组件 C —— 守卫光环（挂到出生点 64 格内的箱子上，见 guardian-chests 定义）。
 *
 * <p>覆盖特性：
 * <ul>
 *   <li><b>多定义并集</b>：出生点附近的箱子同时挂 magic-chest + guard-aura 两个组件，
 *       右键时<b>两条消息同时出现</b>（两个组件都订阅了同一事件）——并集语义最直观的可见证据</li>
 *   <li>自定义选择器 near-spawn（见 selector 包）+ 参数注入</li>
 * </ul>
 */
@EcsComponent("guard-aura")
public class GuardAuraComponent extends ComponentBase {

    /** 光环时长（可选参数；本定义未配置 params → 保留代码初始值，演示"未出现的字段保留默认"） */
    private int seconds = 3;

    @Override
    public void onMount() {
        Server.getInstance().getLogger().info(
                "[ECS示例] 守卫光环挂载 #" + host().id() + "（seconds=" + seconds + "）");
    }

    @Override
    public void onUnmount() {
        Server.getInstance().getLogger().info("[ECS示例] 守卫光环卸载 #" + host().id());
    }

    /**
     * 点击事件 —— 与 MagicChestComponent.onInteract 同事件；
     * 出生点 64 格内右键箱子 → 两条消息（并集可见）。
     */
    @OnEcsEvent
    public void onInteract(PlayerInteractEvent event) {
        event.getPlayer().sendMessage("§b守卫光环保护中 §e#" + host().id()
                + " §7（" + seconds + "s）");
    }
}
