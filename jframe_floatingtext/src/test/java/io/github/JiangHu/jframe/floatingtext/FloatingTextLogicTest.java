package io.github.JiangHu.jframe.floatingtext;

import cn.nukkit.GameVersion;
import cn.nukkit.entity.Entity;
import cn.nukkit.level.Position;
import cn.nukkit.network.protocol.AddEntityPacket;
import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.network.protocol.MoveEntityAbsolutePacket;
import cn.nukkit.network.protocol.RemoveEntityPacket;
import cn.nukkit.network.protocol.SetEntityDataPacket;
import io.github.JiangHu.jframe.floatingtext.animation.Animations;
import io.github.JiangHu.jframe.floatingtext.render.FloatingTextRenderer;
import io.github.JiangHu.jframe.floatingtext.render.FloatingTextRendererStats;
import io.github.JiangHu.jframe.floatingtext.render.TextVisual;
import io.github.JiangHu.jframe.floatingtext.render.VirtualEntityRenderer;
import io.github.JiangHu.jframe.floatingtext.viewers.TextViewer;
import io.github.JiangHu.jframe.floatingtext.viewers.ViewerDirectory;
import io.github.JiangHu.jframe.floatingtext.viewers.Viewers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 浮空字模块单测 — 生命周期/动画/组/上限/观众协调/中途操作/发包渲染器（12 组场景）。
 *
 * <p>全部用 FakeRenderer 与记录型 TextViewer（不依赖真实 Player/Server）；
 * VirtualEntityRenderer 的包行为用收集 sink 直测。
 */
class FloatingTextLogicTest {

    // ==================== 夹具 ====================

    /** 记录型观众（收集发送的包；协议版本用固定常量——包内容断言与协议无关） */
    static final class RecordingViewer {
        final UUID uuid;
        final List<DataPacket> packets = new ArrayList<>();
        private final TextViewer viewer;
        String levelName;

        RecordingViewer(UUID uuid, String levelName) {
            this.uuid = uuid;
            this.levelName = levelName;
            this.viewer = new TextViewer(uuid, levelName, GameVersion.V1_21_130, packets::add);
        }

        TextViewer tv() {
            return viewer;
        }

        /** 换世界后的同 UUID 观众（新会话表示） */
        RecordingViewer movedTo(String newLevel) {
            return new RecordingViewer(uuid, newLevel);
        }
    }

    /** 假渲染器（记录操作序列 + 仿真账本语义） */
    static final class FakeRenderer implements FloatingTextRenderer {
        final List<String> ops = new ArrayList<>();
        final Map<Long, Set<UUID>> ledgers = new HashMap<>();
        final AtomicInteger nextHandle = new AtomicInteger(1);
        final Map<Long, double[]> positions = new HashMap<>();

        @Override
        public long create(TextVisual visual) {
            long handle = nextHandle.getAndIncrement();
            ledgers.put(handle, new HashSet<>());
            for (TextViewer viewer : visual.viewers()) {
                ledgers.get(handle).add(viewer.uuid());
            }
            positions.put(handle, new double[]{visual.x(), visual.y(), visual.z()});
            ops.add("create:" + handle + ":" + visual.text() + ":viewers=" + visual.viewers().size());
            return handle;
        }

        @Override
        public void move(long handle, double x, double y, double z) {
            positions.put(handle, new double[]{x, y, z});
            ops.add("move:" + handle);
        }

        @Override
        public void setText(long handle, String newText) {
            ops.add("setText:" + handle + ":" + newText);
        }

        @Override
        public void showTo(long handle, TextViewer viewer) {
            if (ledgers.containsKey(handle) && ledgers.get(handle).add(viewer.uuid())) {
                ops.add("showTo:" + handle + ":" + shortId(viewer.uuid()));
            }
        }

        @Override
        public void hideFrom(long handle, TextViewer viewer) {
            if (ledgers.containsKey(handle) && ledgers.get(handle).remove(viewer.uuid())) {
                ops.add("hideFrom:" + handle + ":" + shortId(viewer.uuid()));
            }
        }

        @Override
        public void forget(UUID viewerId) {
            for (Set<UUID> ledger : ledgers.values()) {
                ledger.remove(viewerId);
            }
            ops.add("forget:" + shortId(viewerId));
        }

