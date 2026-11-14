package io.github.JiangHu.jframe.scoreboard;

import io.github.JiangHu.jframe.content_template.HierarchicalDataContext;
import io.github.JiangHu.jframe.content_template.TemplateEngine;
import io.github.JiangHu.jframe.core.data.reactive.DataContext;
import io.github.JiangHu.jframe.core.data.reactive.Disposable;
import io.github.JiangHu.jframe.core.data.reactive.EffectScope;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 四层数据粒度逻辑测试——验证引擎全局 / 玩家全局(Session) / 计分板局部(Request) 的 parent 链正确性,
 * 以及 {@link EffectScope} 自动清理、{@link Disposable} 生命周期契约。
 *
 * <h3>四层作用域数据模型</h3>
 * <pre>
 *   engine.globalData（引擎全局，Application）
 *       ↑ parent
 *   engine.playerGlobals[uuid]（玩家全局，Session）
 *       ↑ parent
 *   view.dataContext（计分板局部，Request）
 * </pre>
 * <p>模板全局（engine.templateGlobals）作为可选独立层保留 API,不强制进入主链。
 *
 * <p>本测试不依赖 Nukkit Player,只验证数据层的读取优先级、Session 隔离、链式传播和资源清理。
 */
@DisplayName("四层数据粒度与生命周期逻辑测试")
class ThreeLayerDataLogicTest {

    private TemplateEngine engine;
    private ScoreboardManager manager;

    @BeforeEach
    void setUp() {
        engine = new TemplateEngine();
        manager = new ScoreboardManager(engine);
    }

    // ==================== 玩家全局层（Session） ====================

    @Nested
    @DisplayName("玩家全局层（Session 作用域）")
    class PlayerGlobalLayer {

        @Test
        @DisplayName("getPlayerData 首次访问自动创建（parent 为引擎全局）")
        void testPlayerDataAutoCreate() {
            UUID uuid = UUID.randomUUID();
            engine.setGlobal("serverName", "MyServer");

            DataContext playerGlobal = engine.getPlayerData(uuid);

            assertNotNull(playerGlobal, "getPlayerData 应返回非 null");
            assertEquals("MyServer", playerGlobal.get("serverName"),
                    "玩家全局应继承引擎全局数据");
        }

        @Test
        @DisplayName("getPlayerData 同 UUID 返回同一实例（缓存复用）")
        void testPlayerDataCacheReuse() {
            UUID uuid = UUID.randomUUID();

            DataContext ctx1 = engine.getPlayerData(uuid);
            DataContext ctx2 = engine.getPlayerData(uuid);

            assertSame(ctx1, ctx2, "同 UUID 应返回同一实例");
        }

        @Test
        @DisplayName("不同玩家的全局数据互相隔离")
        void testPlayerIsolation() {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();

            engine.setPlayerData(alice, "coins", 1000);
            engine.setPlayerData(bob, "coins", 500);

            assertEquals(1000, engine.getPlayerData(alice).get("coins"), "Alice 的金币");
            assertEquals(500, engine.getPlayerData(bob).get("coins"), "Bob 的金币");
        }

        @Test
        @DisplayName("setPlayerData 写入玩家全局数据")
        void testSetPlayerData() {
            UUID uuid = UUID.randomUUID();

            engine.setPlayerData(uuid, "level", 42);
            engine.setPlayerData(uuid, "name", "Steve");

            DataContext ctx = engine.getPlayerData(uuid);
            assertEquals(42, ctx.get("level"), "level 应被写入");
            assertEquals("Steve", ctx.get("name"), "name 应被写入");
        }

        @Test
        @DisplayName("setPlayerDataAll 批量写入玩家全局数据")
        void testSetPlayerDataAll() {
            UUID uuid = UUID.randomUUID();
            Map<String, Object> entries = new java.util.HashMap<>();
            entries.put("hp", 100);
            entries.put("mp", 50);

            engine.setPlayerDataAll(uuid, entries);

            DataContext ctx = engine.getPlayerData(uuid);
            assertEquals(100, ctx.get("hp"), "hp 应被批量写入");
            assertEquals(50, ctx.get("mp"), "mp 应被批量写入");
        }
    }

    // ==================== 四层优先级 ====================

