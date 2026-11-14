package io.github.JiangHu.jframe.core.module;

import io.github.JiangHu.jframe.core.module.fixture.boot.BootFirst;
import io.github.JiangHu.jframe.core.module.fixture.boot.BootPlain;
import io.github.JiangHu.jframe.core.module.fixture.boot.BootSecond;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModuleManager#boot} 批量引导单元测试：三阶段时序（全部构造先于任何 init）、
 * 字段循环引用免疫、类型化配置读取与默认值、ofType 过滤、坏声明 fail-fast、
 * boot 后 get 同实例。
 */
class ModuleManagerBootTest {

    private static final String FIXTURE_BOOT = "io.github.JiangHu.jframe.core.module.fixture.boot";
    private static final String FIXTURE_BAD_INIT = "io.github.JiangHu.jframe.core.module.fixture.badinit";

    private static Properties props(String... pairs) {
        Properties properties = new Properties();
        for (int i = 0; i < pairs.length; i += 2) {
            properties.setProperty(pairs[i], pairs[i + 1]);
        }
        return properties;
    }

    @Test
    void bootConstructsAllBeforeAnyInitAndBindsReferences() {
        ModuleManager manager = new ModuleManager(null);
        BootFirst.LOG.clear();
        BootPlain.reset();
        BootSecond.reset();

        BeanBootResult result = manager.boot(
                getClass().getClassLoader(), new Properties(), FIXTURE_BOOT);

        // 三阶段时序：first 构造、second 构造都先于 first 的 init
        int firstConstructed = BootFirst.LOG.indexOf("first-constructed");
        int secondConstructed = BootFirst.LOG.indexOf("second-constructed");
        int firstInit = BootFirst.LOG.indexOf("first-init");
        int secondInit = BootFirst.LOG.indexOf("second-init");
        assertTrue(firstConstructed >= 0 && secondConstructed >= 0
                        && firstInit > secondConstructed && secondInit > firstInit,
                "全部构造应先于任何 @JInit，init 按扫描顺序执行: " + BootFirst.LOG);
        assertEquals(List.of("first-constructed", "second-constructed", "first-init", "second-init"),
                BootFirst.LOG);

        BootFirst first = manager.get(BootFirst.class);
        BootSecond second = manager.get(BootSecond.class);
        assertTrue(first.secondConstructedWhenInit, "first init 时 second 应已构造");
        assertTrue(second.first != null && second.first == first, "second 反向绑定 first（循环引用免疫）");
        assertTrue(first.second == second, "first 绑定 second 与容器单例一致");
        assertEquals(1, first.markerCount, "allOf 应过滤出全部标记 bean");
        assertEquals(1, BootPlain.createdCount(), "boot 应急切构造全部 bean");
        assertTrue(result.beanTypes().contains(BootPlain.class));
    }

    @Test
    void bootReadsTypedPropertiesWithDefaults() {
        ModuleManager manager = new ModuleManager(null);
        Properties properties = props(
                "boot.count", "42",
                "boot.enabled", "true",
                "boot.ratio", "1.5",
                "boot.names", " 甲 , 乙 , ,丙 ");

        manager.boot(getClass().getClassLoader(), properties, FIXTURE_BOOT);

        BootFirst first = manager.get(BootFirst.class);
        assertEquals(42, first.count);
        assertTrue(first.enabled);
        assertEquals(1.5, first.ratio);
        assertArrayEquals(new String[]{"甲", "乙", "丙"}, first.names);

        // 缺键回退默认值
        ModuleManager defaults = new ModuleManager(null);
        defaults.boot(getClass().getClassLoader(), new Properties(), FIXTURE_BOOT);
        BootFirst withDefaults = defaults.get(BootFirst.class);
        assertEquals(7, withDefaults.count);
        assertFalse(withDefaults.enabled);
        assertEquals(0.5, withDefaults.ratio);
        assertArrayEquals(new String[]{"默认甲", "默认乙"}, withDefaults.names);
    }

    @Test
    void bootBadInitDeclarationFailsFast() {
        ModuleManager manager = new ModuleManager(null);
        assertThrows(IllegalArgumentException.class,
                () -> manager.boot(getClass().getClassLoader(), new Properties(), FIXTURE_BAD_INIT),
                "非法 @JInit 声明应在引导期 fail-fast");
    }

    @Test
    void bootOfTypeFiltersBeans() {
        ModuleManager manager = new ModuleManager(null);
        BeanBootResult result = manager.boot(
                getClass().getClassLoader(), new Properties(), FIXTURE_BOOT);

        java.util.Map<String, BootFirst> matched = result.ofType(BootFirst.class);
        assertEquals(1, matched.size());
        assertTrue(matched.containsValue(manager.get(BootFirst.class)));
        assertTrue(result.ofType(java.io.Serializable.class).isEmpty(),
                "无实现时 ofType 应为空");
    }

    @Test
    void bootThenGetReturnsSameInstances() {
        ModuleManager manager = new ModuleManager(null);
        BeanBootResult result = manager.boot(
                getClass().getClassLoader(), new Properties(), FIXTURE_BOOT);

        assertDoesNotThrow(() -> manager.get(BootPlain.class));
        assertTrue(result.instances().contains(manager.get(BootPlain.class)));
        assertTrue(result.instances().contains(manager.get(BootFirst.class)));
    }
}