        @Override
        public void despawn(long handle) {
            ledgers.remove(handle);
            ops.add("despawn:" + handle);
        }

        @Override
        public FloatingTextRendererStats stats() {
            return new FloatingTextRendererStats(ledgers.size(), 0, 0, 0, 0);
        }

        long count(String prefix) {
            return ops.stream().filter(op -> op.startsWith(prefix)).count();
        }

        void clearOps() {
            ops.clear();
        }
    }

    /** 假在线目录（可变观众集） */
    static final class FakeDirectory implements ViewerDirectory {
        final List<TextViewer> viewers = new ArrayList<>();

        @Override
        public Collection<TextViewer> online() {
            return List.copyOf(viewers);
        }
    }

    private final FakeRenderer renderer = new FakeRenderer();
    private final FakeDirectory directory = new FakeDirectory();

    private FloatingTextManager manager() {
        return new FloatingTextManager(renderer, directory);
    }

    private static String shortId(UUID uuid) {
        return uuid.toString().substring(0, 8);
    }

    private static Position pos(double x, double y, double z) {
        return new Position(x, y, z);
    }

    // ==================== 场景 ====================

    @Test
    @DisplayName("1. 瞬态生命周期：show → tick → 到期自动 despawn 且注册表清空")
    void transientLifecycle() {
        FloatingTextManager manager = manager();
        FloatingText text = manager.show(FloatingText.builder()
                .position(pos(1, 2, 3)).text("§c-25")
                .animation(Animations.none()).lifetimeTicks(5).build());
        assertNotNull(text);
        assertTrue(text.valid());

        for (int i = 0; i < 4; i++) {
            manager.tick();
        }
        assertTrue(text.valid(), "寿命未到仍存活");
        manager.tick();   // 第 5 拍
        assertFalse(text.valid(), "到期回收");
        assertEquals(1, renderer.count("despawn:"));
        assertEquals(0, manager.stats().activeTransient());
    }

    @Test
    @DisplayName("2. 动画与节流：rise 每 interval 提交位移；none + 静止零 move")
    void animationAndThrottle() {
        FloatingTextManager manager = manager();

        // rise + interval 2 + 寿命 40 → move 次数 ≤ 20 且 > 0
        manager.show(FloatingText.builder()
                .position(pos(0, 0, 0)).text("x")
                .animation(Animations.rise(b -> b.height(1.0).durationTicks(40)))
                .lifetimeTicks(40).build());
        for (int i = 0; i < 40; i++) {
            manager.tick();
        }
        long riseMoves = renderer.count("move:");
        assertTrue(riseMoves > 0 && riseMoves <= 20, "rise moves 节流到 ≤20，实际 " + riseMoves);

        // none + 静止 → 全程零 move
        renderer.clearOps();
        manager.show(FloatingText.builder()
                .position(pos(0, 0, 0)).text("y")
                .animation(Animations.none())
                .lifetimeTicks(20).build());
        for (int i = 0; i < 20; i++) {
            manager.tick();
        }
        assertEquals(0, renderer.count("move:"), "none + 静止零 move 调用");
    }

    @Test
    @DisplayName("3. 常驻组：\\n 单实体、重名替换、setTexts、removeByTag")
    void groupLifecycle() {
        FloatingTextManager manager = manager();
        FloatingTextGroup g1 = manager.group("lobby", pos(1, 5, 1))
                .lines("§bA", "§7B").build();
        assertTrue(g1.text().currentText().contains("\n"), "多行 join \\n 单实体");
        assertTrue(g1.text().persistent());

        // 重名替换：旧组 despawn、新组接管
        FloatingTextGroup g2 = manager.group("lobby", pos(1, 5, 1))
                .lines("§bC").build();
        assertFalse(g1.text().valid(), "重名拆旧");
        assertTrue(g2.text().valid());
        assertEquals(1, manager.stats().groups());

        // setTexts 幂等重渲
        g2.setTexts("§bC", "§7D");
        assertEquals("§bC\n§7D", g2.text().currentText());

        // removeByTag 命中组标签
        manager.removeByTag("group:lobby");
        assertFalse(g2.text().valid());
        assertEquals(0, manager.stats().groups());
    }

