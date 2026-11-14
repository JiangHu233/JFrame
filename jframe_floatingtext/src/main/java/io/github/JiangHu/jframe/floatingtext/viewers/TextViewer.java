package io.github.JiangHu.jframe.floatingtext.viewers;

import cn.nukkit.GameVersion;
import cn.nukkit.Player;
import cn.nukkit.network.protocol.DataPacket;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 观众的模块内表示 — UUID + 所在 level 名 + 协议版本 + 发包通道。
 *
 * <h3>设计动机（与 {@link Player} 解耦）</h3>
 * <p>渲染层与管理器的全部观众逻辑（账本、对账、定向发包）都作用于本类型，
 * 仅在边界处从 {@link Player} 转换（{@link #of(Player)}）——单元测试无需
 * 构造真实 Player，用记录型 sink 即可断言包行为。
 *
 * <p>持 UUID 而非 Player 引用（避免失效对象）；levelName 供同 level 过滤
 * （null = 不限制）；<b>gameVersion 供引擎 {@code FloatingTextParticle#mvEncode}
 * 按观众客户端协议版本编码</b>（MOT 的 AddEntityPacket 携带 protocol/gameVersion
 * 字段、按协议分版编码——错版包客户端不渲染，本模块的生成包必须逐观众按其协议编码）。
 *
 * @param uuid        玩家唯一 id
 * @param levelName   玩家当前所在 level 名（null 视为任意）
 * @param gameVersion 玩家客户端协议版本（null 由渲染侧兜底最新版）
 * @param sender      发包通道（通常是 {@code player::dataPacket}）
 */
public record TextViewer(UUID uuid, String levelName, GameVersion gameVersion, Consumer<DataPacket> sender) {

    /**
     * 从真实玩家转换。
     *
     * @param player 玩家
     * @return 观众表示
     */
    public static TextViewer of(Player player) {
        return new TextViewer(
                player.getUniqueId(),
                player.getLevel() == null ? null : player.getLevel().getName(),
                player.getGameVersion(),
                packet -> player.dataPacket(packet));
    }

    /**
     * 向该玩家发送一个包。
     *
     * @param packet 数据包
     */
    public void send(DataPacket packet) {
        sender.accept(packet);
    }

    /**
     * 批量发送（引擎 {@code mvEncode} 的多包结果逐个投递）。
     *
     * @param packets 数据包数组（null 安全）
     */
    public void sendAll(DataPacket[] packets) {
        if (packets == null) {
            return;
        }
        for (DataPacket packet : packets) {
            sender.accept(packet);
        }
    }
}
