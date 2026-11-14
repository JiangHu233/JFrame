package io.github.JiangHu.jframe.floatingtext.render;

import cn.nukkit.GameVersion;
import cn.nukkit.entity.Entity;
import cn.nukkit.level.Level;
import cn.nukkit.level.Location;
import cn.nukkit.level.particle.FloatingTextParticle;
import cn.nukkit.math.Vector3;
import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.network.protocol.SetEntityDataPacket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 引擎浮空字粒子包装 — 继承 MOT 原生 {@link FloatingTextParticle}，让生成包
 * （AddEntityPacket 的 protocol/gameVersion 字段与盔甲架全量元数据基线）由引擎
 * 按观众客户端协议版本编码，杜绝手搓包的协议错版问题。
 *
 * <p>形态对齐同服生产验证的 NsGameBase {@code ItemText}：
 * <ul>
 *   <li>{@link #setText(String)} 直写 {@code text} 字段与 {@code metadata} 的
 *       DATA_NAMETAG（绕开引擎 {@code sendMetadata} 的区块包广播——本模块观众
 *       定向发包，不走 Level 广播）；</li>
 *   <li>{@link #mvEncode(GameVersion)} 在引擎编码结果后追加
 *       {@link SetEntityDataPacket} 重申名牌（对齐 ItemText 的保险手法）；</li>
 *   <li>实体 id 由引擎在首次编码时从 {@code Entity.entityCount} 惰性分配
 *       （真实体同源、与真实实体 id 空间统一，客户端兼容）。</li>
 * </ul>
 */
final class EngineTextParticle extends FloatingTextParticle {

    /**
     * @param level 声源世界（可空 → Vector3 构造，无 level 语义）
     * @param text  初始文本（§ 颜色码直出）
     */
    EngineTextParticle(Level level, double x, double y, double z, String text) {
        super(level != null ? new Location(x, y, z, level) : new Vector3(x, y, z), "");
        setText(text);
    }

    @Override
    public void setText(String text) {
        this.text = text == null ? "" : text;
        this.metadata.putString(Entity.DATA_NAMETAG, this.text);
    }

    @Override
    public DataPacket[] mvEncode(GameVersion protocol) {
        DataPacket[] base = super.mvEncode(protocol);
        List<DataPacket> pks = new ArrayList<>(base.length + 1);
        Collections.addAll(pks, base);
        if (!this.invisible) {
            SetEntityDataPacket pk = new SetEntityDataPacket();
            pk.eid = this.entityId;
            pk.metadata = this.metadata;
            pks.add(pk);
        }
        return pks.toArray(new DataPacket[0]);
    }
}