    @Test
    @DisplayName("4. 上限保护：DROP_NEWEST 拒新 / REPLACE_OLDEST 拆旧")
    void overflowProtection() {
        FloatingTextManager manager = manager();
        manager.setMaxActiveTransient(2);

        manager.show(FloatingText.builder().position(pos(0, 0, 0)).text("1").lifetimeTicks(100).build());
        manager.show(FloatingText.builder().position(pos(0, 0, 0)).text("2").lifetimeTicks(100).build());

        // DROP_NEWEST（默认）：第 3 条被拒
        assertNull(manager.show(FloatingText.builder().position(pos(0, 0, 0)).text("3").lifetimeTicks(100).build()));
        assertEquals(2, manager.stats().activeTransient());

        // REPLACE_OLDEST：拆最旧放行
        manager.setOverflowPolicy(FloatingTextManager.OverflowPolicy.REPLACE_OLDEST);
        FloatingText third = manager.show(FloatingText.builder()
                .position(pos(0, 0, 0)).text("3").lifetimeTicks(100).build());
        assertNotNull(third);
        assertEquals(2, manager.stats().activeTransient());
        assertEquals(1, renderer.count("despawn:"), "仅最旧瞬态被回收");
    }

    @Test
    @DisplayName("5. 渲染序列：create → move/setText（节流）→ despawn 时序")
    void rendererOperationSequence() {
        FloatingTextManager manager = manager();
        FloatingText text = manager.show(FloatingText.builder()
                .position(pos(0, 0, 0)).text("a")
                .animation(Animations.rise(b -> b.durationTicks(4)))
                .lifetimeTicks(6).build());
        long handle = text.handle();

        text.setText("b");
        manager.tick();
        manager.tick();
        text.remove();

        List<String> ops = renderer.ops;
        int create = ops.indexOf("create:" + handle + ":a:viewers=0");
        int setText = ops.indexOf("setText:" + handle + ":b");
        int despawn = ops.indexOf("despawn:" + handle);
        assertTrue(create >= 0, "有 create");
        assertTrue(setText > create, "setText 在 create 后");
        assertTrue(despawn > setText, "despawn 最后");
    }

    @Test
    @DisplayName("6. 发包渲染器：引擎编码生成包、改字/移动/定向摘除、句柄单调")
    void virtualEntityRendererPackets() {
        VirtualEntityRenderer virtual = new VirtualEntityRenderer();
        RecordingViewer a = new RecordingViewer(UUID.randomUUID(), "world");
        RecordingViewer b = new RecordingViewer(UUID.randomUUID(), "world");

        long h1 = virtual.create(new TextVisual("§c-25", 1, 2, 3, "world", List.of(a.tv(), b.tv())));

        // 生成包（引擎按观众协议编码）：首观众 [Add, SetData]；后续观众 [Remove(幂等清理), Add, SetData]
        assertEquals(2, a.packets.size());
        assertEquals(3, b.packets.size());
        assertInstanceOf(RemoveEntityPacket.class, b.packets.get(0), "重挂语义：引擎先清旧再挂新");
        assertInstanceOf(AddEntityPacket.class, a.packets.get(0));
        assertInstanceOf(AddEntityPacket.class, b.packets.get(1));
        AddEntityPacket add = (AddEntityPacket) a.packets.get(0);
        long eid = add.entityRuntimeId;
        assertTrue(eid > 0, "引擎从 Entity.entityCount 分配真实 id 空间");
        assertEquals("§c-25", add.metadata.getString(Entity.DATA_NAMETAG));
        assertEquals("minecraft:armor_stand", add.id);
        SetEntityDataPacket restate = (SetEntityDataPacket) a.packets.get(1);
        assertEquals(eid, restate.eid, "Add 后 SetData 重申名牌（ItemText 保险手法）");
        assertEquals("§c-25", restate.metadata.getString(Entity.DATA_NAMETAG));

        // 改字：SetEntityDataPacket 仅名牌
        a.packets.clear();
        virtual.setText(h1, "§c-50");
        assertEquals(1, a.packets.size());
        assertInstanceOf(SetEntityDataPacket.class, a.packets.get(0));
        SetEntityDataPacket change = (SetEntityDataPacket) a.packets.get(0);
        assertEquals(eid, change.eid);
        assertEquals("§c-50", change.metadata.getString(Entity.DATA_NAMETAG));

        // 移动：MoveEntityAbsolutePacket（y 对齐引擎 Add 的 -0.75 基座偏移）
        a.packets.clear();
        virtual.move(h1, 1, 3, 3);
        assertInstanceOf(MoveEntityAbsolutePacket.class, a.packets.get(0));
        MoveEntityAbsolutePacket move = (MoveEntityAbsolutePacket) a.packets.get(0);
        assertEquals(eid, move.eid);
        assertEquals(3 - 0.75, move.y, 1e-6, "move 的 y 与引擎 Add 基座偏移一致");

        // 补发/摘除：只影响目标观众，幂等
        RecordingViewer c = new RecordingViewer(UUID.randomUUID(), "world");
        virtual.showTo(h1, c.tv());
        assertEquals(3, c.packets.size(), "showTo 生成包（重挂语义 3 包）");
        virtual.showTo(h1, c.tv());   // 幂等
        assertEquals(3, c.packets.size(), "showTo 幂等不重复发包");
        a.packets.clear();
        virtual.hideFrom(h1, a.tv());
        virtual.hideFrom(h1, a.tv()); // 幂等
        assertEquals(1, a.packets.size(), "hideFrom 幂等");
        assertInstanceOf(RemoveEntityPacket.class, a.packets.get(0));
        assertEquals(eid, ((RemoveEntityPacket) a.packets.get(0)).eid);

        // 移除：账本观众收 Remove；forget 不发包
        b.packets.clear();
        virtual.despawn(h1);
        assertEquals(1, b.packets.size());
        assertInstanceOf(RemoveEntityPacket.class, b.packets.get(0));
        assertEquals(eid, ((RemoveEntityPacket) b.packets.get(0)).eid);

        // 句柄（注册表内部键）单调
        long h2 = virtual.create(new TextVisual("y", 0, 0, 0, null, List.of()));
        assertTrue(h2 > h1, "句柄单调不复用");
    }

