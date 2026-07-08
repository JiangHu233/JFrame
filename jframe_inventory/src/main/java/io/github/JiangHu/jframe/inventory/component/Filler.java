package io.github.JiangHu.jframe.inventory.component;

import io.github.JiangHu.jframe.inventory.model.SlotAppearance;
import io.github.JiangHu.jframe.inventory.model.SlotType;
import io.github.JiangHu.jframe.inventory.view.RenderContext;

/**
 * 填充组件。
 * <p>
 * 用指定外观填充整个组件区域，格子类型为 {@link SlotType#DISPLAY}（不可交互）。
 * 典型用途：装饰边框、分隔线、背景填充。
 *
 * <pre>{@code
 * // 用灰色玻璃板填充 9×1 的分隔行
 * Filler separator = new Filler(SlotAppearance.builder()
 *     .type(Item.GRAY_STAINED_GLASS_PANE)
 *     .name(" ")
 *     .build(), 9, 1);
 * }</pre>
 *
 * @see SlotAppearance
 */
public class Filler extends InventoryComponent {

    private SlotAppearance appearance;

    /**
     * 创建填充组件。
     *
     * @param appearance 填充外观
     * @param width      宽度（格子数）
     * @param height     高度（格子数）
     */
    public Filler(SlotAppearance appearance, int width, int height) {
        this.appearance = appearance;
        this.width = width;
        this.height = height;
    }

    /**
     * 更新填充外观。
     *
     * @param appearance 新外观
     * @return 当前组件（链式调用）
     */
    public Filler setAppearance(SlotAppearance appearance) {
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
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                ctx.slot(r, c, appearance, SlotType.DISPLAY);
            }
        }
    }
}
