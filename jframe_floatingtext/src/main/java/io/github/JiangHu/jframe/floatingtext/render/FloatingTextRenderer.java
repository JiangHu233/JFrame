package io.github.JiangHu.jframe.floatingtext.render;

import cn.nukkit.level.Position;
import io.github.JiangHu.jframe.floatingtext.viewers.TextViewer;

import java.util.UUID;

/**
 * 渲染器 SPI — 隔离「逻辑浮空字」与「世界侧呈现」。
 *
 * <p>默认实现 {@link VirtualEntityRenderer}（发包假实体，零服务器实体）；
 * 替换实现（如真实体渲染器）只需实现本接口并覆盖 Spring bean
 * {@code floatingTextRenderer}，业务代码零改动。
 *
 * <h3>观众账本契约（实现必须遵守）</h3>
 * <ul>
 *   <li>create 向 visual.viewers 逐个发送生成包并<b>记账</b>；</li>
 *   <li>showTo / hideFrom 幂等（已记账不重发 Add；未记账不发 Remove）；</li>
 *   <li>despawn <b>只向账本内观众</b>发移除包；forget 清账不发包。</li>
 * </ul>
 */
public interface FloatingTextRenderer {

    /**
     * 创建世界侧视觉。
     *
     * @param visual 文本 + 位置 + 观众
     * @return 渲染句柄
     */
    long create(TextVisual visual);

    /**
     * 移动到新位置（绝对坐标）。
     *
     * @param handle 句柄
     * @param x      X
     * @param y      Y
     * @param z      Z
     */
    void move(long handle, double x, double y, double z);

    /**
     * 修改文本。
     *
     * @param handle  句柄
     * @param newText 新文本
     */
    void setText(long handle, String newText);

    /**
     * 向单个观众补发（未记账才发 Add）。
     *
     * @param handle 句柄
     * @param viewer 观众
     */
    void showTo(long handle, TextViewer viewer);

    /**
     * 从单个观众移除（已记账才发 Remove）。
     *
     * @param handle 句柄
     * @param viewer 观众
     */
    void hideFrom(long handle, TextViewer viewer);

    /**
     * 玩家离线：清除其在全部账本中的记录（<b>不发任何包</b>——连接已断）。
     *
     * @param viewerId 玩家 UUID
     */
    void forget(UUID viewerId);

    /**
     * 移除视觉（只向账本观众发移除包并清账）。
     *
     * @param handle 句柄
     */
    void despawn(long handle);

    /**
     * 指标。
     *
     * @return 统计
     */
    FloatingTextRendererStats stats();

    /**
     * 位置重载便捷方法（默认转发到坐标版本）。
     *
     * @param handle   句柄
     * @param position 位置
     */
    default void move(long handle, Position position) {
        move(handle, position.x, position.y, position.z);
    }
}