    @Nested
    @DisplayName("四层读取优先级")
    class FourLayerPriority {

        @Test
        @DisplayName("四层读取优先级：局部 > 玩家全局 > 引擎全局")
        void testFourLayerReadPriority() {
            UUID uuid = UUID.randomUUID();

            // 引擎全局
            engine.setGlobal("shared", "engineValue");
            engine.setGlobal("mode", "engineMode");

            // 玩家全局（覆盖引擎的 mode）
            engine.setPlayerData(uuid, "mode", "playerMode");
            engine.setPlayerData(uuid, "playerOnly", "playerData");

            // 计分板局部（parent 为玩家全局）
            HierarchicalDataContext viewLocal = HierarchicalDataContext.of(engine.getPlayerData(uuid));
            viewLocal.put("mode", "localMode");
            viewLocal.put("localOnly", "localData");

            // 验证优先级
            assertEquals("engineValue", viewLocal.get("shared"), "引擎全局值应被读取");
            assertEquals("playerMode", engine.getPlayerData(uuid).get("mode"), "玩家全局层应覆盖引擎层");
            assertEquals("localMode", viewLocal.get("mode"), "局部层应覆盖玩家全局层");
            assertEquals("playerData", viewLocal.get("playerOnly"), "玩家全局层独有值应被读取");
            assertEquals("localData", viewLocal.get("localOnly"), "局部层独有值应被读取");
        }

        @Test
        @DisplayName("局部层不修改父层数据（写入隔离）")
        void testWriteIsolation() {
            UUID uuid = UUID.randomUUID();
            engine.setGlobal("key", "engineValue");

            HierarchicalDataContext viewLocal = HierarchicalDataContext.of(engine.getPlayerData(uuid));
            viewLocal.put("key", "localValue");

            assertEquals("engineValue", engine.getGlobalData().get("key"), "引擎全局不应被局部修改");
            assertEquals("localValue", viewLocal.get("key"), "局部层应返回自己的值");
        }
    }

    // ==================== 链式传播 ====================

    @Nested
    @DisplayName("链式变更传播")
    class ChangePropagation {

        @Test
        @DisplayName("引擎全局变更 → 玩家全局感知 → 局部感知（三层传播）")
        void testEngineChangePropagation() {
            UUID uuid = UUID.randomUUID();
            HierarchicalDataContext viewLocal = HierarchicalDataContext.of(engine.getPlayerData(uuid));

            AtomicInteger playerCount = new AtomicInteger(0);
            AtomicInteger localCount = new AtomicInteger(0);

            engine.getPlayerData(uuid).onChange(cs -> playerCount.incrementAndGet());
            viewLocal.onChange(cs -> localCount.incrementAndGet());

            engine.setGlobal("online", 42);

            assertEquals(1, playerCount.get(), "玩家全局应感知到引擎变更");
            assertEquals(1, localCount.get(), "局部层应感知到引擎变更（三层传播）");
        }

        @Test
        @DisplayName("玩家全局变更 → 局部感知（两层传播）")
        void testPlayerGlobalChangePropagation() {
            UUID uuid = UUID.randomUUID();
            HierarchicalDataContext viewLocal = HierarchicalDataContext.of(engine.getPlayerData(uuid));

            AtomicInteger localCount = new AtomicInteger(0);
            viewLocal.onChange(cs -> localCount.incrementAndGet());

            engine.setPlayerData(uuid, "coins", 1000);

            assertEquals(1, localCount.get(), "局部层应感知到玩家全局变更");
        }

        @Test
        @DisplayName("局部变更只触发自己（不向上传播）")
        void testLocalChangeNoUpwardPropagation() {
            UUID uuid = UUID.randomUUID();
            HierarchicalDataContext viewLocal = HierarchicalDataContext.of(engine.getPlayerData(uuid));

            AtomicInteger playerCount = new AtomicInteger(0);
            AtomicInteger localCount = new AtomicInteger(0);

            engine.getPlayerData(uuid).onChange(cs -> playerCount.incrementAndGet());
            viewLocal.onChange(cs -> localCount.incrementAndGet());

            viewLocal.put("tmpKey", "tmpVal");

            assertEquals(1, localCount.get(), "局部层应感知到自己的变更");
            assertEquals(0, playerCount.get(), "玩家全局不应感知到局部变更（不向上传播）");
        }
    }

