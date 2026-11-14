package io.github.JiangHu.jframe.floatingtext.render;

import cn.nukkit.GameVersion;
import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.data.EntityMetadata;
import cn.nukkit.level.Level;
import cn.nukkit.network.protocol.MoveEntityAbsolutePacket;
import cn.nukkit.network.protocol.RemoveEntityPacket;
import cn.nukkit.network.protocol.SetEntityDataPacket;
import io.github.JiangHu.jframe.floatingtext.viewers.TextViewer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 引擎粒子发包渲染器（默认实现）— <b>零服务器实体</b>：客户端只认数据包，
 * 不关心服务端是否存在真实 Entity 对象。
 *
 * <h3>实现要点（对齐同服生产验证的 NsGameBase ItemText 手法）</h3>
 * <ul>
 *   <li>每个视觉持一个 {@link EngineTextParticle}（继承 MOT 原生
 *       {@code FloatingTextParticle}）：<b>生成包由引擎按观众客户端协议版本
 *       编码</b>（mvEncode(protocol)）——MOT 的 AddEntityPacket 携带
 *       protocol/gameVersion 字段且按协议分版编码，手搓包错版时客户端不渲染；</li>
 *   <li>实体 id 由引擎从 {@code Entity.entityCount} 分配（真实体同源 id 空间，
 *       不再用巨型虚拟 id 段）；首次编码分配、后续编码自带 [Remove, Add]
 *       重挂语义（幂等清理旧客户端状态，对未见 eid 的 Remove 客户端静默忽略）；</li>
 *   <li>改字：{@link SetEntityDataPacket}（引擎 Add 后同样重申过名牌，此路仅名牌键）；</li>
 *   <li>移动：{@link MoveEntityAbsolutePacket}（y 对齐引擎 Add 的 -0.75 基座偏移；
 *       teleport=false 客户端平滑插值）；</li>
 *   <li>移除：{@link RemoveEntityPacket}（无协议分版字段，手搓安全）；</li>
 *   <li>句柄（{@code create} 返回值）为注册表内部键，与客户端 eid 解耦
 *       （eid 在首观众编码时惰性落定）。</li>
 * </ul>
 *
 * <h3>观众账本</h3>
 * 每视觉持「已发送观众」UUID 集合：show 只发给未记账者、Remove 只发给已记账者——
 * 对账幂等，不幽灵、不遗漏（重连/重生/换世界协调见 ViewerResyncListener）。
 * 账本只存 UUID（不持 Player 引用）；广播经「UUID → 最近 sink」缓存
 * （{@code viewerCache}，任何持观众实例的路径都会刷新，forget 时摘除）。
 *
 * <h3>线程模型</h3>
 * 全部方法仅主线程调用（Nukkit 主线程约定）。
 */
public class VirtualEntityRenderer implements FloatingTextRenderer {

    /** 引擎 Add 生成包的基座下移量（FloatingTextParticle#getAddPacket：y - 0.75f） */
    private static final float ENGINE_BASE_Y_OFFSET = 0.75f;

    /** 视觉记录（handle 为注册表内部键） */
    private static final class Visual {
        private final EngineTextParticle particle;
        private String text;
        private double x;
        private double y;
        private double z;
        private final Set<UUID> ledger = new HashSet<>();

