package io.github.JiangHu.jframe.title;

import io.github.JiangHu.jframe.content_template.TemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TitleAPI} 门面委托测试：以桩构造 Manager + API，验证公开方法正确委托底层组件
 */
@DisplayName("TitleAPI 门面委托")
class TitleAPILogicTest {

    private static final String FULL_XML = "<template>"
            + "<title>{{main}}</title><line>{{sub}}</line><line>{{bar}}</line></template>";

    private TemplateEngine engine;
    private RecordingTitleSender sender;
    private ManualTitleScheduler scheduler;
    private TitleManager manager;
    private TitleAPI api;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        engine = new TemplateEngine();
        sender = new RecordingTitleSender();
        scheduler = new ManualTitleScheduler();
        manager = new TitleManager(engine, player -> sender, scheduler);
        api = new TitleAPI(manager, engine);
        playerId = UUID.randomUUID();
    }

    @Nested
    @DisplayName("实例构造")
    class Creation {

        @Test
        @DisplayName("构造器注入的 manager / engine 可经访问器取回")
        void testConstructorAccessors() {
            assertSame(manager, api.getManager());
            assertSame(engine, api.getEngine());
        }

        @Test
        @DisplayName("静态工厂 create 基于引擎创建完整门面")
        void testStaticCreate() {
            TitleAPI created = TitleAPI.create(engine);

            assertNotNull(created.getManager());
            assertSame(engine, created.getEngine());
        }
    }

    @Nested
    @DisplayName("模板管理委托")
    class TemplateDelegation {

        @Test
        @DisplayName("loadTemplate(name, xml) 委托注册")
        void testLoadFromSource() {
            TitleTemplate loaded = api.loadTemplate("facade", FULL_XML);

            assertSame(loaded, api.getManager().getTemplate("facade"));
        }

        @Test
        @DisplayName("loadTemplate(TitleTemplate) 委托注册 Builder 产物")
        void testLoadPacked() {
            TitleTemplate packed = TitleTemplate.of("packed", engine.compile(FULL_XML));

            TitleTemplate returned = api.loadTemplate(packed);

            assertSame(packed, returned);
            assertSame(packed, api.getManager().getTemplate("packed"));
        }

        @Test
        @DisplayName("removeTemplate 委托销毁视图与注册表")
        void testRemoveTemplate() {
            api.loadTemplate("facade", FULL_XML);
            TitleView view = api.getManager().show(playerId, "facade", sender);

            api.removeTemplate("facade");

            assertTrue(view.isDisposed());
            assertNull(api.getManager().getTemplate("facade"));
        }
    }

    @Nested
    @DisplayName("数据更新与访问委托")
    class DataDelegation {

        @Test
        @DisplayName("updateGlobal 委托引擎全局层并传播刷新视图")
        void testUpdateGlobal() {
            api.loadTemplate("facade", FULL_XML);
            api.getManager().show(playerId, "facade", sender);
            int titleCalls = sender.titleCalls.size();

            api.updateGlobal("main", "门面全局标题");

            assertEquals(titleCalls + 1, sender.titleCalls.size());
            assertEquals("门面全局标题", sender.lastTitleCall().title);
        }

        @Test
        @DisplayName("updateTemplate 委托引擎模板层写入（独立层，不刷新视图）")
        void testUpdateTemplate() {
            api.loadTemplate("facade", FULL_XML);
            api.getManager().show(playerId, "facade", sender);
            int barCalls = sender.actionBarCalls.size();

            api.updateTemplate("facade", "bar", "门面模板动作栏");

            // 引擎模板层已写入（对齐 scoreboard 契约：模板全局作为可选独立层保留 API）
            assertEquals("门面模板动作栏", engine.getTemplateData("facade").get("bar"));
            // 视图链 local → player → global 不含模板层，不触发刷新
            assertEquals(barCalls, sender.actionBarCalls.size());
        }

        @Test
        @DisplayName("updateAll 委托遍历活跃玩家写入玩家全局层")
        void testUpdateAll() {
            api.loadTemplate("facade", FULL_XML);
            api.getManager().show(playerId, "facade", sender);

            api.updateAll("main", "全体新标题");

            assertEquals("全体新标题", sender.lastTitleCall().title);
        }

        @Test
        @DisplayName("getGlobalDataContext / getTemplateDataContext 委托引擎层")
        void testDataContextAccessors() {
            assertSame(engine.getGlobalData(), api.getGlobalDataContext());
            assertSame(engine.getTemplateData("facade"), api.getTemplateDataContext("facade"));
        }
    }

    @Nested
    @DisplayName("生命周期委托")
    class LifecycleDelegation {

        @Test
        @DisplayName("disposeAll 销毁全部视图")
        void testDisposeAll() {
            api.loadTemplate("facade", FULL_XML);
            TitleView view = api.getManager().show(playerId, "facade", sender);

            api.disposeAll();

            assertTrue(view.isDisposed());
            assertFalse(view.isShown());
        }
    }
}