    @Test
    @DisplayName("7. 观众策略：Viewers.of 定向 / all 全体（初始发包）")
    void viewersPolicies() {
        RecordingViewer a = new RecordingViewer(UUID.randomUUID(), null);
        RecordingViewer b = new RecordingViewer(UUID.randomUUID(), null);
        directory.viewers.add(a.tv());
        directory.viewers.add(b.tv());

        FloatingTextManager manager = manager();
        manager.show(FloatingText.builder()
                .position(pos(0, 0, 0)).text("t1").lifetimeTicks(10)
                .viewers(Viewers.of(a.uuid)).build());
        assertTrue(renderer.ops.get(0).endsWith("viewers=1"), "of 定向只发给 a");

        renderer.clearOps();
        manager.show(FloatingText.builder()
                .position(pos(0, 0, 0)).text("t2").lifetimeTicks(10).build());
        assertTrue(renderer.ops.get(0).endsWith("viewers=2"), "all 发给全部");
    }

    @Test
    @DisplayName("8. 观众协调：新观众补常驻、幂等、quit 清账后再补发、level 漂移摘除")
    void viewerResyncCoordination() {
        RecordingViewer a = new RecordingViewer(UUID.randomUUID(), "lobby");
        directory.viewers.add(a.tv());

        FloatingTextManager manager = manager();
        manager.show(FloatingText.builder()
                .position(pos(0, 1, 0)).text("P").levelName("lobby").build());       // 常驻
        manager.show(FloatingText.builder()
                .position(pos(0, 1, 0)).text("T").lifetimeTicks(100).build());        // 瞬态
        assertTrue(renderer.ops.get(0).endsWith("viewers=1"));
        renderer.clearOps();

        // 进服的新观众 b：对账只补常驻（瞬态不补）
        RecordingViewer b = new RecordingViewer(UUID.randomUUID(), "lobby");
        manager.resync(b.tv());
        assertEquals(1, renderer.count("showTo:"), "只补常驻");
        assertEquals(0, renderer.count("hideFrom:"));

        // 幂等：重复 resync 不再发
        renderer.clearOps();
        manager.resync(b.tv());
        assertEquals(0, renderer.count("showTo:"), "账本幂等");

        // level 漂移：b 换世界 → 对账摘除
        renderer.clearOps();
        manager.resync(b.movedTo("arena").tv());
        assertEquals(1, renderer.count("hideFrom:"), "level 不匹配摘除");

        // b 回到 lobby → 对账补发（摘除后账本已空）
        renderer.clearOps();
        manager.resync(b.movedTo("lobby").tv());
        assertEquals(1, renderer.count("showTo:"), "回界补发");

        // 离线：清账本（不发包）
        renderer.clearOps();
        manager.onViewerQuit(b.uuid);
        assertEquals(1, renderer.count("forget:"), "quit 清账");

        // 重连（同 UUID 新会话）：forget 已清账 → 补发成立
        renderer.clearOps();
        manager.resync(new RecordingViewer(b.uuid, "lobby").tv());
        assertEquals(1, renderer.count("showTo:"), "重连补发（quit 先清账语义）");
    }

