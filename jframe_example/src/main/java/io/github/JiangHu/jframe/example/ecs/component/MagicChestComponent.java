package io.github.JiangHu.jframe.example.ecs.component;

import cn.nukkit.Server;
import cn.nukkit.event.inventory.InventoryOpenEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import io.github.JiangHu.jframe.engine.ecs.entity.ComponentBase;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsComponent;
import io.github.JiangHu.jframe.engine.ecs.annotation.OnEcsEvent;

/**
 * 演示组件 A —— 魔法箱（挂到所有 {@code BlockEntityChest} 上，见 all-chests 定义）。
 *
 * <p>覆盖特性：
 * <ul>
 *   <li>生命周期：onMount/onUnmount 控制台日志（挂载与卸载可见）</li>
 *   <li>参数即字段：sound/radius 由 YAML params 注入（未配置保留默认值）</li>
 *   <li>宿主识别：回调内 host() 取宿主实体 id</li>
 *   <li>事件桥双通道：
 *     <ul>
 *       <li>方法内首行守卫 —— 仅右键方块触发（左键不回话 = 守卫可见），
 *           经内置 block-interact 解析器（点击方块 → 方块实体）</li>
 *       <li>自定义解析器 chest-holder —— InventoryOpenEvent（打开库存）落到宿主组件</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>实机预期：右键箱子连续收到两条消息（interact + open，事件双连发属预期）；
 * 左键只有 onInteract 被守卫拦下、onOpen 不触发 → 无任何消息。
 */
@EcsComponent("magic-chest")
public class MagicChestComponent extends ComponentBase {

    /** 音效名（params.sound 注入；仅作参数注入演示，本示例不播放） */
    private String sound = "random.orb";

    /** 半径（params.radius 注入） */
    private double radius = 5.0;

    @Override
    public void onMount() {
        Server.getInstance().getLogger().info(
                "[ECS示例] 魔法箱挂载 #" + host().id() + "（radius=" + radius + ", sound=" + sound + "）");
    }

    @Override
    public void onUnmount() {
        Server.getInstance().getLogger().info("[ECS示例] 魔法箱卸载 #" + host().id());
    }

    /**
     * 点击事件 —— 方法内首行守卫：仅右键方块触发（左键不回话）。
     */
    @OnEcsEvent
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
            return; // 守卫：直接读事件字段，无外部机制
        }
        event.getPlayer().sendMessage("§6你触碰了魔法箱 §e#" + host().id()
                + " §7（radius=" + radius + "）");
    }

    /**
     * 打开库存事件 —— 自定义 chest-holder 解析器提取持有者（箱子）落到本组件。
     */
    @OnEcsEvent
    public void onOpen(InventoryOpenEvent event) {
        event.getPlayer().sendMessage("§d你打开了魔法箱 §e#" + host().id());
    }
}
