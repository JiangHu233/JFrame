package io.github.JiangHu.jframe.content_template;

import io.github.JiangHu.jframe.core.data.reactive.ChangeSet;
import io.github.JiangHu.jframe.core.data.reactive.DataContext;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HierarchicalDataContext} 单元测试——验证分层响应式容器的核心能力。
 *
 * <h3>测试覆盖</h3>
 * <ul>
 *   <li><b>读取合并</b>：parent + local 合并，local 优先</li>
 *   <li><b>写入隔离</b>：put 只写 local，不影响 parent</li>
 *   <li><b>变更传播</b>：parent 变更 → child onChange 触发</li>
 *   <li><b>dispose 清理</b>：dispose 后 parent 变更不再传播</li>
 *   <li><b>null parent</b>：parent 为 null 时退化为普通 DataContext</li>
 *   <li><b>snapshot 不可变性</b></li>
 * </ul>
 */
@DisplayName("HierarchicalDataContext 分层响应式容器测试")
class HierarchicalDataContextTest {

    // ==================== 读取合并 ====================

    @Nested
    @DisplayName("读取合并：parent + local，玩家优先")
    class ReadMergeTest {

        @Test
        @DisplayName("get() local 优先，parent 兜底")
        void testGetLocalPriority() {
            DataContext global = DataContext.of();
            global.put("serverName", "全局服务器");
            global.put("onlineCount", 42);

            HierarchicalDataContext player = HierarchicalDataContext.of(global);
            player.put("coins", 1000);
            player.put("serverName", "玩家覆盖"); // 同名 key，玩家优先

            assertEquals("玩家覆盖", player.get("serverName"), "同名 key 玩家优先");
            assertEquals(42, player.get("onlineCount"), "parent 兜底");
            assertEquals(1000, player.get("coins"), "玩家私有");
        }

        @Test
        @DisplayName("get() local 和 parent 都不存在返回 null")
        void testGetNullWhenAbsent() {
            DataContext global = DataContext.of();
            global.put("exists", true);

            HierarchicalDataContext player = HierarchicalDataContext.of(global);

            assertNull(player.get("notExists"), "不存在的 key 返回 null");
        }

        @Test
        @DisplayName("asMap() 合并 parent + local，local 覆盖同名 key")
        void testAsMapMerge() {
            DataContext global = DataContext.of();
            global.put("serverName", "全局");
            global.put("onlineCount", 42);

            HierarchicalDataContext player = HierarchicalDataContext.of(global);
            player.put("coins", 1000);
            player.put("serverName", "玩家"); // 覆盖全局

            Map<String, Object> merged = player.asMap();

            assertEquals(3, merged.size(), "合并后 3 个 key");
            assertEquals("玩家", merged.get("serverName"), "local 覆盖 parent");
            assertEquals(42, merged.get("onlineCount"), "parent 数据保留");
            assertEquals(1000, merged.get("coins"), "local 数据保留");
        }
    }

    // ==================== 写入隔离 ====================

    @Nested
    @DisplayName("写入隔离：put 只写 local，不影响 parent")
    class WriteIsolationTest {

        @Test
        @DisplayName("put() 不影响 parent 数据")
        void testPutIsolation() {
            DataContext global = DataContext.of();
            global.put("shared", "原始值");

            HierarchicalDataContext player = HierarchicalDataContext.of(global);
            player.put("shared", "玩家修改");
            player.put("playerOnly", "玩家私有");

            // parent 不受影响
            assertEquals("原始值", global.get("shared"), "parent 数据不受 child put 影响");
            assertNull(global.get("playerOnly"), "parent 看不到 child 私有数据");

            // child 看到自己的修改
            assertEquals("玩家修改", player.get("shared"), "child put 生效");
        }

        @Test
        @DisplayName("putAll() 批量写入只影响 local")
        void testPutAllIsolation() {
            DataContext global = DataContext.of();
            global.put("count", 0);

            HierarchicalDataContext player = HierarchicalDataContext.of(global);
            player.putAll(Map.of("count", 99, "name", "Steve"));

            assertEquals(0, global.get("count"), "parent count 不变");
            assertNull(global.get("name"), "parent 看不到 name");
            assertEquals(99, player.get("count"), "child count 已更新");
        }
    }

    // ==================== 变更传播 ====================

    @Nested
    @DisplayName("变更传播：parent 变更 → child onChange 触发")
    class ChangePropagationTest {

        @Test
        @DisplayName("parent put → child onChange 收到通知")
        void testParentChangePropagates() {
            DataContext global = DataContext.of();
            HierarchicalDataContext player = HierarchicalDataContext.of(global);

            AtomicInteger callCount = new AtomicInteger(0);
            AtomicReference<String> changedKey = new AtomicReference<>();

            player.onChange(change -> {
                callCount.incrementAndGet();
                if (change.containsKey("onlineCount")) {
                    changedKey.set("onlineCount");
                }
            });

            // parent 变更 → child 监听器触发
            global.put("onlineCount", 100);

            assertEquals(1, callCount.get(), "parent 变更应触发 child 监听器");
            assertEquals("onlineCount", changedKey.get(), "变更 key 正确传递");
        }

