package io.github.JiangHu.jframe.inventory.codec;

import cn.nukkit.inventory.BaseInventory;
import cn.nukkit.inventory.InventoryHolder;
import cn.nukkit.inventory.InventoryType;

/**
 * 测试专用物品栏 —— 继承 {@link BaseInventory} 的最小可实例化子类。
 * <p>
 * {@link BaseInventory} 本身是抽象类（无抽象方法），无法直接实例化；
 * 本类仅提供一个公开构造器，使单元测试能够创建真实的物品栏实例，
 * 用于验证 {@link InventoryCodec#apply} / {@link InventoryCodec#applyJson} 的填充行为。
 * <p>
 * 仅用于测试，不放入主源码。
 */
public class FakeInventory extends BaseInventory {

    /**
     * 构造指定类型与容量的测试物品栏。
     *
     * @param holder 持有者，测试中通常传 {@code null}
     * @param type   物品栏类型（决定默认容量，如 {@link InventoryType#CHEST} 为 27）
     */
    public FakeInventory(InventoryHolder holder, InventoryType type) {
        super(holder, type);
    }
}
