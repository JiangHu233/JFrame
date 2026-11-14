package io.github.JiangHu.jframe.floatingtext.render;

/**
 * 渲染器指标 — 活跃视觉数 + 四类包操作累计（压测与容量校准）。
 *
 * @param activeVisuals    当前活跃视觉数
 * @param addPackets       AddEntityPacket 累计发送数（含补发）
 * @param setDataPackets   SetEntityDataPacket 累计发送数
 * @param movePackets      MoveEntityAbsolutePacket 累计发送数
 * @param removePackets    RemoveEntityPacket 累计发送数
 */
public record FloatingTextRendererStats(int activeVisuals, long addPackets,
                                        long setDataPackets, long movePackets,
                                        long removePackets) {
}
