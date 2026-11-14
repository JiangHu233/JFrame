package io.github.JiangHu.jframe.content_template;

import static org.junit.jupiter.api.Assertions.*;

import io.github.JiangHu.jframe.core.data.reactive.DataContext;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link TemplateEngine} 三层数据粒度测试——验证引擎全局 / 模板全局 / 玩家本地的分层隔离与传播。
 *
 * <p>三层架构：
 * <pre>{@code
 * 引擎全局（engine.getGlobalData）    ← 所有模板、所有玩家共享
 *     ↑ parent
 * 模板全局（engine.getTemplateData）  ← 同一模板的所有玩家共享
 *     ↑ parent
 * 玩家本地（HierarchicalDataContext）  ← 仅该玩家可见
 * }</pre>
 */
@DisplayName("TemplateEngine 三层数据粒度")
class TemplateEngineThreeLayerDataTest {

    private TemplateEngine engine;

    @BeforeEach
    void setUp() {
        engine = new TemplateEngine();
    }

    // ==================== 引擎全局（第一层） ====================

    @Nested
    @DisplayName("第一层：引擎全局数据")
    class EngineGlobalTests {

        @Test
        @DisplayName("setGlobal / getGlobalData 基本读写")
        void testGlobalReadWrite() {
            engine.setGlobal("serverName", "我的服务器");
            engine.setGlobal("online", 42);

            DataContext global = engine.getGlobalData();
            assertEquals("我的服务器", global.get("serverName"));
            assertEquals(42, global.get("online"));
        }

        @Test
        @DisplayName("setGlobalAll 批量写入")
        void testGlobalBatchWrite() {
            engine.setGlobalAll(Map.of("a", 1, "b", 2, "c", 3));

            DataContext global = engine.getGlobalData();
            assertEquals(1, global.get("a"));
            assertEquals(2, global.get("b"));
            assertEquals(3, global.get("c"));
        }
    }

    // ==================== 模板全局（第二层） ====================

    @Nested
    @DisplayName("第二层：模板全局数据")
    class TemplateGlobalTests {

        @Test
        @DisplayName("getTemplateData 返回非 null DataContext")
        void testTemplateDataNotNull() {
            DataContext data = engine.getTemplateData("main");
            assertNotNull(data);
        }

        @Test
        @DisplayName("同名模板返回同一实例（缓存）")
        void testTemplateDataCacheHit() {
            DataContext first = engine.getTemplateData("main");
            DataContext second = engine.getTemplateData("main");
            assertSame(first, second, "同名模板应返回同一实例");
        }

        @Test
        @DisplayName("不同模板返回不同实例（隔离）")
        void testTemplateDataIsolation() {
            DataContext main = engine.getTemplateData("main");
            DataContext admin = engine.getTemplateData("admin");
            assertNotSame(main, admin, "不同模板应返回不同实例");
        }

        @Test
        @DisplayName("setTemplateData 写入模板级数据")
        void testSetTemplateData() {
            engine.setTemplateData("main", "guildScore", 5000);

            DataContext data = engine.getTemplateData("main");
            assertEquals(5000, data.get("guildScore"));
        }

        @Test
        @DisplayName("setTemplateDataAll 批量写入")
        void testSetTemplateDataAll() {
            engine.setTemplateDataAll("main", Map.of("score", 100, "level", 5));

            DataContext data = engine.getTemplateData("main");
            assertEquals(100, data.get("score"));
            assertEquals(5, data.get("level"));
        }

        @Test
        @DisplayName("模板间数据隔离：main 的数据不影响 admin")
        void testTemplateIsolation() {
            engine.setTemplateData("main", "shared", "mainValue");
            engine.setTemplateData("admin", "shared", "adminValue");

            assertEquals("mainValue", engine.getTemplateData("main").get("shared"));
            assertEquals("adminValue", engine.getTemplateData("admin").get("shared"));
        }

        @Test
        @DisplayName("模板全局可见引擎全局数据（parent 继承）")
        void testTemplateInheritsEngineGlobal() {
            engine.setGlobal("serverName", "我的服务器");

            DataContext templateData = engine.getTemplateData("main");
            assertEquals("我的服务器", templateData.get("serverName"));
        }

        @Test
        @DisplayName("模板全局覆盖引擎全局同名 key（优先级）")
        void testTemplateOverridesEngineGlobal() {
            engine.setGlobal("score", 100);
            engine.setTemplateData("main", "score", 200);

            assertEquals(200, engine.getTemplateData("main").get("score"));
            assertEquals(100, engine.getGlobalData().get("score"), "引擎全局不受影响");
        }
    }

    // ==================== 三层集成 ====================

    @Nested
    @DisplayName("三层集成：引擎全局 → 模板全局 → 玩家本地")
    class ThreeLayerIntegrationTests {

