package io.github.JiangHu.jframe.title;

import io.github.JiangHu.jframe.content_template.HierarchicalDataContext;
import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.content_template.TemplateEngine;
import io.github.JiangHu.jframe.title.nukkit.TitleScheduler;
import io.github.JiangHu.jframe.title.nukkit.TitleSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TitleView} 纯逻辑测试（RecordingTitleSender + ManualTitleScheduler 隔离 Nukkit）：
 * 激活全量发包、通道级 diff、双模式生命周期（瞬时到期 / 常驻 KeepAlive / 动作栏续期）
 */
@DisplayName("TitleView 视图逻辑")
class TitleViewLogicTest {

    private static final String TEMPLATE_TEXT = "<template>"
            + "<title>{{main}}</title>"
            + "<line>{{sub}}</line>"
            + "<line>{{bar}}</line>"
            + "</template>";

    private TemplateEngine engine;
    private RecordingTitleSender sender;
    private ManualTitleScheduler scheduler;
    private UUID playerId;
    private List<TitleView> expiredViews;

    @BeforeEach
    void setUp() {
        engine = new TemplateEngine();
        sender = new RecordingTitleSender();
        scheduler = new ManualTitleScheduler();
        playerId = UUID.randomUUID();
        expiredViews = new ArrayList<>();
    }

    /** 构建视图：默认映射（subtitle=lines[0]、actionbar=lines[1]），Builder 可追加配置 */
    private TitleView newView(java.util.function.UnaryOperator<TitleTemplate.Builder> customizer) {
        Template raw = engine.compile(TEMPLATE_TEXT);
        TitleTemplate.Builder builder = TitleTemplate.builder("视图模板", raw);
        TitleTemplate titleTemplate = customizer.apply(builder).build();
        HierarchicalDataContext dataContext = HierarchicalDataContext.of(engine.getPlayerData(playerId));
        dataContext.put("main", "主标题");
        dataContext.put("sub", "副标题");
        dataContext.put("bar", "动作栏");
        return new TitleView(playerId, titleTemplate, dataContext, engine,
                sender, scheduler, expiredViews::add);
    }

    private TitleView newDefaultView() {
        return newView(b -> b);
    }

    @Nested
    @DisplayName("激活与全量发包")
    class Activation {

        @Test
        @DisplayName("show 后全量渲染并按默认映射发包（title+subtitle 一次发送携带时序，actionbar 独立）")
        void testShowSendsAllChannels() {
            TitleView view = newDefaultView();

            view.show(null);

            assertTrue(view.isShown());
            assertFalse(view.isDisposed());
            assertEquals(1, sender.titleCalls.size());
            RecordingTitleSender.TitleCall call = sender.lastTitleCall();
            assertEquals("主标题", call.title);
            assertEquals("副标题", call.subtitle);
            assertEquals(TitleTiming.DEFAULT.getFadeIn(), call.fadeIn);
            assertEquals(TitleTiming.DEFAULT.getStay(), call.stay);
            assertEquals(TitleTiming.DEFAULT.getFadeOut(), call.fadeOut);
            assertEquals(List.of("动作栏"), sender.actionBarCalls);
        }

        @Test
        @DisplayName("重复 show 幂等（不重复发包）")
        void testShowIdempotent() {
            TitleView view = newDefaultView();
            view.show(null);
            view.show(null);

            assertEquals(1, sender.titleCalls.size());
        }

        @Test
        @DisplayName("Builder 时序覆盖默认值并体现在发包参数")
        void testCustomTimingSent() {
            TitleView view = newView(b -> b.timing(TitleTiming.of(1, 2, 3)));
            view.show(null);

            RecordingTitleSender.TitleCall call = sender.lastTitleCall();
            assertEquals(1, call.fadeIn);
            assertEquals(2, call.stay);
            assertEquals(3, call.fadeOut);
        }
    }

    @Nested
    @DisplayName("通道级 diff 刷新")
    class ChannelDiffRefresh {

        @Test
        @DisplayName("仅动作栏数据变化→只重发 actionbar，title 通道零发包")
        void testActionBarOnlyRefresh() {
            TitleView view = newDefaultView();
            view.show(null);
            assertEquals(1, sender.titleCalls.size());
            assertEquals(1, sender.actionBarCalls.size());

            view.set("bar", "新动作栏");

            assertEquals(1, sender.titleCalls.size(), "title 通道不应重发");
            assertEquals(2, sender.actionBarCalls.size());
            assertEquals("新动作栏", sender.lastActionBarCall());
        }

