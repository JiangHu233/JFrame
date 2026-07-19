package io.github.JiangHu.jframe.inventory.ui.layout;

import io.github.JiangHu.jframe.inventory.ui.component.InventoryComponent;
import io.github.JiangHu.jframe.inventory.ui.component.Panel;

/**
 * 手动布局（默认布局）。
 * <p>
 * 不自动排列子组件，开发者通过 {@code panel.add(child, row, col)} 显式指定每个子组件的位置。
 * 适合精确控制布局的场景。
 *
 * <pre>{@code
 * Panel panel = new Panel(9, 3);
 * panel.setLayout(new ManualLayout());
 * panel.add(button1, 0, 0);   // 第 0 行第 0 列
 * panel.add(button2, 0, 1);   // 第 0 行第 1 列
 * }</pre>
 *
 * @see Panel#add(InventoryComponent, int, int)
 */
public class ManualLayout implements Layout {

    @Override
    public void arrange(Panel container) {
        // 手动布局无需排列，子组件位置已由开发者通过 add(child, row, col) 指定
    }
}
