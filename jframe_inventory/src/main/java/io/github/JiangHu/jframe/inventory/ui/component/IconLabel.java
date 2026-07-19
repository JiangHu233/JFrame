package io.github.JiangHu.jframe.inventory.ui.component;

import io.github.JiangHu.jframe.inventory.ui.model.SlotAppearance;
import io.github.JiangHu.jframe.inventory.ui.model.SlotType;
import io.github.JiangHu.jframe.inventory.ui.view.RenderContext;

/**
 * 图标标签组件。
 * <p>
 * 仅用于显示物品外观，不可交互（格子类型为 {@link SlotType#DISPLAY}）。
 * 典型用途：标题栏装饰、商品预览、信息展示。
 *
 * <pre>{@code
 * IconLabel title = new IconLabel(SlotAppearance.builder()
 *     .type(Item.ORANGE_STAINED_GLASS_PANE)
 *     .name("§6§l武器商店")
 *     .build());
 * }</pre>
 *
 * @see SlotAppearance
 */
public class IconLabel extends InventoryComponent {

    private SlotAppearance appearance;

    /**
     * 创建图标标签。
     *
     * @param appearance 显示外观
     */
    public IconLabel(SlotAppearance appearance) {
        this.appearance = appearance;
    }

    /**
     * 更新外观。
     *
     * @param appearance 新外观
     * @return 当前组件（链式调用）
     */
    public IconLabel setAppearance(SlotAppearance appearance) {
        this.appearance = appearance;
        return this;
    }

    /**
     * 获取当前外观。
     *
     * @return 外观
     */
    public SlotAppearance appearance() {
        return appearance;
    }

    @Override
    protected void onRender(RenderContext ctx) {
        ctx.slot(0, 0, appearance, SlotType.DISPLAY);
    }
}
