package io.github.JiangHu.jframe.serialize.value;

import io.github.JiangHu.jframe.serialize.exception.DataException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SaveValue} 通用中间数据模型单元测试。
 * <p>
 * 覆盖场景：工厂方法、类型判断、类型取值（含宽容 asString）、
 * 类型不匹配异常、Map/List 构建与 equals。
 */
@DisplayName("SaveValue 中间数据模型测试")
class SaveValueTest {

    @Nested
    @DisplayName("工厂方法与类型判断")
    class FactoryAndTypeCheckTest {

        @Test
        @DisplayName("of() 创建对应类型的标量节点")
        void scalarFactories() {
            assertTrue(SaveValue.of("hi").isStr());
            assertTrue(SaveValue.of(42).isNum());
            assertTrue(SaveValue.of(3.14).isNum());
            assertTrue(SaveValue.of(true).isBool());
            assertTrue(SaveValue.ofNull().isNull());
        }

        @Test
        @DisplayName("of(null) 拒绝 null（字符串与数值）")
        void nullRejected() {
            assertThrows(DataException.class, () -> SaveValue.of((String) null));
            assertThrows(DataException.class, () -> SaveValue.of((Number) null));
        }

        @Test
        @DisplayName("list() / map() 创建空容器节点")
        void containerFactories() {
            assertTrue(SaveValue.list().isList());
            assertTrue(SaveValue.list().isEmpty());
            assertTrue(SaveValue.map().isMap());
            assertTrue(SaveValue.map().isEmpty());
        }

        @Test
        @DisplayName("类型判断互斥：标量不会误判为容器")
        void typeChecksAreExclusive() {
            SaveValue str = SaveValue.of("x");
            assertFalse(str.isMap());
            assertFalse(str.isList());
            assertFalse(str.isNull());

            SaveValue.Null nil = SaveValue.ofNull();
            assertFalse(nil.isStr());
            assertFalse(nil.isNum());
        }
    }

    @Nested
    @DisplayName("类型取值")
    class AccessorTest {

        @Test
        @DisplayName("标量取值返回正确内容")
        void scalarAccessors() {
            assertEquals("Steve", SaveValue.of("Steve").asString());
            assertEquals(42, SaveValue.of(42).asInt());
            assertEquals(42L, SaveValue.of(42).asLong());
            assertEquals(3.5, SaveValue.of(3.5).asDouble(), 1e-9);
            assertTrue(SaveValue.of(true).asBool());
            assertEquals(7, SaveValue.of(7).asNumber().intValue());
        }

        @Test
        @DisplayName("asString 宽容策略：数值与布尔可转字符串")
        void asStringLenient() {
            assertEquals("42", SaveValue.of(42).asString());
            assertEquals("3.5", SaveValue.of(3.5).asString());
            assertEquals("true", SaveValue.of(true).asString());
        }

        @Test
        @DisplayName("类型不匹配时抛 DataException")
        void mismatchThrows() {
            assertThrows(DataException.class, () -> SaveValue.of("x").asInt());
            assertThrows(DataException.class, () -> SaveValue.of(1).asBool());
            assertThrows(DataException.class, () -> SaveValue.of("x").asList());
            assertThrows(DataException.class, () -> SaveValue.of("x").asMap());
            assertThrows(DataException.class, () -> SaveValue.ofNull().asString());
            assertThrows(DataException.class, () -> SaveValue.list().asMap());
            assertThrows(DataException.class, () -> SaveValue.map().asList());
        }
    }

    @Nested
    @DisplayName("Map / List 构建")
    class ContainerTest {

        @Test
        @DisplayName("Map 链式 put 保持插入顺序")
        void mapChainedPutKeepsOrder() {
            SaveValue.Map map = SaveValue.map()
                    .put("b", SaveValue.of(2))
                    .put("a", SaveValue.of(1))
                    .put("c", SaveValue.of(3));

            assertEquals(3, map.size());
            assertEquals("[b, a, c]", map.keys().toString());
            assertTrue(map.containsKey("a"));
            assertEquals(1, map.get("a").asInt());
        }

        @Test
        @DisplayName("List 链式 add 与按下标读取")
        void listChainedAdd() {
            SaveValue.List list = SaveValue.list()
                    .add(SaveValue.of("pvp"))
                    .add(SaveValue.of(88))
                    .add(SaveValue.ofNull());

            assertEquals(3, list.size());
            assertEquals("pvp", list.get(0).asString());
            assertEquals(88, list.get(1).asInt());
            assertTrue(list.get(2).isNull());
        }

        @Test
        @DisplayName("嵌套结构：Map 内含 List 与子 Map")
        void nestedStructure() {
            SaveValue.Map root = SaveValue.map()
                    .put("name", SaveValue.of("Steve"))
                    .put("tags", SaveValue.list()
                            .add(SaveValue.of("pvp"))
                            .add(SaveValue.of("builder")))
                    .put("pos", SaveValue.map()
                            .put("x", SaveValue.of(1))
                            .put("y", SaveValue.of(2)));

            assertEquals("Steve", root.get("name").asString());
            assertEquals(2, root.get("tags").asList().size());
            assertEquals(1, root.get("pos").asMap().get("x").asInt());
        }

        @Test
        @DisplayName("Map / List 基于内容的 equals")
        void contentEquality() {
            SaveValue.Map a = SaveValue.map().put("k", SaveValue.of(1));
            SaveValue.Map b = SaveValue.map().put("k", SaveValue.of(1));
            assertEquals(a, b);

            SaveValue.List la = SaveValue.list().add(SaveValue.of("x"));
            SaveValue.List lb = SaveValue.list().add(SaveValue.of("x"));
            assertEquals(la, lb);

            b.put("k", SaveValue.of(2));
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("Map 移除键与不存在的键返回 null")
        void mapRemoveAndMissingKey() {
            SaveValue.Map map = SaveValue.map().put("k", SaveValue.of(1));
            assertEquals(1, map.remove("k").asInt());
            assertNull(map.get("k"));
            assertNull(map.remove("nope"));
        }
    }
}