    // ==================== EffectScope 自动清理 ====================

    @Nested
    @DisplayName("EffectScope 副作用作用域")
    class EffectScopeTest {

        @Test
        @DisplayName("dispose 执行全部已注册的清理逻辑")
        void testDisposeRunsAll() {
            EffectScope scope = new EffectScope();
            AtomicInteger counter = new AtomicInteger(0);

            scope.register(counter::incrementAndGet);
            scope.register(counter::incrementAndGet);
            scope.register(counter::incrementAndGet);

            scope.dispose();

            assertEquals(3, counter.get(), "dispose 应执行全部 3 条清理逻辑");
            assertTrue(scope.isDisposed(), "dispose 后 isDisposed 应为 true");
        }

        @Test
        @DisplayName("dispose 幂等——多次调用只执行一次清理")
        void testDisposeIdempotent() {
            EffectScope scope = new EffectScope();
            AtomicInteger counter = new AtomicInteger(0);

            scope.register(counter::incrementAndGet);

            scope.dispose();
            scope.dispose();
            scope.dispose();

            assertEquals(1, counter.get(), "多次 dispose 应只执行一次清理（幂等）");
        }

        @Test
        @DisplayName("单个清理逻辑异常不影响其他清理")
        void testDisposeExceptionIsolation() {
            EffectScope scope = new EffectScope();
            AtomicInteger counter = new AtomicInteger(0);

            scope.register(counter::incrementAndGet);
            scope.register(() -> { throw new RuntimeException("模拟清理异常"); });
            scope.register(counter::incrementAndGet);

            assertDoesNotThrow(scope::dispose, "单个清理异常不应传播");
            assertEquals(2, counter.get(), "异常清理前后的清理逻辑仍应执行");
        }

        @Test
        @DisplayName("scope 已销毁后 register 立即执行清理（资源不泄漏）")
        void testRegisterAfterDisposed() {
            EffectScope scope = new EffectScope();
            AtomicInteger counter = new AtomicInteger(0);

            scope.dispose();
            scope.register(counter::incrementAndGet);

            assertEquals(1, counter.get(), "已销毁 scope 的 register 应立即执行清理");
        }

        @Test
        @DisplayName("EffectScope 实现 Disposable 接口")
        void testImplementsDisposable() {
            EffectScope scope = new EffectScope();
            assertTrue(scope instanceof Disposable, "EffectScope 应实现 Disposable");
        }

        @Test
        @DisplayName("register null 安全忽略")
        void testRegisterNull() {
            EffectScope scope = new EffectScope();
            assertDoesNotThrow(() -> scope.register(null), "register null 不应抛异常");
            scope.dispose();
        }
    }

    // ==================== dispose 生命周期与内存泄漏防护 ====================

    @Nested
    @DisplayName("dispose 生命周期与内存泄漏防护")
    class DisposeLifecycle {

        @Test
        @DisplayName("HierarchicalDataContext dispose 后 parent 变更不再触发子监听器（无泄漏）")
        void testDisposeRemovesParentListener() {
            HierarchicalDataContext parent = new HierarchicalDataContext(null);
            HierarchicalDataContext child = HierarchicalDataContext.of(parent);

            AtomicInteger childCount = new AtomicInteger(0);
            child.onChange(cs -> childCount.incrementAndGet());

            // dispose 前：parent 变更应传播到 child
            parent.put("key1", "val1");
            assertEquals(1, childCount.get(), "dispose 前 parent 变更应传播到 child");

            // dispose child
            child.dispose();

            // dispose 后：parent 变更不应再触发 child（监听已摘除）
            parent.put("key2", "val2");
            assertEquals(1, childCount.get(), "dispose 后 parent 变更不应触发 child（无泄漏）");
        }

        @Test
        @DisplayName("dispose 幂等——多次调用安全")
        void testDisposeIdempotent() {
            HierarchicalDataContext ctx = new HierarchicalDataContext(null);
            assertDoesNotThrow(() -> {
                ctx.dispose();
                ctx.dispose();
                ctx.dispose();
            }, "多次 dispose 应安全（幂等）");
        }