        @Test
        @DisplayName("local put → child onChange 也触发（两层都能感知）")
        void testLocalChangeAlsoTriggers() {
            DataContext global = DataContext.of();
            HierarchicalDataContext player = HierarchicalDataContext.of(global);

            AtomicInteger callCount = new AtomicInteger(0);
            player.onChange(change -> callCount.incrementAndGet());

            // local 变更也触发
            player.put("coins", 500);
            assertEquals(1, callCount.get(), "local 变更也应触发监听器");
        }

        @Test
        @DisplayName("多个 child 共享同一 parent，parent 变更全部触发")
        void testMultipleChildrenPropagation() {
            DataContext global = DataContext.of();

            HierarchicalDataContext player1 = HierarchicalDataContext.of(global);
            HierarchicalDataContext player2 = HierarchicalDataContext.of(global);

            AtomicInteger count1 = new AtomicInteger(0);
            AtomicInteger count2 = new AtomicInteger(0);
            player1.onChange(c -> count1.incrementAndGet());
            player2.onChange(c -> count2.incrementAndGet());

            global.put("onlineCount", 50);

            assertEquals(1, count1.get(), "player1 收到通知");
            assertEquals(1, count2.get(), "player2 收到通知");
        }
    }

    // ==================== dispose 清理 ====================

    @Nested
    @DisplayName("dispose 清理：解除 parent 监听，防止内存泄漏")
    class DisposeTest {

        @Test
        @DisplayName("dispose() 后 parent 变更不再传播")
        void testDisposeStopsPropagation() {
            DataContext global = DataContext.of();
            HierarchicalDataContext player = HierarchicalDataContext.of(global);

            AtomicInteger callCount = new AtomicInteger(0);
            player.onChange(c -> callCount.incrementAndGet());

            // dispose 前能收到
            global.put("v1", 1);
            assertEquals(1, callCount.get(), "dispose 前应收到通知");

            player.dispose();

            // dispose 后不再收到
            global.put("v2", 2);
            assertEquals(1, callCount.get(), "dispose 后不应再收到通知");
        }

        @Test
        @DisplayName("dispose() 幂等——多次调用安全")
        void testDisposeIdempotent() {
            DataContext global = DataContext.of();
            HierarchicalDataContext player = HierarchicalDataContext.of(global);

            player.dispose();
            player.dispose(); // 不抛异常
            player.dispose(); // 不抛异常

            // parent 变更不传播
            AtomicInteger count = new AtomicInteger(0);
            player.onChange(c -> count.incrementAndGet());
            global.put("test", 1);
            assertEquals(0, count.get(), "dispose 后 onChange 注册也不再生效");
        }

        @Test
        @DisplayName("removeListener() 用不同引用不影响原监听器")
        void testRemoveListenerDifferentReference() {
            DataContext global = DataContext.of();
            HierarchicalDataContext player = HierarchicalDataContext.of(global);

            AtomicInteger count = new AtomicInteger(0);
            java.util.function.Consumer<ChangeSet> listener = c -> count.incrementAndGet();
            player.onChange(listener);

            global.put("v1", 1);
            assertEquals(1, count.get());

            // 用不同引用移除，不影响原监听器
            player.removeListener(c -> {});
            global.put("v2", 2);
            assertEquals(2, count.get(), "不同引用移除不影响原监听器");
        }

        @Test
        @DisplayName("removeListener() 用同一引用正确移除")
        void testRemoveListenerSameReference() {
            DataContext global = DataContext.of();
            HierarchicalDataContext player = HierarchicalDataContext.of(global);

            AtomicInteger count = new AtomicInteger(0);
            java.util.function.Consumer<ChangeSet> listener = c -> count.incrementAndGet();
            player.onChange(listener);

            global.put("v1", 1);
            assertEquals(1, count.get());

            player.removeListener(listener);

            global.put("v2", 2);
            assertEquals(1, count.get(), "移除后不再收到通知");
        }
    }

    // ==================== null parent ====================

    @Nested
    @DisplayName("null parent：退化为普通 DataContext")
    class NullParentTest {

        @Test
        @DisplayName("parent=null 时 get/asMap 正常工作")
        void testNullParentBasics() {
            HierarchicalDataContext player = HierarchicalDataContext.of((DataContext) null);
            player.put("key", "value");

            assertEquals("value", player.get("key"));
            assertEquals("value", player.asMap().get("key"));
            assertNull(player.get("notExists"));
        }

        @Test
        @DisplayName("parent=null 时 dispose 不抛异常")
        void testNullParentDispose() {
            HierarchicalDataContext player = HierarchicalDataContext.of((DataContext) null);
            player.put("key", "value");
            player.dispose(); // 不抛异常
        }
    }

