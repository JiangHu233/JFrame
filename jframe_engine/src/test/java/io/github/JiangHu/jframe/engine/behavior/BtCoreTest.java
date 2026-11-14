package io.github.JiangHu.jframe.engine.behavior;

import io.github.JiangHu.jframe.engine.ecs.registry.ComponentRegistry;
import io.github.JiangHu.jframe.engine.ecs.registry.EcsTestHarness;
import io.github.JiangHu.jframe.engine.behavior.builtin.HasComponentCondition;
import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;
import io.github.JiangHu.jframe.engine.ecs.fixture.TestComponents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BT 核心单测 — 组合/装饰语义、词表注册与参数注入、YAML 树解析、
 * 能力直读条件（HasComponent 经 ECS harness）、reactive 求值与效果输出。
 */
class BtCoreTest {

    /** 词表注册表 */
    private BtRegistry registry;

    /** 计数行为（SUCCESS）执行次数 */
    private final AtomicInteger successRuns = new AtomicInteger();

    @BeforeEach
    void setUp() {
        registry = new BtRegistry();
        successRuns.set(0);
    }

    /** 恒真条件 */
    public static final class TrueCond extends BtCondition {
        @Override
        public boolean test(BtContext context) {
            return true;
        }
    }

    /** 恒假条件 */
    public static final class FalseCond extends BtCondition {
        @Override
        public boolean test(BtContext context) {
            return false;
        }
    }

    /** 计数行为（参数可配状态；公有字段=参数键） */
    public static class CountingAction extends BtAction {
        /** 返回状态（succ/fail/run；参数注入测试键） */
        public String result = "succ";

        @Override
        public BtStatus execute(BtContext context) {
            if ("emit".equals(result)) {
                context.emit("effect-1");
                return BtStatus.SUCCESS;
            }
            return switch (result) {
                case "fail" -> BtStatus.FAILURE;
                case "run" -> BtStatus.RUNNING;
                default -> BtStatus.SUCCESS;
            };
        }
    }

    private void registerDefaults() {
        registry.registerCondition("test:true", TrueCond.class);
        registry.registerCondition("test:false", FalseCond.class);
        registry.registerAction("test:count", CountingAction.class);
    }

    private BtContext context(long tick) {
        return new BtContext(new BtBlackboard(), null, tick, false);
    }

    @Test
    @DisplayName("Selector：左高右低，第一个非 FAILURE 即短路")
    void selectorPriority() {
        registerDefaults();
        BtAction fail = registry.newAction("test:count", Map.of("result", "fail"));
        BtAction succ = registry.newAction("test:count", Map.of());
        BtSelector selector = new BtSelector(List.of(fail, succ));
        assertEquals(BtStatus.SUCCESS, selector.tick(context(0)));
    }

    @Test
    @DisplayName("Sequence：全 SUCCESS 才 SUCCESS，FAILURE 短路")
    void sequenceAllOrNothing() {
        registerDefaults();
        BtCondition trueCond = registry.newCondition("test:true", Map.of());
        BtAction fail = registry.newAction("test:count", Map.of("result", "fail"));
        assertEquals(BtStatus.FAILURE, new BtSequence(List.of(trueCond, fail)).tick(context(0)));
        assertEquals(BtStatus.SUCCESS,
                new BtSequence(List.of(trueCond, registry.newAction("test:count", Map.of())))
                        .tick(context(0)));
    }

    @Test
    @DisplayName("Inverter：SUCCESS↔FAILURE 翻转")
    void inverterFlips() {
        registerDefaults();
        BtCondition trueCond = registry.newCondition("test:true", Map.of());
        assertEquals(BtStatus.FAILURE, new BtInverter(trueCond).tick(context(0)));
        BtCondition falseCond = registry.newCondition("test:false", Map.of());
        assertEquals(BtStatus.SUCCESS, new BtInverter(falseCond).tick(context(0)));
    }

    @Test
    @DisplayName("Cooldown：SUCCESS 起表，冷却期内直接 FAILURE 不求值子节点")
    void cooldownGates() {
        registerDefaults();
        BtAction counting = registry.newAction("test:count", Map.of());
        BtCooldown cooldown = new BtCooldown(10, counting);
        // 黑板为宿主长持对象（跨 tick 复用——冷却计时存于此）；Context 每 tick 新建
        BtBlackboard board = new BtBlackboard();
        assertEquals(BtStatus.SUCCESS, cooldown.tick(new BtContext(board, null, 100, false))); // 起表
        assertEquals(BtStatus.FAILURE, cooldown.tick(new BtContext(board, null, 105, false))); // 冷却中
        assertEquals(BtStatus.SUCCESS, cooldown.tick(new BtContext(board, null, 111, false))); // 过期恢复
    }

    @Test
    @DisplayName("词表参数注入：拼错键名 fail fast")
    void wordParamTypoFails() {
        registerDefaults();
        assertThrows(EcsConfigException.class,
                () -> registry.newAction("test:count", Map.of("resuLt", "fail")));
    }

    @Test
    @DisplayName("YAML 树解析：组合/装饰/条件/行为全形态 + 效果输出")
    void yamlTreeLoadAndTick() {
        registerDefaults();
        BtTreeLoader loader = new BtTreeLoader(registry);
        List<BtTree> trees = loader.load("""
                demo:
                  root:
                    select:
                      - sequence:
                          - {cond: test:false}
                          - {act: test:count, params: {result: fail}}
                      - sequence:
                          - {cond: test:true}
                          - {act: test:count, params: {result: emit}}
                """, "test");
        assertEquals(1, trees.size());
        BtContext ctx = context(0);
        assertEquals(BtStatus.SUCCESS, trees.get(0).tick(ctx));
        assertEquals(List.of("effect-1"), ctx.takeEffects()); // 行为叶只产效果不副作用
    }

    @Test
    @DisplayName("能力直读：HasComponent 条件经组件注册表判宿主组件存在性")
    void hasComponentCondition() {
        EcsTestHarness harness = new EcsTestHarness();
        ComponentRegistry components = harness.componentRegistry;
        components.register("marker", TestComponents.CounterComp.class);
        registry.bindComponentRegistry(components);
        registry.registerCondition("ecs:has-component", HasComponentCondition.class);

        BtCondition cond = registry.newCondition("ecs:has-component", Map.of("component", "marker"));
        var entity = harness.engine.createVirtual();
        BtContext without = new BtContext(new BtBlackboard(), entity, 0, false);
        assertEquals(BtStatus.FAILURE, cond.tick(without)); // 未挂组件
        harness.engine.mountComponent(entity, TestComponents.CounterComp.class, Map.of());
        BtContext with = new BtContext(new BtBlackboard(), entity, 0, false);
        assertEquals(BtStatus.SUCCESS, cond.tick(with));    // 挂了即满足
    }

    @Test
    @DisplayName("黑板：类型化读写与缺省")
    void blackboardTypedAccess() {
        BtBlackboard board = new BtBlackboard();
        board.put("num", 42L);
        assertEquals(42L, board.getLong("num", -1));
        assertEquals(-1L, board.getLong("missing", -1));
        assertEquals(0.0, board.getDouble("num", 0.0)); // 类型不符取缺省不抛
        board.put("flag", Boolean.TRUE);
        assertTrue(board.getBoolean("flag", false));
    }
}