        @Test
        @DisplayName("仅主标题数据变化→只重发 title 通道，actionbar 零发包")
        void testTitleOnlyRefresh() {
            TitleView view = newDefaultView();
            view.show(null);

            view.set("main", "新主标题");

            assertEquals(2, sender.titleCalls.size());
            assertEquals("新主标题", sender.lastTitleCall().title);
            assertEquals("副标题", sender.lastTitleCall().subtitle);
            assertEquals(1, sender.actionBarCalls.size(), "actionbar 通道不应重发");
        }

        @Test
        @DisplayName("副标题行变化→title 通道重发（title/subtitle 为一个发送单元）")
        void testSubtitleRefresh() {
            TitleView view = newDefaultView();
            view.show(null);

            view.set("sub", "新副标题");

            assertEquals(2, sender.titleCalls.size());
            assertEquals("新副标题", sender.lastTitleCall().subtitle);
        }

        @Test
        @DisplayName("与模板无关的数据变更→渲染无变化→零发包")
        void testIrrelevantChangeNoPackets() {
            TitleView view = newDefaultView();
            view.show(null);

            view.set("unrelated", "无关值");

            assertEquals(1, sender.titleCalls.size());
            assertEquals(1, sender.actionBarCalls.size());
        }

        @Test
        @DisplayName("deactivate 后数据变更不再发包；reactivate 强制全量重发")
        void testNoRefreshWhileDeactivated() {
            TitleView view = newDefaultView();
            view.show(null);
            view.deactivate(null);

            view.set("bar", "停用期间的变化");
            assertEquals(1, sender.actionBarCalls.size(), "停用期间不应发包");

            view.reactivate(null);
            assertEquals(2, sender.titleCalls.size(), "reactivate 强制重发 title");
            assertEquals(2, sender.actionBarCalls.size(), "reactivate 强制重发 actionbar");
            assertEquals("停用期间的变化", sender.lastActionBarCall());
        }
    }

    @Nested
    @DisplayName("瞬时模式生命周期")
    class TransientMode {

        private TitleView newTransientView(boolean autoClear) {
            return newView(b -> b.mode(TitleMode.TRANSIENT)
                    .timing(TitleTiming.of(5, 10, 5))
                    .autoClear(autoClear));
        }

        @Test
        @DisplayName("show 后按 fadeIn+stay+fadeOut 总时长调度到期任务")
        void testExpireScheduled() {
            TitleView view = newTransientView(true);

            view.show(null);

            assertEquals(1, scheduler.delayedTaskCount());
            assertEquals(20, scheduler.lastDelayedDelay(), "总时长 = 5+10+5");
        }

        @Test
        @DisplayName("到期触发：autoClear 清除标题 → dispose → 回调通知")
        void testExpireClearsAndDisposes() {
            TitleView view = newTransientView(true);
            view.show(null);

            scheduler.fireDelayed();

            assertEquals(1, sender.clearCount, "autoClear 开启且占 TITLE 槽应清除标题");
            assertTrue(view.isDisposed());
            assertFalse(view.isShown());
            assertEquals(1, expiredViews.size());
            assertEquals(view, expiredViews.get(0));
        }

        @Test
        @DisplayName("autoClose=false 到期不清屏但仍 dispose 并回调")
        void testExpireWithoutClear() {
            TitleView view = newTransientView(false);
            view.show(null);

            scheduler.fireDelayed();

            assertEquals(0, sender.clearCount);
            assertTrue(view.isDisposed());
            assertEquals(1, expiredViews.size());
        }

        @Test
        @DisplayName("stay 期间数据更新仍正常发包")
        void testUpdateDuringStay() {
            TitleView view = newTransientView(true);
            view.show(null);

            view.set("bar", "停留期间更新");

            assertEquals(2, sender.actionBarCalls.size());
            assertFalse(view.isDisposed(), "到期前不应销毁");
        }

        @Test
        @DisplayName("dispose 后到期回调不重复触发")
        void testExpireAfterDisposeIsNoop() {
            TitleView view = newTransientView(true);
            view.show(null);
            view.dispose(null);

            view.onExpire(); // 防御：已销毁视图的到期回调

            assertEquals(0, sender.clearCount);
            assertEquals(0, expiredViews.size());
        }
    }

    @Nested
    @DisplayName("常驻模式 KeepAlive 与动作栏续期")
    class PersistentMode {

