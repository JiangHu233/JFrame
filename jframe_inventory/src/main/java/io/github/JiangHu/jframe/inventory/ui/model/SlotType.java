package io.github.JiangHu.jframe.inventory.ui.model;

import io.github.JiangHu.jframe.inventory.ui.component.InventoryComponent;

/**
 * 格子类型枚举。
 * <p>
 * 决定箱子中每个格子的行为模式：能否交互、能否存取物品。
 * <p>
 * 默认情况下，所有未被组件显式声明的格子都是 {@link #LOCKED}（不可改变），
 * 天然满足「箱子中的格子默认不可改变上面的物品」这一核心需求。
 *
 * @see InventoryComponent
 */
public enum SlotType {

    /**
     * 按钮格子：点击触发 {@code onClick} 回调，禁止放入/取出物品。
     * <p>
     * 典型用途：购买按钮、导航按钮、功能开关。
     */
    BUTTON,

    /**
     * 存储格子：允许玩家自由放入/取出物品，操作时触发 {@code onStore} 回调。
     * <p>
     * 典型用途：金币存储槽、物品回收槽、背包扩展槽。
     */
    STORAGE,

    /**
     * 展示格子：仅用于显示物品，不可交互（点击无反应，禁止放入/取出）。
     * <p>
     * 典型用途：标题栏装饰、商品预览、信息展示。
     */
    DISPLAY,

    /**
     * 锁定格子（默认）：不可交互，物品由框架管理。
     * <p>
     * 所有未被组件显式声明的格子都默认为 LOCKED。
     */
    LOCKED
}
