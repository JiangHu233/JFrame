package io.github.JiangHu.jframe.inventory.ui.layout;

import io.github.JiangHu.jframe.inventory.ui.component.Panel;

/**
 * 布局管理器接口。
 * <p>
 * 负责计算容器（{@link Panel}）中子组件的相对位置（row, col）。
 * 渲染前由容器调用 {@link #arrange} 完成布局排列。
 *
 * <h3>内置实现</h3>
 * <ul>
 *   <li>{@link ManualLayout} —— 手动布局，开发者显式指定每个子组件位置</li>
 *   <li>{@link BorderLayout} —— 边框布局（东南西北中五个区域）</li>
 *   <li>{@link GridLayout} —— 网格布局（均分行列）</li>
 * </ul>
 *
 * @see Panel
 */
public interface Layout {

    /**
     * 排列容器中的子组件。
     * <p>
     * 实现应遍历容器的子组件，为每个子组件设置 {@code row} 和 {@code col}。
     *
     * @param container 要布局的容器
     */
    void arrange(Panel container);
}