    @Test
    @DisplayName("9. 中途操作：setText / move / remove 即时生效")
    void midLifeOperations() {
        FloatingTextManager manager = manager();
        FloatingText text = manager.show(FloatingText.builder()
                .position(pos(0, 0, 0)).text("a").lifetimeTicks(100).build());

        text.setText("b");
        manager.tick();
        assertEquals(1, renderer.count("setText:"));
        assertEquals("b", text.currentText());

        text.move(pos(9, 9, 9));
        manager.tick();
        assertTrue(renderer.count("move:") >= 1, "move 提交");

        text.remove();
        manager.tick();
        assertFalse(text.valid());
        assertEquals(1, renderer.count("despawn:"));
    }

    @Test
    @DisplayName("10. 位置源：动态源按节流周期提交新位置")
    void positionSourceUpdates() {
        FloatingTextManager manager = manager();
        AtomicInteger step = new AtomicInteger(0);
        Position[] positions = {pos(0, 0, 0), pos(1, 0, 0), pos(2, 0, 0)};
        FloatingText text = manager.show(FloatingText.builder()
                .position(pos(0, 0, 0)).text("f").lifetimeTicks(100)
                .positionSource(() -> positions[Math.min(step.get(), positions.length - 1)])
                .build());

        step.set(1);
        manager.tick();   // age=1：interval 2 未到 → 不提交
        assertEquals(0, renderer.count("move:"));
        manager.tick();   // age=2：interval 到 → 提交
        assertEquals(1, renderer.count("move:"));
        step.set(2);
        manager.tick();   // age=3：未到
        manager.tick();   // age=4：到，位置已变 → 提交
        assertEquals(2, renderer.count("move:"));
        assertNotNull(text);
    }

    @Test
    @DisplayName("11. 清场：removeAll 后全部 despawn、无残留")
    void removeAllClears() {
        directory.viewers.add(new RecordingViewer(UUID.randomUUID(), null).tv());
        FloatingTextManager manager = manager();
        manager.show(FloatingText.builder().position(pos(0, 0, 0)).text("1").lifetimeTicks(99).build());
        manager.show(FloatingText.builder().position(pos(0, 0, 0)).text("2").build());
        manager.group("g", pos(0, 0, 0)).lines("a").build();

        manager.removeAll();
        assertEquals(0, manager.stats().activeTransient());
        assertEquals(0, manager.stats().activePersistent());
        assertEquals(0, manager.stats().groups());
        assertEquals(3, renderer.count("despawn:"));
    }

    @Test
    @DisplayName("12. 周期对账：观众 level 变化后 reconcile 自愈（补发/摘除双向）")
    void reconcileRepairs() {
        RecordingViewer a = new RecordingViewer(UUID.randomUUID(), "lobby");
        directory.viewers.add(a.tv());

        FloatingTextManager manager = manager();
        manager.setReconcileIntervalTicks(3);
        manager.show(FloatingText.builder()
                .position(pos(0, 1, 0)).text("P").levelName("lobby").build());
        renderer.clearOps();

        // 观众换世界（目录反映新状态）→ 100 tick 内 reconcile 自动摘除
        RecordingViewer moved = a.movedTo("arena");
        directory.viewers.clear();
        directory.viewers.add(moved.tv());
        for (int i = 0; i < 6; i++) {
            manager.tick();
        }
        assertTrue(renderer.count("hideFrom:") >= 1, "周期对账自愈摘除");

        // 观众回来 → 对账补发
        renderer.clearOps();
        RecordingViewer back = moved.movedTo("lobby");
        directory.viewers.clear();
        directory.viewers.add(back.tv());
        for (int i = 0; i < 6; i++) {
            manager.tick();
        }
        assertTrue(renderer.count("showTo:") >= 1, "周期对账自愈补发");
    }
}