        @Test
        @DisplayName("无续期配置时不调度周期任务")
        void testNoRenewByDefault() {
            TitleView view = newDefaultView();
            view.show(null);

            assertEquals(0, scheduler.repeatingTaskCount());
            assertEquals(0, scheduler.delayedTaskCount());
        }

        @Test
        @DisplayName("配置续期间隔后按周期重发动作栏文本")
        void testRenewActionBar() {
            TitleView view = newView(b -> b.actionBarRefresh(20));
            view.show(null);

            assertEquals(1, scheduler.repeatingTaskCount());
            assertEquals(20, scheduler.lastRepeatingPeriod());

            scheduler.tickRepeating();
            assertEquals(2, sender.actionBarCalls.size());
            assertEquals("动作栏", sender.lastActionBarCall(), "续期重发上次文本（不重新渲染）");

            scheduler.tickRepeating();
            assertEquals(3, sender.actionBarCalls.size());
        }

        @Test
        @DisplayName("禁用动作栏通道时不调度续期任务")
        void testNoRenewWhenActionbarDisabled() {
            TitleView view = newView(b -> b.actionbarLine(-1).actionBarRefresh(20));
            view.show(null);

            assertEquals(0, scheduler.repeatingTaskCount());
        }

        @Test
        @DisplayName("deactivate：清 TITLE 槽、取消定时任务、保留数据与渲染状态")
        void testDeactivateKeepsAlive() {
            TitleView view = newView(b -> b.actionBarRefresh(20));
            view.show(null);
            assertEquals(1, scheduler.repeatingTaskCount());

            view.deactivate(null);

            assertFalse(view.isShown());
            assertFalse(view.isDisposed(), "KeepAlive：不销毁");
            assertEquals(1, sender.clearCount, "占用 TITLE 槽应清除显示");
            assertEquals(0, scheduler.repeatingTaskCount(), "定时任务应全部取消");
            assertNull(view.getCurrentPlayer());
        }

        @Test
        @DisplayName("仅动作栏模板 deactivate 不误清 TITLE 槽")
        void testActionBarOnlyDeactivateNoClear() {
            TitleView view = newView(b -> b.subtitleLines(0, 0).actionbarLine(0));
            view.show(null);
            assertEquals(1, sender.actionBarCalls.size());

            view.deactivate(null);

            assertEquals(0, sender.clearCount, "不占 TITLE 槽不应清除");
        }

        @Test
        @DisplayName("reactivate 后重新调度续期任务并恢复响应")
        void testReactivateReschedules() {
            TitleView view = newView(b -> b.actionBarRefresh(20));
            view.show(null);
            view.deactivate(null);
            assertEquals(0, scheduler.repeatingTaskCount());

            view.reactivate(null);

            assertTrue(view.isShown());
            assertEquals(1, scheduler.repeatingTaskCount(), "reactivate 应重建续期任务");

            view.set("bar", "恢复后更新");
            // show 全量首发 1 次 + reactivate 强制全量重发 1 次 + set 更新 1 次
            assertEquals(3, sender.actionBarCalls.size());
        }
    }

    @Nested
    @DisplayName("dispose 与状态查询")
    class Disposal {

        @Test
        @DisplayName("dispose 取消全部定时任务且不可逆")
        void testDisposeCancelsTasks() {
            TitleView view = newView(b -> b.mode(TitleMode.TRANSIENT).timing(TitleTiming.of(1, 1, 1)));
            view.show(null);
            assertEquals(1, scheduler.delayedTaskCount());

            view.dispose(null);

            assertTrue(view.isDisposed());
            assertFalse(view.isShown());
            assertEquals(0, scheduler.delayedTaskCount());
            assertEquals(0, sender.clearCount, "dispose 不清屏（区别于 deactivate / 到期 autoClear）");
        }

        @Test
        @DisplayName("dispose 后数据变更零发包")
        void testNoPacketsAfterDispose() {
            TitleView view = newDefaultView();
            view.show(null);
            view.dispose(null);

            view.set("bar", "销毁后更新");

            assertEquals(1, sender.actionBarCalls.size());
        }

        @Test
        @DisplayName("状态查询：playerId / template / dataContext / engine")
        void testAccessors() {
            TitleView view = newDefaultView();

            assertEquals(playerId, view.getPlayerId());
            assertEquals("视图模板", view.getTemplate().getName());
            assertEquals(engine, view.getEngine());
            assertNull(view.getCurrentPlayer(), "未激活时无绑定玩家");

            view.show(null);
            view.deactivate(null);
            assertNull(view.getCurrentPlayer(), "停用后解绑玩家");
        }
    }
}
