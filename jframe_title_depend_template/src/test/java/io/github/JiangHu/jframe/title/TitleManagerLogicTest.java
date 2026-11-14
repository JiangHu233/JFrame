package io.github.JiangHu.jframe.title;

import io.github.JiangHu.jframe.content_template.TemplateEngine;
import io.github.JiangHu.jframe.title.nukkit.TitleScheduler;
import io.github.JiangHu.jframe.title.nukkit.TitleSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TitleManager} 纯逻辑测试（三参构造注入桩，包级 UUID 方法驱动，隔离 Nukkit）：
 * 双槽位共存、同槽切换 KeepAlive、瞬时到期回收、hide / update / onPlayerQuit / removeTemplate
 */
@DisplayName("TitleManager 管理器逻辑")
class TitleManagerLogicTest {

    /** 公告模板：瞬时、仅 TITLE 槽（title + lines[0] 副标题） */
    private static final String ANNOUNCE_XML = "<template><title>{{main}}</title><line>{{sub}}</line></template>";

    /** 信息栏模板：常驻、仅 ACTIONBAR 槽（lines[0]） */
    private static final String INFO_BAR_XML = "<template><line>{{bar}}</line></template>";

    /** 完整模板：默认双槽（title + lines[0] 副标题 + lines[1] 动作栏） */
    private static final String FULL_XML = "<template>"
            + "<title>{{main}}</title><line>{{sub}}</line><line>{{bar}}</line></template>";