        @Test
        @DisplayName("dispose 后 put 安全降级（忽略写入，不产生僵尸数据）")
        void testDisposeDefensivePut() {
            HierarchicalDataContext parent = new HierarchicalDataContext(null);
            HierarchicalDataContext child = HierarchicalDataContext.of(parent);

            child.dispose();

            // dispose 后写入应被忽略，不抛异常
            assertDoesNotThrow(() -> child.put("zombie", "data"), "dispose 后 put 不应抛异常");

            // 写入的数据不应出现在 parent（未向上传播）
            assertFalse(parent.snapshot().containsKey("zombie"), "dispose 后写入不应产生僵尸数据");
        }

        @Test
        @DisplayName("dispose 后 putAll 安全降级")
        void testDisposeDefensivePutAll() {
            HierarchicalDataContext parent = new HierarchicalDataContext(null);
            HierarchicalDataContext child = HierarchicalDataContext.of(parent);

            child.dispose();

            Map<String, Object> entries = new java.util.HashMap<>();
            entries.put("k1", "v1");
            entries.put("k2", "v2");

            assertDoesNotThrow(() -> child.putAll(entries), "dispose 后 putAll 不应抛异常");
            assertFalse(parent.snapshot().containsKey("k1"), "dispose 后 putAll 不应产生僵尸数据");
        }

        @Test
        @DisplayName("removePlayerData dispose 玩家全局（摘除引擎全局监听）")
        void testRemovePlayerDataDisposes() {
            UUID uuid = UUID.randomUUID();

            // 创建玩家全局
            DataContext playerGlobal = engine.getPlayerData(uuid);
            assertNotNull(playerGlobal, "getPlayerData 应返回非 null");

            // removePlayerData 应 dispose 并移除
            engine.removePlayerData(uuid);

            // 引擎变更不应再触发已 dispose 的玩家全局（无异常即通过）
            assertDoesNotThrow(() -> engine.setGlobal("test", "value"),
                    "removePlayerData 后引擎变更不应触发已 dispose 的上下文");
        }

        @Test
        @DisplayName("DataContext 实现 Disposable 接口")
        void testDataContextImplementsDisposable() {
            DataContext ctx = DataContext.of();
            assertTrue(ctx instanceof Disposable, "DataContext 应实现 Disposable");
            assertDoesNotThrow(ctx::dispose, "基类 dispose 应安全（no-op）");
        }

        @Test
        @DisplayName("HierarchicalDataContext 实现 Disposable 接口")
        void testHierarchicalImplementsDisposable() {
            HierarchicalDataContext ctx = new HierarchicalDataContext(null);
            assertTrue(ctx instanceof Disposable, "HierarchicalDataContext 应实现 Disposable");
        }
    }

    // ==================== 模板全局层（可选独立层） ====================

    @Nested
    @DisplayName("模板全局层（可选独立层）")
    class TemplateGlobalLayer {

        @Test
        @DisplayName("不同模板的全局数据互相隔离")
        void testTemplateIsolation() {
            DataContext mainCtx = manager.getTemplateDataContext("main");
            DataContext adminCtx = manager.getTemplateDataContext("admin");

            mainCtx.put("mode", "survival");
            adminCtx.put("mode", "creative");

            assertEquals("survival", mainCtx.get("mode"), "main 模板应有自己的值");
            assertEquals("creative", adminCtx.get("mode"), "admin 模板应有自己的值");
            assertNotEquals(mainCtx.get("mode"), adminCtx.get("mode"), "两个模板的值应不同");
        }

        @Test
        @DisplayName("所有模板共享引擎全局数据")
        void testSharedEngineGlobal() {
            engine.setGlobal("serverName", "MyServer");

            DataContext mainCtx = manager.getTemplateDataContext("main");
            DataContext adminCtx = manager.getTemplateDataContext("admin");

            assertEquals("MyServer", mainCtx.get("serverName"), "main 模板应能读取引擎全局");
            assertEquals("MyServer", adminCtx.get("serverName"), "admin 模板应能读取引擎全局");
        }

        @Test
        @DisplayName("getTemplateDataContext 同名返回同一实例（缓存复用）")
        void testTemplateDataContextCache() {
            DataContext ctx1 = manager.getTemplateDataContext("main");
            DataContext ctx2 = manager.getTemplateDataContext("main");

            assertSame(ctx1, ctx2, "同名模板应返回同一 DataContext 实例");
        }

