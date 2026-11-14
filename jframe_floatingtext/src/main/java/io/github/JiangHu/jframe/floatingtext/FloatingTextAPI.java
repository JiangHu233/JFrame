package io.github.JiangHu.jframe.floatingtext;

import cn.nukkit.level.Position;

/**
 * 浮空字 API 门面 — 纯委托零逻辑（TitleAPI 同款形态）。
 *
 * <h3>核心用法</h3>
 * <pre>{@code
 * FloatingTextAPI ft = JFrameMain.getInstance().getFloatingTextAPI();
 *
 * // 瞬态（伤害数字 / 脚步）：寿命 + 动画，到期自动回收
 * ft.show(FloatingText.builder()
 *         .position(pos).text("§c-25")
 *         .animation(Animations.rise()).lifetimeTicks(30).build());
 *
 * // 常驻组（大厅全息）：\n 单实体多行，重连自动补发
 * ft.group("lobby:rules", pos).lines("§b=== 主城 ===", "§7规则").build();
 *
 * // 批量管理 / 指标
 * ft.removeByTag("match:footstep");
 * ft.stats();
 * }</pre>
 */
public class FloatingTextAPI {

    private final FloatingTextManager manager;

    /**
     * 构造门面。
     *
     * @param manager 管理器
     */
    public FloatingTextAPI(FloatingTextManager manager) {
        this.manager = manager;
    }

    /**
     * 注册一条浮空字（Builder 构造后调用）。
     *
     * @param text 浮空字
     * @return 已注册句柄；瞬态超限且策略为 DROP_NEWEST 时 null
     */
    public FloatingText show(FloatingText text) {
        return manager.show(text);
    }

    /**
     * 创建命名常驻组（重复命名 = 拆旧建新）。
     *
     * @param name     组名
     * @param position 位置
     * @return 组 Builder
     */
    public FloatingTextGroup.Builder group(String name, Position position) {
        return manager.group(name, position);
    }

    /**
     * 移除命名组。
     *
     * @param name 组名
     */
    public void remove(String name) {
        manager.removeGroup(name);
    }

    /**
     * 按标签批量移除。
     *
     * @param tag 标签
     */
    public void removeByTag(String tag) {
        manager.removeByTag(tag);
    }

    /**
     * 清场（移除全部条目与组）。
     */
    public void removeAll() {
        manager.removeAll();
    }

    /**
     * 指标（活跃数 / 组数 / 渲染包操作数）。
     *
     * @return 统计
     */
    public FloatingTextManager.Stats stats() {
        return manager.stats();
    }
}