        @Test
        @DisplayName("玩家本地可见所有三层数据")
        void testPlayerSeesAllLayers() {
            engine.setGlobal("serverName", "我的服务器");
            engine.setTemplateData("main", "guildScore", 5000);

            DataContext templateGlobal = engine.getTemplateData("main");
            HierarchicalDataContext playerData = HierarchicalDataContext.of(templateGlobal);
            playerData.put("coins", 1000);

            assertEquals("我的服务器", playerData.get("serverName"));   // 引擎全局
            assertEquals(5000, playerData.get("guildScore"));          // 模板全局
            assertEquals(1000, playerData.get("coins"));               // 玩家本地
        }

        @Test
        @DisplayName("读取优先级：玩家 > 模板 > 引擎")
        void testReadPriority() {
            engine.setGlobal("key", "engine");
            engine.setTemplateData("main", "key", "template");

            DataContext templateGlobal = engine.getTemplateData("main");
            HierarchicalDataContext playerData = HierarchicalDataContext.of(templateGlobal);
            playerData.put("key", "player");

            assertEquals("player", playerData.get("key"), "玩家本地优先");
        }

        @Test
        @DisplayName("写入隔离：玩家写入不影响模板和引擎")
        void testWriteIsolation() {
            engine.setGlobal("key", "engine");
            engine.setTemplateData("main", "key", "template");

            DataContext templateGlobal = engine.getTemplateData("main");
            HierarchicalDataContext playerData = HierarchicalDataContext.of(templateGlobal);
            playerData.put("key", "player");

            assertEquals("template", templateGlobal.get("key"), "模板全局不受玩家写入影响");
            assertEquals("engine", engine.getGlobalData().get("key"), "引擎全局不受影响");
        }

        @Test
        @DisplayName("变更传播：引擎全局变更 → 传播到模板和玩家")
        void testEngineGlobalPropagation() {
            engine.setGlobal("online", 10);

            DataContext templateGlobal = engine.getTemplateData("main");
            HierarchicalDataContext playerData = HierarchicalDataContext.of(templateGlobal);

            // 引擎全局变更
            engine.setGlobal("online", 42);

            assertEquals(42, templateGlobal.get("online"), "模板全局收到引擎全局变更");
            assertEquals(42, playerData.get("online"), "玩家本地收到引擎全局变更");
        }

        @Test
        @DisplayName("变更传播：模板全局变更 → 传播到玩家，不影响引擎")
        void testTemplateGlobalPropagation() {
            engine.setTemplateData("main", "score", 100);

            DataContext templateGlobal = engine.getTemplateData("main");
            HierarchicalDataContext playerData = HierarchicalDataContext.of(templateGlobal);

            // 模板全局变更
            engine.setTemplateData("main", "score", 200);

            assertEquals(200, playerData.get("score"), "玩家本地收到模板全局变更");
            assertNull(engine.getGlobalData().get("score"), "引擎全局不受模板变更影响");
        }

        @Test
        @DisplayName("变更不向上传播：玩家写入不影响模板")
        void testNoUpwardPropagation() {
            engine.setTemplateData("main", "key", "template");

            DataContext templateGlobal = engine.getTemplateData("main");
            HierarchicalDataContext playerData = HierarchicalDataContext.of(templateGlobal);
            playerData.put("key", "player");

            assertEquals("template", templateGlobal.get("key"), "模板全局不受玩家写入影响");
        }
    }

    // ==================== 资源释放 ====================

    @Nested
    @DisplayName("removeTemplateData 资源释放")
    class RemoveTemplateDataTests {

        @Test
        @DisplayName("removeTemplateData 后再获取返回新实例")
        void testRemoveReturnsNewInstance() {
            DataContext first = engine.getTemplateData("main");
            engine.removeTemplateData("main");
            DataContext second = engine.getTemplateData("main");

            assertNotSame(first, second, "移除后应返回新实例");
        }

        @Test
        @DisplayName("removeTemplateData 返回被移除的 DataContext")
        void testRemoveReturnsOldData() {
            engine.setTemplateData("main", "key", "value");
            DataContext removed = engine.removeTemplateData("main");

            assertNotNull(removed, "应返回被移除的数据上下文");
            assertEquals("value", removed.get("key"));
        }

        @Test
        @DisplayName("removeTemplateData 后引擎全局数据不受影响")
        void testRemoveDoesNotAffectEngineGlobal() {
            engine.setGlobal("serverName", "我的服务器");
            engine.setTemplateData("main", "score", 100);

            engine.removeTemplateData("main");

            assertEquals("我的服务器", engine.getGlobalData().get("serverName"),
                    "引擎全局数据不受模板移除影响");
        }
    }
}
