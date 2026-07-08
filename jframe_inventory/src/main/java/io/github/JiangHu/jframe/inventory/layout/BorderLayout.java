package io.github.JiangHu.jframe.inventory.layout;

import io.github.JiangHu.jframe.inventory.component.InventoryComponent;
import io.github.JiangHu.jframe.inventory.component.Panel;

import java.util.HashMap;
import java.util.Map;

/**
 * 边框布局。
 * <p>
 * 将容器分为五个区域：{@link Constraint#NORTH}（顶部）、{@link Constraint#SOUTH}（底部）、
 * {@link Constraint#CENTER}（中间）、{@link Constraint#EAST}（右侧）、{@link Constraint#WEST}（左侧）。
 * <p>
 * NORTH 和 SOUTH 占满容器宽度、高度 1 行；EAST 和 WEST 宽度 1 列、高度为中间区域；
 * CENTER 占据剩余空间。
 *
 * <pre>{@code
 * Panel panel = new Panel(9, 6);
 * panel.setLayout(new BorderLayout());
 * panel.add(header, BorderLayout.NORTH);
 * panel.add(footer, BorderLayout.SOUTH);
 * panel.add(content, BorderLayout.CENTER);
 * }</pre>
 */
public class BorderLayout implements Layout {

    /** 约束位置枚举 */
    public enum Constraint {
        /** 顶部：占满宽度，高度 1 行 */
        NORTH,
        /** 底部：占满宽度，高度 1 行 */
        SOUTH,
        /** 中间：占据剩余空间 */
        CENTER,
        /** 左侧：宽度 1 列 */
        WEST,
        /** 右侧：宽度 1 列 */
        EAST
    }

    /** 子组件 → 约束位置的映射 */
    private final Map<InventoryComponent, Constraint> constraints = new HashMap<>();

    /**
     * 记录子组件的约束位置。
     * <p>
     * 由 {@link Panel#add(InventoryComponent, Object)} 在添加子组件时调用。
     *
     * @param child     子组件
     * @param constraint 约束位置
     */
    public void bind(InventoryComponent child, Constraint constraint) {
        constraints.put(child, constraint);
    }

    @Override
    public void arrange(Panel container) {
        int width = container.getWidth();
        int height = container.getHeight();

        InventoryComponent north = findByConstraint(constraints, Constraint.NORTH);
        InventoryComponent south = findByConstraint(constraints, Constraint.SOUTH);
        InventoryComponent east = findByConstraint(constraints, Constraint.EAST);
        InventoryComponent west = findByConstraint(constraints, Constraint.WEST);
        InventoryComponent center = findByConstraint(constraints, Constraint.CENTER);

        int topHeight = (north != null) ? north.height : 0;
        int bottomHeight = (south != null) ? south.height : 0;
        int leftWidth = (west != null) ? west.width : 0;
        int rightWidth = (east != null) ? east.width : 0;

        if (north != null) {
            north.row = 0;
            north.col = 0;
            north.width = width;
        }
        if (south != null) {
            south.row = height - south.height;
            south.col = 0;
            south.width = width;
        }
        if (west != null) {
            west.row = topHeight;
            west.col = 0;
            west.height = height - topHeight - bottomHeight;
        }
        if (east != null) {
            east.row = topHeight;
            east.col = width - east.width;
            east.height = height - topHeight - bottomHeight;
        }
        if (center != null) {
            center.row = topHeight;
            center.col = leftWidth;
            center.width = width - leftWidth - rightWidth;
            center.height = height - topHeight - bottomHeight;
        }
    }

    private InventoryComponent findByConstraint(Map<InventoryComponent, Constraint> map, Constraint target) {
        for (var entry : map.entrySet()) {
            if (entry.getValue() == target) {
                return entry.getKey();
            }
        }
        return null;
    }
}
