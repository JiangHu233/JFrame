package io.github.JiangHu.jframe.inventory.ui.view;

import cn.nukkit.item.Item;
import io.github.JiangHu.jframe.inventory.ui.component.InventoryComponent;
import io.github.JiangHu.jframe.inventory.ui.model.SlotAppearance;
import io.github.JiangHu.jframe.inventory.ui.model.SlotType;

/**
 * 渲染上下文（RenderContext）。
 * <p>
 * 组件在 {@code onRender} 阶段通过本对象向箱子格子写入外观数据。
 * 核心职责：<strong>坐标转换</strong> —— 将组件内的相对坐标转为箱子的绝对格子序号。
 *
 * <h3>坐标系统</h3>
 * <ul>
 *   <li>箱子是 9 列 × N 行的网格</li>
 *   <li>组件使用相对坐标（相对于父容器的偏移）</li>
 *   <li>绝对格子序号：{@code absIndex = absRow * 9 + absCol}</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @Override
 * protected void onRender(RenderContext ctx) {
 *     ctx.slot(0, 0, SlotAppearance.builder()
 *         .type(Item.DIAMOND_SWORD)
 *         .name("§b购买武器")
 *         .build(), SlotType.BUTTON);
 * }
 * }</pre>
 *
 * @see InventoryView
 * @see SlotAppearance
 */
public class RenderContext {

    private final InventoryView view;
    private final InventoryComponent component;
    private final int baseRow;
    private final int baseCol;

    /**
     * 创建渲染上下文。
     *
     * @param view      所属视图
     * @param component 当前正在渲染的组件（作为格子拥有者）
     * @param baseRow   基准行（绝对坐标）
     * @param baseCol   基准列（绝对坐标）
     */
    RenderContext(InventoryView view, InventoryComponent component, int baseRow, int baseCol) {
        this.view = view;
        this.component = component;
        this.baseRow = baseRow;
        this.baseCol = baseCol;
    }

    /**
     * 在相对位置放置物品外观。
     * <p>
     * 自动将相对坐标转为绝对格子序号并写入视图的 slots 数组。
     *
     * @param relRow    相对行（相对于当前组件）
     * @param relCol    相对列
     * @param appearance 物品外观（{@code null} 表示不设置物品，仅标记类型）
     * @param type      格子类型
     */
    public void slot(int relRow, int relCol, SlotAppearance appearance, SlotType type) {
        int absRow = baseRow + relRow;
        int absCol = baseCol + relCol;
        view.setSlot(absRow, absCol, appearance, type, component);
    }

    /**
     * 在相对位置放置原生物品（便捷方法，内部转为 SlotAppearance）。
     *
     * @param relRow 相对行
     * @param relCol 相对列
     * @param item   原生物品
     * @param type   格子类型
     */
    public void slot(int relRow, int relCol, Item item, SlotType type) {
        SlotAppearance appearance = SlotAppearance.builder()
                .type(item.getId())
                .meta(item.getDamage())
                .count(item.getCount())
                .name(item.hasCustomName() ? item.getCustomName() : null)
                .build();
        slot(relRow, relCol, appearance, type);
    }

    /**
     * 用同一外观填充整个组件区域。
     *
     * @param appearance 物品外观
     * @param type       格子类型
     */
    public void fill(SlotAppearance appearance, SlotType type) {
        for (int r = 0; r < component.getHeight(); r++) {
            for (int c = 0; c < component.getWidth(); c++) {
                slot(r, c, appearance, type);
            }
        }
    }

    /**
     * 清空指定相对位置的格子（恢复为默认 LOCKED）。
     *
     * @param relRow 相对行
     * @param relCol 相对列
     */
    public void clear(int relRow, int relCol) {
        int absRow = baseRow + relRow;
        int absCol = baseCol + relCol;
        view.clearSlot(absRow, absCol);
    }

    /**
     * 创建偏移的子上下文（供子组件渲染使用）。
     *
     * @param dRow      行偏移
     * @param dCol      列偏移
     * @param child     子组件
     * @return 偏移后的新上下文
     */
    public RenderContext translate(int dRow, int dCol, InventoryComponent child) {
        return new RenderContext(view, child, baseRow + dRow, baseCol + dCol);
    }

    /** 当前基准行（绝对坐标） */
    public int baseRow() {
        return baseRow;
    }

    /** 当前基准列（绝对坐标） */
    public int baseCol() {
        return baseCol;
    }
}