        private Visual(EngineTextParticle particle, String text, double x, double y, double z) {
            this.particle = particle;
            this.text = text;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** 活跃视觉（句柄 → 视觉） */
    private final Map<Long, Visual> visuals = new HashMap<>();

    /** 观众 sink 缓存（UUID → 最近发包通道；forget 清理，避免持有失效引用误发） */
    private final Map<UUID, TextViewer> viewerCache = new HashMap<>();

    /** 下一个注册表句柄（内部键，单调不复用） */
    private long nextHandle = 1;

    /** 包计数（发送次数，含逐观众重复） */
    private long addCount;
    private long setDataCount;
    private long moveCount;
    private long removeCount;

    @Override
    public long create(TextVisual visual) {
        EngineTextParticle particle = new EngineTextParticle(
                resolveLevel(visual.levelName()), visual.x(), visual.y(), visual.z(), visual.text());
        Visual entry = new Visual(particle, visual.text(), visual.x(), visual.y(), visual.z());
        long handle = nextHandle++;
        visuals.put(handle, entry);
        for (TextViewer viewer : visual.viewers()) {
            cacheViewer(viewer);
            sendShow(entry, viewer);
        }
        return handle;
    }

    @Override
    public void move(long handle, double x, double y, double z) {
        Visual entry = visuals.get(handle);
        if (entry == null) {
            return;
        }
        entry.x = x;
        entry.y = y;
        entry.z = z;
        long eid = entry.particle.getEntityId();
        if (eid < 0) {
            return; // 从未对任何观众编码过（账本必空）
        }
        for (UUID viewerId : entry.ledger) {
            TextViewer viewer = viewerCache.get(viewerId);
            if (viewer != null) {
                MoveEntityAbsolutePacket packet = new MoveEntityAbsolutePacket();
                packet.eid = eid;
                packet.x = entry.x;
                packet.y = entry.y - ENGINE_BASE_Y_OFFSET;
                packet.z = entry.z;
                packet.yaw = 0;
                packet.headYaw = 0;
                packet.pitch = 0;
                packet.onGround = false;
                packet.teleport = false;
                viewer.send(packet);
                moveCount++;
            }
        }
    }

    @Override
    public void setText(long handle, String newText) {
        Visual entry = visuals.get(handle);
        if (entry == null || newText == null || newText.equals(entry.text)) {
            return;
        }
        entry.text = newText;
        entry.particle.setText(newText);
        long eid = entry.particle.getEntityId();
        if (eid < 0) {
            return;
        }
        for (UUID viewerId : entry.ledger) {
            TextViewer viewer = viewerCache.get(viewerId);
            if (viewer != null) {
                SetEntityDataPacket packet = new SetEntityDataPacket();
                packet.eid = eid;
                packet.metadata = new EntityMetadata().putString(Entity.DATA_NAMETAG, newText);
                viewer.send(packet);
                setDataCount++;
            }
        }
    }

    @Override
    public void showTo(long handle, TextViewer viewer) {
        Visual entry = visuals.get(handle);
        if (entry == null || viewer == null) {
            return;
        }
        cacheViewer(viewer);
        if (!entry.ledger.contains(viewer.uuid())) {
            sendShow(entry, viewer);
        }
    }

    @Override
    public void hideFrom(long handle, TextViewer viewer) {
        Visual entry = visuals.get(handle);
        if (entry == null || viewer == null) {
            return;
        }
        cacheViewer(viewer);
        if (entry.ledger.remove(viewer.uuid())) {
            sendRemove(entry, viewer);
        }
    }

    @Override
    public void forget(UUID viewerId) {
        viewerCache.remove(viewerId);           // 摘 sink 缓存（连接已断，不再误发）
        for (Visual entry : visuals.values()) {
            entry.ledger.remove(viewerId);      // 清账本（不发包）
        }
    }

    @Override
    public void despawn(long handle) {
        Visual entry = visuals.remove(handle);
        if (entry == null) {
            return;
        }
        for (UUID viewerId : entry.ledger) {
            TextViewer viewer = viewerCache.get(viewerId);
            if (viewer != null) {
                sendRemove(entry, viewer);
            }
        }
        entry.ledger.clear();
    }

    @Override
    public FloatingTextRendererStats stats() {
        return new FloatingTextRendererStats(visuals.size(),
                addCount, setDataCount, moveCount, removeCount);
    }

    // ==================== 内部 ====================

    /**
     * 生成路径：引擎按观众协议编码（首观众 [Add, SetData]，后续观众
     * [Remove(幂等清理), Add, SetData]）并记账。
     */
    private void sendShow(Visual entry, TextViewer viewer) {
        entry.particle.setInvisible(false);
        viewer.sendAll(entry.particle.mvEncode(protocolOf(viewer)));
        entry.ledger.add(viewer.uuid());
        addCount++;
    }

    /** 移除路径（RemoveEntityPacket 无协议分版字段，手搓安全） */
    private void sendRemove(Visual entry, TextViewer viewer) {
        long eid = entry.particle.getEntityId();
        if (eid < 0) {
            return; // 该视觉从未在任何观众客户端存在
        }
        RemoveEntityPacket packet = new RemoveEntityPacket();
        packet.eid = eid;
        viewer.send(packet);
        removeCount++;
    }

    /** 观众协议版本兜底（正常经 TextViewer.of 必非空） */
    private static GameVersion protocolOf(TextViewer viewer) {
        return viewer.gameVersion() != null ? viewer.gameVersion() : GameVersion.getLastVersion();
    }

    /** 按名解析世界（null/未加载/无 Server 环境安全回退 null → 粒子走无 level 构造） */
    private static Level resolveLevel(String levelName) {
        if (levelName == null) {
            return null;
        }
        try {
            Server server = Server.getInstance();
            return server == null ? null : server.getLevelByName(levelName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 刷新观众 sink 缓存。
     */
    private void cacheViewer(TextViewer viewer) {
        viewerCache.put(viewer.uuid(), viewer);
    }

    @Override
    public String toString() {
        return "VirtualEntityRenderer{visuals=" + visuals.size()
                + ", nextHandle=" + nextHandle + "}";
    }
}