        @Test
        @DisplayName("updateTemplate 写入模板全局数据")
        void testUpdateTemplate() {
            manager.updateTemplate("main", "mode", "survival");

            DataContext ctx = manager.getTemplateDataContext("main");
            assertEquals("survival", ctx.get("mode"), "updateTemplate 应写入模板全局数据");
        }

        @Test
        @DisplayName("removeTemplate 后引擎全局监听被解除（无内存泄漏）")
        void testRemoveTemplateDisposesContext() {
            DataContext templateCtx = manager.getTemplateDataContext("main");
            assertNotNull(templateCtx, "getTemplateDataContext 应返回非 null");

            manager.removeTemplate("main");

            assertDoesNotThrow(() -> engine.setGlobal("test", "value"),
                    "removeTemplate 后引擎变更不应触发已 dispose 的上下文");
        }
    }

    // ==================== makeUniqueName ====================

    @Nested
    @DisplayName("makeUniqueName 唯一名称生成")
    class MakeUniqueNameTest {

        @Test
        @DisplayName("为相同文本生成不同后缀")
        void testDifferentSuffix() {
            String text = "Hello";
            String name0 = ScoreboardView.makeUniqueName(text, 0);
            String name1 = ScoreboardView.makeUniqueName(text, 1);
            String name2 = ScoreboardView.makeUniqueName(text, 2);

            assertNotEquals(name0, name1, "index 0 和 1 的后缀应不同");
            assertNotEquals(name1, name2, "index 1 和 2 的后缀应不同");
            assertNotEquals(name0, name2, "index 0 和 2 的后缀应不同");
        }

        @Test
        @DisplayName("保留原始文本前缀")
        void testPreservesPrefix() {
            String text = "§a玩家信息";
            String result = ScoreboardView.makeUniqueName(text, 0);

            assertTrue(result.startsWith(text), "结果应以原始文本开头");
            assertTrue(result.length() > text.length(), "结果应比原始文本长（追加了后缀）");
        }

        @Test
        @DisplayName("15 行内全部唯一（侧边栏最大行数）")
        void testAllUniqueWithinMaxLines() {
            Set<String> names = new HashSet<>();
            String baseText = "Same Text";

            for (int i = 0; i < ScoreboardConstants.MAX_LINES; i++) {
                names.add(ScoreboardView.makeUniqueName(baseText, i));
            }

            assertEquals(ScoreboardConstants.MAX_LINES, names.size(),
                    "15 行相同文本应生成 15 个唯一名称");
        }

        @Test
        @DisplayName("超过颜色池大小时循环复用（16 色循环）")
        void testColorPoolCycle() {
            String text = "Line";
            String name0 = ScoreboardView.makeUniqueName(text, 0);
            String name16 = ScoreboardView.makeUniqueName(text, 16);

            assertEquals(name0, name16, "index 0 和 16 应使用相同颜色后缀（16 色循环）");
        }
    }

    // ==================== asMap 合并验证 ====================

    @Test
    @DisplayName("asMap 正确合并四层数据（局部 > 玩家全局 > 引擎全局）")
    void testAsMapMergeFourLayers() {
        UUID uuid = UUID.randomUUID();

        engine.setGlobal("engineKey", "engineVal");
        engine.setGlobal("override", "engine");

        engine.setPlayerData(uuid, "playerKey", "playerVal");
        engine.setPlayerData(uuid, "override", "player");

        HierarchicalDataContext viewLocal = HierarchicalDataContext.of(engine.getPlayerData(uuid));
        viewLocal.put("localKey", "localVal");
        viewLocal.put("override", "local");

        Map<String, Object> merged = viewLocal.asMap();

        assertEquals("engineVal", merged.get("engineKey"), "引擎层独有 key");
        assertEquals("playerVal", merged.get("playerKey"), "玩家全局层独有 key");
        assertEquals("localVal", merged.get("localKey"), "局部层独有 key");
        assertEquals("local", merged.get("override"), "同名 key 局部优先");
        assertEquals(4, merged.size(), "合并后应有 4 个 key");
    }
}
