package io.github.JiangHu.jframe.inventory.ui.component;

import io.github.JiangHu.jframe.inventory.ui.model.SlotAppearance;
import io.github.JiangHu.jframe.inventory.ui.model.SlotType;
import io.github.JiangHu.jframe.inventory.ui.model.event.ClickEvent;
import io.github.JiangHu.jframe.inventory.ui.view.RenderContext;

import java.util.function.Consumer;

/**
 * 按钮组件。
 * <p>
 * 显示一个物品外观，点击时触发回调。格子类型为 {@link SlotType#BUTTON}，
 * 禁止玩家放入/取出物品。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 方式一：通过回调
 * Button button = new Button(SlotAppearance.builder()
 *     .type(Item.DIAMOND_SWORD)
 *     .name("§b购买武器")
 *     .build());
 * button.onClick(click -> {
 *     click.player().sendMessage("你点击了购买按钮！");
 * });
 *
 * // 方式二：继承重写
 * public class ShopButton extends Button {
 *     public ShopButton() {
 *         super(SlotAppearance.builder().type(Item.EMERALD).name("§a商店").build());
 *     }
 *     @Override
 *     protected void onClick(ClickEvent event) {
 *         // 自定义逻辑
 *     }
 * }
 * }</pre>
 *
 * @see SlotAppearance
 */
public class Button extends InventoryComponent {

    /** 按钮外观 */
    private SlotAppearance appearance;

    /** 点击回调（可选，与重写 onClick 二选一） */
    private Consumer<ClickEvent> clickHandler;

    /**
     * 创建按钮。
     *
     * @param appearance 按钮外观
     */
    public Button(SlotAppearance appearance) {
        this.appearance = appearance;
    }

    /**
     * 设置点击回调。
     *
     * @param handler 回调函数
     * @return 当前按钮（链式调用）
     */
    public Button onClick(Consumer<ClickEvent> handler) {
        this.clickHandler = handler;
        return this;
    }

    /**
     * 更新按钮外观。
     *
     * @param appearance 新外观
     * @return 当前按钮（链式调用）
     */
    public Button setAppearance(SlotAppearance appearance) {
        this.appearance = appearance;
        return this;
    }

    /**
     * 获取当前外观。
     *
     * @return 按钮外观
     */
    public SlotAppearance appearance() {
        return appearance;
    }

    @Override
    protected void onRender(RenderContext ctx) {
        ctx.slot(0, 0, appearance, SlotType.BUTTON);
    }

    @Override
    protected void onClick(ClickEvent event) {
        if (clickHandler != null) {
            clickHandler.accept(event);
        }
    }
}
