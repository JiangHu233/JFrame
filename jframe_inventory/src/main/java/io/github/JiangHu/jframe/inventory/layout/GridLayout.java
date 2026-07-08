package io.github.JiangHu.jframe.inventory.layout;

import io.github.JiangHu.jframe.inventory.component.InventoryComponent;
import io.github.JiangHu.jframe.inventory.component.Panel;

/**
 * 网格布局。
 * <p>
 * 将容器区域均分为 {@code rows × cols} 的网格，子组件按添加顺序依次填入。
 * 每个网格单元的宽度为 {@code containerWidth / cols}，高度为 {@code containerHeight / rows}。
 *
 * <pre>{@code
 * Panel grid = new Panel(8, 2);        // 8 格宽，2 格高
 * grid.setLayout(new GridLayout(2, 4)); // 2 行 4 列
 * grid.add(button1);  // 自动放到 (0,0)
 * grid.add(button2);  // 自动放到 (0,1)
 * grid.add(button3);  // 自动放到 (0,2)
 * // ...
 * }</pre>
 */
public class GridLayout implements Layout {

    private final int rows;
    private final int cols;

    /**
     * 创建网格布局。
     *
     * @param rows 行数
     * @param cols 列数
     */
    public GridLayout(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
    }

    @Override
    public void arrange(Panel container) {
        int cellWidth = container.getWidth() / cols;
        int cellHeight = container.getHeight() / rows;

        int index = 0;
        for (InventoryComponent child : container.children()) {
            int r = index / cols;
            int c = index % cols;
            child.row = r * cellHeight;
            child.col = c * cellWidth;
            child.width = cellWidth;
            child.height = cellHeight;
            index++;
        }
    }
}
