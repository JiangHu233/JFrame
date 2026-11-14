package io.github.JiangHu.jframe.engine.ecs.gameobject;

import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;
import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;
import io.github.JiangHu.jframe.engine.ecs.fixture.TestComponents;
import io.github.JiangHu.jframe.engine.ecs.fixture.TestRegistries;
import io.github.JiangHu.jframe.engine.ecs.registry.EcsTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GameObjectLoader 单测 — YAML 解析、三类来源、fail-fast 校验、冲突检测。
 */
class GameObjectLoaderTest {

    /** 引擎夹具 */
    private EcsTestHarness harness;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        harness = new EcsTestHarness();
        harness.componentRegistry.register("counter", TestComponents.CounterComp.class);
        harness.componentRegistry.register("eventful", TestComponents.EventfulComp.class);
        harness.selectorRegistry.register("always", TestRegistries.AlwaysSelector.class);
        harness.spawnerRegistry.register("null-spawner", TestRegistries.NullSpawner.class);
        harness.spawnerRegistry.register("recording-spawner", TestRegistries.RecordingSpawner.class);
        harness.despawnerRegistry.register("recording-despawner", TestRegistries.RecordingDespawner.class);
    }

    private static final String VALID_YAML = """
            magic-chest:
              selector: { use: tag, params: { tag: chest } }
              tags: [chest, loot]
              components:
                - id: counter
                  params: { value: 5, name: "magic" }
              spawn: { use: recording-spawner, params: { count: 3 } }
            """;

    @Test
    @DisplayName("合法 YAML：选择器/生成器实例化注入 + 组件规格解析")
    void parseValidYaml() {
        List<GameObjectDefinition> defs = harness.loader.load(VALID_YAML, "test");
        assertEquals(1, defs.size());
        GameObjectDefinition def = defs.get(0);
        assertEquals("magic-chest", def.name());
        assertNotNull(def.selector(), "选择器应在加载时实例化");
        assertNotNull(def.spawner(), "生成器应在加载时实例化");
        assertEquals(java.util.Set.of("chest", "loot"), def.tags());
        assertEquals(1, def.components().size());
        assertEquals("counter", def.components().get(0).id());
        assertEquals(3, ((TestRegistries.RecordingSpawner) def.spawner()).count,
                "生成器参数应在加载时注入");
    }

    @Test
    @DisplayName("未知组件 id → fail fast")
    void unknownComponentId() {
        assertThrows(EcsConfigException.class, () -> harness.loader.load("""
                bad:
                  components: [ { id: no-such-comp } ]
                """, "test"));
    }

    @Test
    @DisplayName("未知选择器名 → fail fast")
    void unknownSelector() {
        assertThrows(EcsConfigException.class, () -> harness.loader.load("""
                bad:
                  selector: { use: no-such-selector }
                  components: [ { id: counter } ]
                """, "test"));
    }

    @Test
    @DisplayName("未知生成器名 → fail fast")
    void unknownSpawner() {
        assertThrows(EcsConfigException.class, () -> harness.loader.load("""
                bad:
                  spawn: { use: no-such-spawner }
                """, "test"));
    }

    @Test
    @DisplayName("despawn 节点：加载期实例化注入参数（与 spawn 同构）")
    void despawnNodeParsed() {
        List<GameObjectDefinition> defs = harness.loader.load("""
                arena:
                  spawn: { use: recording-spawner }
                  despawn: { use: recording-despawner, params: { count: 7 } }
                arena-b:
                  despawn: { use: recording-despawner }
                """, "test");
        GameObjectDefinition def = defs.get(0);
        TestRegistries.RecordingDespawner despawner = (TestRegistries.RecordingDespawner) def.despawner();
        assertNotNull(despawner, "回收器应在加载时实例化");
        assertEquals(7, despawner.count, "回收器参数应在加载时注入");
        TestRegistries.RecordingDespawner other =
                (TestRegistries.RecordingDespawner) defs.get(1).despawner();
        assertNotSame(despawner, other, "每条定义引用独立实例");
    }

    @Test
    @DisplayName("未知回收器名 → fail fast")
    void unknownDespawner() {
        assertThrows(EcsConfigException.class, () -> harness.loader.load("""
                bad:
                  despawn: { use: no-such-despawner }
                """, "test"));
    }

    @Test
    @DisplayName("回收器 params 未知字段名 → 加载期拒绝")
    void unknownDespawnerParamField() {
        assertThrows(EcsConfigException.class, () -> harness.loader.load("""
                bad:
                  despawn: { use: recording-despawner, params: { nope: 1 } }
                """, "test"));
    }

    @Test
    @DisplayName("组件 params 未知字段名 → 加载期校验拒绝")
    void unknownComponentParamField() {
        assertThrows(EcsConfigException.class, () -> harness.loader.load("""
                bad:
                  components: [ { id: counter, params: { nope: 1 } } ]
                """, "test"));
    }

    @Test
    @DisplayName("选择器 params 未知字段名 → 加载期拒绝")
    void unknownSelectorParamField() {
        assertThrows(EcsConfigException.class, () -> harness.loader.load("""
                bad:
                  selector: { use: tag, params: { tag: x, nope: 1 } }
                  components: [ { id: counter } ]
                """, "test"));
    }

    @Test
    @DisplayName("文件/目录来源：递归收集 *.yml")
    void fileSource() throws IOException {
        Path sub = Files.createDirectories(tempDir.resolve("nested"));
        Files.writeString(tempDir.resolve("a.yml"), VALID_YAML);
        Files.writeString(sub.resolve("b.yml"), """
                other:
                  selector: { use: always }
                  components: [ { id: counter } ]
                """);
        Files.writeString(tempDir.resolve("readme.txt"), "not yaml");

        List<GameObjectDefinition> defs = harness.loader.load(tempDir.toFile());
        assertEquals(2, defs.size());
    }

    @Test
    @DisplayName("classpath 来源：递归扫描 test resources（含嵌套）")
    void classpathSource() {
        List<GameObjectDefinition> defs = harness.loader.loadClasspath(
                "gameobjects", getClass().getClassLoader());
        assertTrue(defs.size() >= 2, "应收集到 gameobjects/ 与 gameobjects/nested/ 下的定义");
        assertTrue(defs.stream().anyMatch(d -> d.name().equals("classpath-root")));
        assertTrue(defs.stream().anyMatch(d -> d.name().equals("classpath-nested")));
    }

    @Test
    @DisplayName("classpath 前缀不存在 → 空结果不报错")
    void classpathEmptyPrefixDir() {
        List<GameObjectDefinition> defs = harness.loader.loadClasspath(
                "no-such-prefix-dir", getClass().getClassLoader());
        assertTrue(defs.isEmpty());
    }

    @Test
    @DisplayName("引擎侧定义名冲突 → 报错（同 YAML 二次加载）")
    void conflictOnReload() {
        List<GameObjectDefinition> defs = harness.loader.load(VALID_YAML, "test");
        harness.engine.loadDefinition(defs.get(0));
        EcsConfigException e = assertThrows(EcsConfigException.class,
                () -> harness.engine.loadDefinition(defs.get(0)));
        assertTrue(e.getMessage().contains("magic-chest"));
    }

    @Test
    @DisplayName("加载后回溯：文件来源定义同样触发存量实体挂载")
    void fileSourceRetroMount() throws IOException {
        EcsEntity existing = harness.engine.createVirtual();
        existing.addTag("chest");
        Path file = tempDir.resolve("c.yml");
        Files.writeString(file, VALID_YAML);
        for (GameObjectDefinition def : harness.loader.load(file.toFile())) {
            harness.engine.loadDefinition(def);
        }
        assertNotNull(existing.get(TestComponents.CounterComp.class), "文件来源也应回溯存量");
        assertEquals(5, existing.get(TestComponents.CounterComp.class).value);
    }

    @Test
    @DisplayName("空 YAML / null 根 → 空结果")
    void emptyYaml() {
        assertTrue(harness.loader.load("", "test").isEmpty());
        assertTrue(harness.loader.load("   ", "test").isEmpty());
    }
}
