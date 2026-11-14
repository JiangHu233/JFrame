package io.github.JiangHu.jframe.engine.ecs.registry;

import io.github.JiangHu.jframe.engine.ecs.entity.ComponentBase;
import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ParamBinder 单测 — 字段注入、类型强转、类名解析。
 */
class ParamBinderTest {

    /** 测试用枚举 */
    enum Color { RED, GREEN }

    /** 基类字段注入观测 */
    static class Base {
        String baseField = "base-default";
    }

    /** 注入目标 */
    static class Sample extends Base {
        String name = "default";
        int count;
        double ratio;
        boolean flag;
        short tiny;
        List<String> names;
        Map<String, Object> meta;
        Color color;
    }

    @Test
    @DisplayName("同名注入：参数 key 与字段名按名匹配")
    void bindSameName() {
        Sample sample = new Sample();
        ParamBinder.bind(sample, Map.of("name", "abc"));
        assertEquals("abc", sample.name);
    }

    @Test
    @DisplayName("类型强转：Number 互转 / String→数值 / String→boolean / String→enum")
    void coerceTypes() {
        Sample sample = new Sample();
        ParamBinder.bind(sample, Map.of(
                "count", 42,                       // Integer → int
                "ratio", 0.5,                      // Double → double
                "tiny", 7L,                        // Long → short
                "flag", "true",                    // String → boolean
                "color", "GREEN",                  // String → enum
                "names", List.of("a", "b"),        // List 直接赋值
                "meta", Map.of("k", "v")           // Map 直接赋值
        ));
        assertEquals(42, sample.count);
        assertEquals(0.5, sample.ratio);
        assertEquals((short) 7, sample.tiny);
        assertTrue(sample.flag);
        assertEquals(Color.GREEN, sample.color);
        assertEquals(List.of("a", "b"), sample.names);
        assertEquals(Map.of("k", "v"), sample.meta);
    }

    @Test
    @DisplayName("String→int 强转（YAML 数字常是 Integer，但也容忍字符串形式）")
    void coerceStringToInt() {
        Sample sample = new Sample();
        ParamBinder.bind(sample, Map.of("count", " 42 "));
        assertEquals(42, sample.count);
    }

    @Test
    @DisplayName("未知字段名 → 加载/注入期 fail fast")
    void unknownFieldFails() {
        Sample sample = new Sample();
        assertThrows(EcsConfigException.class,
                () -> ParamBinder.bind(sample, Map.of("nope", 1)));
        assertThrows(EcsConfigException.class,
                () -> ParamBinder.validate(Sample.class, Map.of("nope", 1)));
    }

    @Test
    @DisplayName("无法强转 → fail fast")
    void incompatibleTypeFails() {
        Sample sample = new Sample();
        assertThrows(EcsConfigException.class,
                () -> ParamBinder.bind(sample, Map.of("count", "not-a-number")));
        assertThrows(EcsConfigException.class,
                () -> ParamBinder.bind(sample, Map.of("color", "BLUE")));
    }

    @Test
    @DisplayName("未出现的字段保留初始值（可选参数语义）")
    void missingKeyKeepsDefault() {
        Sample sample = new Sample();
        ParamBinder.bind(sample, Map.of("count", 1));
        assertEquals("default", sample.name);
        assertEquals("base-default", sample.baseField);
    }

    @Test
    @DisplayName("基类字段注入（沿继承链收集，static/final 排除）")
    void baseClassFieldInjection() {
        Sample sample = new Sample();
        ParamBinder.bind(sample, Map.of("baseField", "injected"));
        assertEquals("injected", sample.baseField);
        // ComponentBase.host 为 final → 不在可注入字段表
        assertTrue(ParamBinder.collectFields(ComponentBase.class).isEmpty());
    }

    @Test
    @DisplayName("类名解析：完整名 / 简单名（Nukkit 各包前缀）/ 失败报错")
    void resolveClassNames() {
        // 完整名
        assertEquals(Sample.class, ParamBinder.resolveClass(Sample.class.getName()));
        // 简单名：测试夹具位于 cn.nukkit.entity 包
        assertEquals(cn.nukkit.entity.FakeNukkitEntityForResolution.class,
                ParamBinder.resolveClass("FakeNukkitEntityForResolution"));
        // 简单名：MOT 子包（mob/passive）真实类（实机报错修复的回归断言）
        assertEquals(cn.nukkit.entity.mob.EntityZombie.class, ParamBinder.resolveClass("EntityZombie"));
        assertEquals(cn.nukkit.entity.mob.EntitySkeleton.class, ParamBinder.resolveClass("EntitySkeleton"));
        assertEquals(cn.nukkit.entity.passive.EntityCow.class, ParamBinder.resolveClass("EntityCow"));
        // 失败
        assertThrows(EcsConfigException.class,
                () -> ParamBinder.resolveClass("DefinitelyNotAClass"));
    }

    @Test
    @DisplayName("validate：静态校验不实例化也能发现未知字段")
    void validateStatic() {
        assertDoesNotThrow(() -> ParamBinder.validate(Sample.class, Map.of("count", 5)));
        assertNotNull(new Sample());
    }
}
