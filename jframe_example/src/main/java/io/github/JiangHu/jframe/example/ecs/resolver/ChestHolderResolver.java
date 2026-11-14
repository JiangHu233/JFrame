package io.github.JiangHu.jframe.example.ecs.resolver;

import cn.nukkit.event.Event;
import cn.nukkit.event.inventory.InventoryOpenEvent;
import io.github.JiangHu.jframe.engine.ecs.resolver.EcsEventResolver;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsResolver;

import java.util.List;

/**
 * 演示宿主解析器 —— 库存持有者（"歪门邪道对象"的典型代表）。
 *
 * <p>{@code InventoryOpenEvent} 无法用 {@code getEntity()} 拿到当事实体：
 * 真正的宿主是<b>库容的持有者</b>（箱子/木桶等方块实体）。本解析器提取持有者作为候选，
 * 引擎换算后查绑定索引，事件即落到"被打开的那个箱子"的组件上
 * （{@code MagicChestComponent.onOpen}）。
 */
@EcsResolver("chest-holder")
public class ChestHolderResolver implements EcsEventResolver {

    @Override
    public Class<? extends Event> eventType() {
        return InventoryOpenEvent.class;
    }

    @Override
    public List<Object> extractHosts(Event event) {
        if (event instanceof InventoryOpenEvent openEvent) {
            Object holder = openEvent.getInventory().getHolder();
            if (holder != null) {
                return List.of(holder);
            }
        }
        return List.of();
    }
}