    // ==================== snapshot 不可变 ====================

    @Nested
    @DisplayName("snapshot 不可变性")
    class SnapshotTest {

        @Test
        @DisplayName("snapshot() 返回不可变 Map")
        void testSnapshotImmutable() {
            DataContext global = DataContext.of();
            global.put("gKey", "gValue");

            HierarchicalDataContext player = HierarchicalDataContext.of(global);
            player.put("pKey", "pValue");

            Map<String, Object> snapshot = player.snapshot();

            assertEquals("gValue", snapshot.get("gKey"), "snapshot 包含 parent 数据");
            assertEquals("pValue", snapshot.get("pKey"), "snapshot 包含 local 数据");

            assertThrows(UnsupportedOperationException.class, () -> {
                snapshot.put("hack", "hack");
            }, "snapshot 应为不可变 Map");
        }

        @Test
        @DisplayName("snapshot() local 覆盖 parent 同名 key")
        void testSnapshotOverride() {
            DataContext global = DataContext.of();
            global.put("shared", "global");

            HierarchicalDataContext player = HierarchicalDataContext.of(global);
            player.put("shared", "player");

            Map<String, Object> snapshot = player.snapshot();
            assertEquals("player", snapshot.get("shared"), "snapshot 中 local 覆盖 parent");
        }
    }

    // ==================== 集成：TemplateEngine 全局数据渲染 ====================

    @Nested
    @DisplayName("TemplateEngine 全局数据渲染集成")
    class TemplateEngineGlobalDataTest {

        @Test
        @DisplayName("全局数据 + 玩家数据合并渲染")
        void testGlobalDataRender() {
            TemplateEngine engine = new TemplateEngine();

            // 设置全局数据
            engine.setGlobal("serverName", "我的服务器");
            engine.setGlobal("onlineCount", 42);

            // 玩家数据（继承全局）
            HierarchicalDataContext playerData = HierarchicalDataContext.of(engine.getGlobalData());
            playerData.put("playerName", "Steve");
            playerData.put("coins", 1000);

            // 编译模板
            Template template = engine.compile("""
                <template>
                    <title>{{serverName}}</title>
                    <line>玩家: {{playerName}}</line>
                    <line>在线: {{onlineCount}}</line>
                    <line>金币: {{coins}}</line>
                </template>
                """);

            RenderResult result = engine.render(template, playerData);

            assertEquals("我的服务器", result.getTitle(), "标题使用全局数据");
            assertEquals(3, result.getLines().size());
            assertEquals("玩家: Steve", result.getLines().get(0), "使用玩家数据");
            assertEquals("在线: 42", result.getLines().get(1), "使用全局数据");
            assertEquals("金币: 1000", result.getLines().get(2), "使用玩家数据");
        }

        @Test
        @DisplayName("玩家数据覆盖全局同名 key 渲染")
        void testPlayerOverrideGlobalRender() {
            TemplateEngine engine = new TemplateEngine();
            engine.setGlobal("title", "全局标题");

            HierarchicalDataContext playerData = HierarchicalDataContext.of(engine.getGlobalData());
            playerData.put("title", "玩家标题"); // 覆盖全局

            Template template = engine.compile("""
                <template>
                    <title>测试</title>
                    <line>{{title}}</line>
                </template>
                """);

            RenderResult result = engine.render(template, playerData);
            assertEquals("玩家标题", result.getLines().get(0), "玩家数据覆盖全局");
        }

        @Test
        @DisplayName("全局数据变更后重新渲染反映新值")
        void testGlobalDataChangeReRender() {
            TemplateEngine engine = new TemplateEngine();
            engine.setGlobal("onlineCount", 10);

            HierarchicalDataContext playerData = HierarchicalDataContext.of(engine.getGlobalData());

            Template template = engine.compile("""
                <template>
                    <title>在线人数</title>
                    <line>当前在线: {{onlineCount}}</line>
                </template>
                """);

            // 第一次渲染
            RenderResult r1 = engine.render(template, playerData);
            assertEquals("当前在线: 10", r1.getLines().get(0));

            // 全局数据变更
            engine.setGlobal("onlineCount", 25);

            // 第二次渲染（asMap 自动合并最新全局数据）
            RenderResult r2 = engine.render(template, playerData);
            assertEquals("当前在线: 25", r2.getLines().get(0), "重新渲染应反映全局数据变更");
        }

        @Test
        @DisplayName("setGlobalAll 批量设置全局数据")
        void testSetGlobalAll() {
            TemplateEngine engine = new TemplateEngine();
            engine.setGlobalAll(Map.of("a", 1, "b", 2, "c", 3));

            DataContext global = engine.getGlobalData();
            assertEquals(1, global.get("a"));
            assertEquals(2, global.get("b"));
            assertEquals(3, global.get("c"));
        }
    }
}
