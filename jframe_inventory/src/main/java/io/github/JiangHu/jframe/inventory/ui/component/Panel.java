package io.github.JiangHu.jframe.inventory.ui.component;

import io.github.JiangHu.jframe.inventory.ui.layout.BorderLayout;
import io.github.JiangHu.jframe.inventory.ui.layout.GridLayout;
import io.github.JiangHu.jframe.inventory.ui.layout.Layout;
import io.github.JiangHu.jframe.inventory.ui.layout.ManualLayout;
import io.github.JiangHu.jframe.inventory.ui.model.SlotAppearance;
import io.github.JiangHu.jframe.inventory.ui.model.SlotType;
import io.github.JiangHu.jframe.inventory.ui.view.RenderContext;

/**
 * 容器面板（类似 JPanel）。
 * <p>
 * 可包含多个子组件，通过 {@link Layout 布局管理器} 自动排列子组件位置。
 * 是构建复杂界面的核心容器。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 手动布局
 * Panel panel = new Panel(9, 3);
 * panel.add(button1, 0, 0);
 * panel.add(button2, 0, 1);
 *
 * // 边框布局
 * Panel panel = new Panel(9, 6);
 * panel.setLayout(new BorderLayout());
 * panel.add(header, BorderLayout.NORTH);
 * panel.add(content, BorderLayout.CENTER);
 *
 * // 网格布局
 * Panel grid = new Panel(8, 2);
 * grid.setLayout(new GridLayout(2, 4));
 * grid.add(btn1);  // 自动排列
 * }</pre>
 *
 * @see Layout
 * @see BorderLayout
 * @see GridLayout
 */
public class Panel extends InventoryComponent {

    /** 布局管理器，默认手动布局 */
    private Layout layout = new ManualLayout();

    /** 背景外观（可选，填充整个面板区域） */
    private SlotAppearance background;

    /**
     * 创建指定尺寸的面板。
     *
     * @param width  宽度（格子数）
     * @param height 高度（格子数）
     */
    public Panel(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /**
     * 设置布局管理器。
     *
     * @param layout 布局管理器
     * @return 当前面板（链式调用）
     */
    public Panel setLayout(Layout layout) {
        this.layout = layout;
        return this;
    }

    /**
     * 获取当前布局管理器。
     *
     * @return 布局管理器
     */
    public Layout layout() {
        return layout;
    }

    /**
     * 设置背景外观（填充整个面板区域，DISPLAY 类型）。
     *
     * @param background 背景外观
     * @return 当前面板（链式调用）
     */
    public Panel background(SlotAppearance background) {
        this.background = background;
        return this;
    }

    /**
     * 添加子组件（带布局约束）。
     * <p>
     * 当使用 {@link BorderLayout} 时，constraint 应为 {@link BorderLayout.Constraint}。
     *
     * @param child      子组件
     * @param constraint 布局约束
     * @return 当前面板（链式调用）
     */
    public Panel add(InventoryComponent child, Object constraint) {
        if (layout instanceof BorderLayout borderLayout && constraint instanceof BorderLayout.Constraint c) {
            borderLayout.bind(child, c);
        }
        super.add(child);
        return this;
    }

    @Override
    protected void onRender(RenderContext ctx) {
        // 执行布局排列
        layout.arrange(this);
        // 渲染背景（可选）
        if (background != null) {
            ctx.fill(background, SlotType.DISPLAY);
        }
        // 子组件由 InventoryComponent.render 自动渲染
    }
}