    private TemplateEngine engine;
    private RecordingTitleSender sender;
    private ManualTitleScheduler scheduler;
    private TitleManager manager;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        engine = new TemplateEngine();
        sender = new RecordingTitleSender();
        scheduler = new ManualTitleScheduler();
        manager = new TitleManager(engine, player -> sender, scheduler);
        playerId = UUID.randomUUID();
    }

    /** 经 Builder 注册模板（默认融合 + 定制项） */
    private TitleTemplate register(String name, String xml, UnaryOperator<TitleTemplate.Builder> customizer) {
        TitleTemplate template = customizer.apply(
                TitleTemplate.builder(name, engine.compile(xml))).build();
        return manager.loadTemplate(template);
    }

    private TitleTemplate registerAnnounce() {
        return register("ann", ANNOUNCE_XML, b -> b
                .mode(TitleMode.TRANSIENT)
                .timing(TitleTiming.of(1, 1, 1))
                .subtitleLines(0, 1)
                .actionbarLine(-1));
    }

    private TitleTemplate registerInfoBar() {
        return register("bar", INFO_BAR_XML, b -> b
                .subtitleLines(0, 0)
                .actionbarLine(0));
    }

    private TitleTemplate registerFull() {
        return register("full", FULL_XML, b -> b);
    }

    @Nested
    @DisplayName("模板注册")
    class TemplateRegistry {

        @Test
        @DisplayName("loadTemplate(name, xml) 编译注册并可按名取出")
        void testLoadFromSource() {
            TitleTemplate loaded = manager.loadTemplate("src", FULL_XML);

            assertSame(loaded, manager.getTemplate("src"));
            assertEquals("src", loaded.getName());
        }

        @Test
        @DisplayName("loadTemplate(TitleTemplate) 注册 Builder 定制产物")
        void testLoadPacked() {
            TitleTemplate packed = registerFull();

            assertSame(packed, manager.getTemplate("full"));
        }

        @Test
        @DisplayName("show 未注册模板返回 null")
        void testShowUnknownTemplate() {
            assertNull(manager.show(playerId, "不存在", sender));
        }

        @Test
        @DisplayName("removeTemplate 销毁视图并清理注册表")
        void testRemoveTemplate() {
            registerFull();
            TitleView view = manager.show(playerId, "full", sender);
            assertTrue(view.isShown());

            manager.removeTemplate("full");

            assertTrue(view.isDisposed());
            assertNull(manager.getTemplate("full"));
            assertNull(manager.show(playerId, "full", sender));
        }
    }

    @Nested
    @DisplayName("双槽位模型")
    class DualSlot {

        @Test
        @DisplayName("仅 TITLE 槽模板与仅 ACTIONBAR 槽模板互不干扰、可共存")
        void testDualSlotCoexist() {
            registerAnnounce();
            registerInfoBar();

            TitleView annView = manager.show(playerId, "ann", sender);
            TitleView barView = manager.show(playerId, "bar", sender);

            assertTrue(annView.isShown(), "TITLE 槽视图不应被 ACTIONBAR 槽模板挤掉");
            assertTrue(barView.isShown());
            assertEquals(1, sender.titleCalls.size(), "ann 全量发包一次");
            assertEquals(1, sender.actionBarCalls.size(), "bar 全量发包一次");
        }

        @Test
        @DisplayName("update 双槽分发：TITLE 槽视图与 ACTIONBAR 槽视图各自收到更新")
        void testUpdateDispatchesToBothSlots() {
            registerAnnounce();
            registerInfoBar();
            manager.show(playerId, "ann", sender);
            manager.show(playerId, "bar", sender);
            int titleCallsBefore = sender.titleCalls.size();
            int barCallsBefore = sender.actionBarCalls.size();

            manager.update(playerId, "main", "新公告");

            assertEquals(titleCallsBefore + 1, sender.titleCalls.size(), "ann 的 title 通道应重发");
            assertEquals("新公告", sender.lastTitleCall().title);
            assertEquals(barCallsBefore, sender.actionBarCalls.size(), "bar 模板无 {{main}}，不应发包");

            manager.update(playerId, "bar", "新信息栏");

            assertEquals(barCallsBefore + 1, sender.actionBarCalls.size());
            assertEquals("新信息栏", sender.lastActionBarCall());
            assertEquals(titleCallsBefore + 1, sender.titleCalls.size(), "ann 模板无 {{bar}}，不应发包");
        }

        @Test
        @DisplayName("同一模板占双槽时 update 只写入一次（去重）")
        void testUpdateDeduplicatesDualSlotTemplate() {
            registerFull();
            manager.show(playerId, "full", sender);
            int titleCalls = sender.titleCalls.size();
            int barCalls = sender.actionBarCalls.size();

            manager.update(playerId, "main", "新主标题");

            assertEquals(titleCalls + 1, sender.titleCalls.size());
            assertEquals(barCalls, sender.actionBarCalls.size());
        }
    }

    @Nested
    @DisplayName("同槽切换与 KeepAlive")
    class SlotSwitch {

        @Test
        @DisplayName("同槽切换：旧视图 deactivate（保留状态不销毁），再 show 复用缓存同一实例")
        void testSlotSwitchKeepsAlive() {
            registerAnnounce();
            registerFull();

            TitleView annView = manager.show(playerId, "ann", sender);
            TitleView fullView = manager.show(playerId, "full", sender);

            // full 也占 TITLE 槽 → ann 被 deactivate
            assertFalse(annView.isShown());
            assertFalse(annView.isDisposed(), "KeepAlive：不销毁");
            assertTrue(fullView.isShown());

            // 再 show ann：复用缓存实例 reactivate
            TitleView again = manager.show(playerId, "ann", sender);
            assertSame(annView, again, "应复用缓存的同一视图实例");
            assertTrue(annView.isShown());
            assertFalse(fullView.isShown(), "full 反过来被 deactivate");
        }

        @Test
        @DisplayName("重复 show 同一模板强制清屏重发")
        void testReshowSameTemplateForcesResend() {
            registerFull();
            manager.show(playerId, "full", sender);
            assertEquals(1, sender.titleCalls.size());

            manager.show(playerId, "full", sender);

            assertEquals(2, sender.titleCalls.size(), "重复 show 应 deactivate + reactivate 重发");
        }
    }

    @Nested
    @DisplayName("瞬时模式到期回收")
    class TransientExpiry {

        @Test
        @DisplayName("到期 dispose 后缓存与槽位标记清理，再 show 新建视图")
        void testTransientExpiredEvictsCache() {
            registerAnnounce();

            TitleView first = manager.show(playerId, "ann", sender);
            assertEquals(1, scheduler.delayedTaskCount());

            scheduler.fireDelayed();

            assertTrue(first.isDisposed());
        }

        @Test
        @DisplayName("到期后再 show 同名模板创建全新视图实例")
        void testReshowAfterExpiryCreatesNewView() {
            registerAnnounce();
            TitleView first = manager.show(playerId, "ann", sender);

            scheduler.fireDelayed();

            TitleView second = manager.show(playerId, "ann", sender);
            assertNotSame(first, second, "旧视图已 dispose，应新建");
            assertTrue(second.isShown());
            assertTrue(first.isDisposed());
        }

        @Test
        @DisplayName("仅 ACTIONBAR 槽的瞬时模板到期后不挤占后续 TITLE 槽模板")
        void testTransientActionBarExpiry() {
            register("transientBar", INFO_BAR_XML, b -> b
                    .mode(TitleMode.TRANSIENT)
                    .timing(TitleTiming.of(1, 1, 1))
                    .subtitleLines(0, 0)
                    .actionbarLine(0));
            registerAnnounce();

            manager.show(playerId, "transientBar", sender);
            manager.show(playerId, "ann", sender);

            scheduler.fireDelayed();

            // transientBar 到期回收，ann（TITLE 槽）不受影响
            TitleView annAgain = manager.show(playerId, "ann", sender);
            assertTrue(annAgain.isShown());
        }
    }

    @Nested
    @DisplayName("hide 与恢复")
    class HideAndRestore {

        @Test
        @DisplayName("hide 停用双槽视图，数据更新不再发包，再 show 复用缓存")
        void testHideKeepsAlive() {
            registerFull();
            TitleView view = manager.show(playerId, "full", sender);

            manager.hide(playerId);

            assertFalse(view.isShown());
            assertFalse(view.isDisposed());

            int titleCalls = sender.titleCalls.size();
            int barCalls = sender.actionBarCalls.size();
            manager.update(playerId, "main", "隐藏期间更新");
            assertEquals(titleCalls, sender.titleCalls.size(), "隐藏期间不应发包");
            assertEquals(barCalls, sender.actionBarCalls.size());

            TitleView restored = manager.show(playerId, "full", sender);
            assertSame(view, restored, "再 show 应复用 KeepAlive 缓存");
            assertTrue(view.isShown());
        }
    }

    @Nested
    @DisplayName("引擎层数据传播")
    class EnginePropagation {

        @Test
        @DisplayName("玩家全局层写入经父层传播刷新激活视图")
        void testPlayerDataPropagation() {
            registerFull();
            manager.show(playerId, "full", sender);
            int titleCalls = sender.titleCalls.size();

            engine.setPlayerData(playerId, "main", "玩家层主标题");

            assertEquals(titleCalls + 1, sender.titleCalls.size());
            assertEquals("玩家层主标题", sender.lastTitleCall().title);
        }

        @Test
        @DisplayName("updateGlobal 引擎全局层写入传播刷新激活视图")
        void testGlobalPropagation() {
            registerFull();
            manager.show(playerId, "full", sender);
            int titleCalls = sender.titleCalls.size();

            manager.updateGlobal("main", "全局主标题");

            assertEquals(titleCalls + 1, sender.titleCalls.size());
            assertEquals("全局主标题", sender.lastTitleCall().title);
        }

        @Test
        @DisplayName("updateTemplate 写入引擎模板层（独立层，不进入视图主链）")
        void testTemplatePropagation() {
            registerFull();
            manager.show(playerId, "full", sender);
            int titleCalls = sender.titleCalls.size();

            manager.updateTemplate("full", "main", "模板层主标题");

            // 引擎模板层已写入（对齐 scoreboard 契约：模板全局作为可选独立层保留 API）
            assertEquals("模板层主标题", engine.getTemplateData("full").get("main"));
            // 视图链 local → player → global 不含模板层，不触发刷新
            assertEquals(titleCalls, sender.titleCalls.size());
        }
    }

    @Nested
    @DisplayName("玩家退出清理")
    class PlayerQuit {

        @Test
        @DisplayName("onPlayerQuit 销毁该玩家全部视图，再 show 重建")
        void testQuitDisposesAllViews() {
            registerAnnounce();
            registerInfoBar();
            TitleView annView = manager.show(playerId, "ann", sender);
            TitleView barView = manager.show(playerId, "bar", sender);

            manager.onPlayerQuit(playerId);

            assertTrue(annView.isDisposed());
            assertTrue(barView.isDisposed());

            TitleView rebuilt = manager.show(playerId, "bar", sender);
            assertNotSame(barView, rebuilt, "退出后缓存已清，应新建视图");
            assertTrue(rebuilt.isShown());
        }
    }
}
