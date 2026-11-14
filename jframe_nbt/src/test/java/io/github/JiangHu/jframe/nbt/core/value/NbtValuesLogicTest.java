package io.github.JiangHu.jframe.nbt.core.value;

import cn.nukkit.nbt.tag.ByteTag;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.DoubleTag;
import cn.nukkit.nbt.tag.FloatTag;
import cn.nukkit.nbt.tag.IntArrayTag;
import cn.nukkit.nbt.tag.IntTag;
import cn.nukkit.nbt.tag.ListTag;
import cn.nukkit.nbt.tag.LongTag;
import cn.nukkit.nbt.tag.ShortTag;
import cn.nukkit.nbt.tag.StringTag;
import cn.nukkit.nbt.tag.Tag;
import io.github.JiangHu.jframe.nbt.core.NbtTypeMismatchException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NbtValues} 纯逻辑测试 —— 表 4-1 写入推断全行覆盖、List 归一矩阵、null 三边界、
 * autoFit、读取解包与宽化（表 4-2/4-3）、convert 按 Class 解包（DESIGN.md 第四章 4.2/4.3，
 * M1 验收标准 ⑥）。
 */
@DisplayName("NbtValues 类型识别（表 4-1 / 4-2 / 4-3）")
class NbtValuesLogicTest {

    // ==================== 表 4-1：写入推断 ====================

    @Nested
    @DisplayName("写入推断 of(Object)：表 4-1 逐行")
    class WriteInference {

        @Test
        @DisplayName("Tag 直通（幂等，同实例）")
        void tagPassthrough() {
            Tag tag = new StringTag("", "x");
            assertSame(tag, NbtValues.of(tag));
        }

        @Test
        @DisplayName("Boolean → ByteTag(1/0)")
        void booleanToByte() {
            Tag t = NbtValues.of(Boolean.TRUE);
            assertInstanceOf(ByteTag.class, t);
            assertEquals(1, ((ByteTag) t).getData().intValue());
            assertEquals(0, ((ByteTag) NbtValues.of(Boolean.FALSE)).getData().intValue());
        }

        @Test
        @DisplayName("六种数值包装类型 → 对应宽度 Tag")
        void numericWidths() {
            assertInstanceOf(ByteTag.class, NbtValues.of((byte) 1));
            assertInstanceOf(ShortTag.class, NbtValues.of((short) 2));
            assertInstanceOf(IntTag.class, NbtValues.of(3));
            assertInstanceOf(LongTag.class, NbtValues.of(4L));
            assertInstanceOf(FloatTag.class, NbtValues.of(0.5f));
            assertInstanceOf(DoubleTag.class, NbtValues.of(0.25));
        }

        @Test
        @DisplayName("String → StringTag（不做数字猜测）")
        void stringToString() {
            Tag t = NbtValues.of("123");
            assertInstanceOf(StringTag.class, t, "字符串 \"123\" 不应被猜成数字");
            assertEquals("123", ((StringTag) t).data);
        }

        @Test
        @DisplayName("byte[] / int[] 直映射")
        void primitiveArrays() {
            assertInstanceOf(cn.nukkit.nbt.tag.ByteArrayTag.class, NbtValues.of(new byte[]{1, 2}));
            Tag t = NbtValues.of(new int[]{1, 2, 3});
            assertInstanceOf(IntArrayTag.class, t);
            assertArrayEquals(new int[]{1, 2, 3}, ((IntArrayTag) t).data);
        }

        @Test
        @DisplayName("long[] → 抛异常（平台无 LongArrayTag，DESIGN 9.4 降级）")
        void longArrayRejected() {
            assertThrows(NbtTypeMismatchException.class, () -> NbtValues.of(new long[]{1L}));
        }

        @Test
        @DisplayName("short[]/float[]/double[]/boolean[]/Object[] → ListTag（元素递归 + 归一）")
        void otherArraysToList() {
            Tag t = NbtValues.of(new double[]{1.0, 2.0});
            assertInstanceOf(ListTag.class, t);
            assertEquals(2, ((ListTag<?>) t).size());
            assertInstanceOf(DoubleTag.class, ((ListTag<?>) t).get(0));

            Tag b = NbtValues.of(new boolean[]{true, false});
            assertInstanceOf(ListTag.class, b);
            assertEquals(1, ((ByteTag) ((ListTag<?>) b).get(0)).getData().intValue());
            assertEquals(0, ((ByteTag) ((ListTag<?>) b).get(1)).getData().intValue());
        }

        @Test
        @DisplayName("Map<String,?> → CompoundTag（值递归；null 值跳过该键）")
        void mapToCompound() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", "x");
            map.put("count", 64);
            map.put("skip", null);
            Tag t = NbtValues.of(map);
            assertInstanceOf(CompoundTag.class, t);
            CompoundTag c = (CompoundTag) t;
            assertEquals("x", c.getString("name"));
            assertEquals(64, c.getInt("count"));
            assertFalse(c.getTags().containsKey("skip"), "null 值应跳过该键");
        }

        @Test
        @DisplayName("Map 非 String 键 → 抛异常")
        void mapNonStringKeyRejected() {
            assertThrows(NbtTypeMismatchException.class, () -> NbtValues.of(Map.of(1, "x")));
        }

        @Test
        @DisplayName("嵌套 Map/List 递归推断")
        void nestedRecursion() {
            Tag t = NbtValues.of(Map.of("display", Map.of("Lore", List.of("a", "b"))));
            CompoundTag c = (CompoundTag) t;
            CompoundTag display = c.getCompound("display");
            ListTag<?> lore = display.getList("Lore");
            assertEquals(2, lore.size());
            assertEquals("a", ((StringTag) lore.get(0)).data);
        }

        @Test
        @DisplayName("BigInteger → LongTag；超 long 值域抛异常")
        void bigInteger() {
            Tag t = NbtValues.of(BigInteger.valueOf(123L));
            assertInstanceOf(LongTag.class, t);
            assertEquals(123L, ((LongTag) t).getData().longValue());
            assertThrows(NbtTypeMismatchException.class,
                    () -> NbtValues.of(new BigInteger("99999999999999999999")));
        }

        @Test
        @DisplayName("BigDecimal → DoubleTag：STRICT 丢精度抛 / LENIENT 截断接受")
        void bigDecimal() {
            Tag exact = NbtValues.of(new BigDecimal("0.5"), CoerceMode.STRICT);
            assertEquals(0.5, ((DoubleTag) exact).getData(), 0.0);
            assertThrows(NbtTypeMismatchException.class,
                    () -> NbtValues.of(new BigDecimal("0.1"), CoerceMode.STRICT));
            Tag truncated = NbtValues.of(new BigDecimal("0.1"), CoerceMode.LENIENT);
            assertInstanceOf(DoubleTag.class, truncated);
        }

        @Test
        @DisplayName("不支持的类型 → 抛异常（不做反射/toString 魔法）")
        void unsupportedType() {
            assertThrows(NbtTypeMismatchException.class, () -> NbtValues.of(new Object()));
            class Pojo {
            }
            assertThrows(NbtTypeMismatchException.class, () -> NbtValues.of(new Pojo()));
        }

        @Test
        @DisplayName("of(null) → NPE（顶层 null 由 API 层转 delete）")
        void nullRejected() {
            assertThrows(NullPointerException.class, () -> NbtValues.of(null));
        }
    }

    // ==================== List 归一矩阵 ====================

    @Nested
    @DisplayName("List 元素类型归一（NBT ListTag 同质要求）")
    class ListNormalization {

        @Test
        @DisplayName("同型 → 直接成表")
        void sameType() {
            ListTag<?> t = NbtValues.ofList(List.of(1, 2, 3), ListPolicy.REJECT);
            assertEquals(3, t.size());
            assertTrue(t.getAll().stream().allMatch(e -> e instanceof IntTag));
        }

        @Test
        @DisplayName("Byte+Int 混合 → 提升为 Int")
        void byteIntWidenToInt() {
            List<Object> mixed = List.of((byte) 1, 2);
            ListTag<?> t = NbtValues.ofList(mixed, ListPolicy.REJECT);
            assertTrue(t.getAll().stream().allMatch(e -> e instanceof IntTag));
        }

        @Test
        @DisplayName("Byte+Short 混合 → 提升为 Int")
        void byteShortWidenToInt() {
            ListTag<?> t = NbtValues.ofList(List.of((byte) 1, (short) 2), ListPolicy.REJECT);
            assertTrue(t.getAll().stream().allMatch(e -> e instanceof IntTag));
        }

        @Test
        @DisplayName("含 Long → 提升为 Long")
        void widenToLong() {
            ListTag<?> t = NbtValues.ofList(List.of(1, 2L), ListPolicy.REJECT);
            assertTrue(t.getAll().stream().allMatch(e -> e instanceof LongTag));
        }

        @Test
        @DisplayName("含 Float → 提升为 Float")
        void widenToFloat() {
            ListTag<?> t = NbtValues.ofList(List.of(1, 0.5f), ListPolicy.REJECT);
            assertTrue(t.getAll().stream().allMatch(e -> e instanceof FloatTag));
        }

        @Test
        @DisplayName("含 Double → 提升为 Double（最宽）")
        void widenToDouble() {
            ListTag<?> t = NbtValues.ofList(List.of((byte) 1, 2L, 0.5f, 0.25), ListPolicy.REJECT);
            assertTrue(t.getAll().stream().allMatch(e -> e instanceof DoubleTag));
        }

        @Test
        @DisplayName("Boolean 计入数值（按 ByteTag）参与提升")
        void booleanCountsAsByte() {
            ListTag<?> t = NbtValues.ofList(List.of(true, 2), ListPolicy.REJECT);
            assertTrue(t.getAll().stream().allMatch(e -> e instanceof IntTag),
                    "true(1b) + 2(int) 应提升为 Int");
        }

        @Test
        @DisplayName("非数值异构 → 默认 REJECT 抛异常")
        void heterogeneousRejected() {
            assertThrows(NbtTypeMismatchException.class,
                    () -> NbtValues.ofList(List.of("a", 1), ListPolicy.REJECT));
        }

        @Test
        @DisplayName("非数值异构 → ACCEPT_HETEROGENEOUS 显式放行")
        void heterogeneousAccepted() {
            ListTag<?> t = NbtValues.ofList(List.of("a", 1), ListPolicy.ACCEPT_HETEROGENEOUS);
            assertEquals(2, t.size());
            assertInstanceOf(StringTag.class, t.get(0));
            assertInstanceOf(IntTag.class, t.get(1));
        }

        @Test
        @DisplayName("空集合 → 空 ListTag")
        void emptyList() {
            ListTag<?> t = NbtValues.ofList(List.of(), ListPolicy.REJECT);
            assertEquals(0, t.size());
        }

        @Test
        @DisplayName("List 元素 null → 抛异常（位置有语义）")
        void nullElementRejected() {
            assertThrows(NbtTypeMismatchException.class,
                    () -> NbtValues.ofList(java.util.Arrays.asList("a", null), ListPolicy.REJECT));
        }

        @Test
        @DisplayName("嵌套 List 元素各自归一")
        void nestedListElements() {
            ListTag<?> t = NbtValues.ofList(List.of(List.of(1, (byte) 2), List.of("a")), ListPolicy.REJECT);
            ListTag<?> first = (ListTag<?>) t.get(0);
            assertTrue(first.getAll().stream().allMatch(e -> e instanceof IntTag));
        }
    }

    // ==================== autoFit ====================

    @Nested
    @DisplayName("autoFit 旧值类型无损适配（开放问题 #10 默认开启）")
    class AutoFit {

        @Test
        @DisplayName("同类型直通")
        void sameType() {
            Tag fitted = NbtValues.autoFit(new IntTag("", 5), new IntTag("", 1));
            assertInstanceOf(IntTag.class, fitted);
            assertEquals(5, ((IntTag) fitted).getData());
        }

        @Test
        @DisplayName("旧 Byte + 新 Int(32) → ByteTag(32)（示例表 #28）")
        void intFitsByte() {
            Tag fitted = NbtValues.autoFit(new IntTag("", 32), new ByteTag("", 64));
            assertInstanceOf(ByteTag.class, fitted);
            assertEquals(32, ((ByteTag) fitted).getData().intValue());
        }

        @Test
        @DisplayName("旧 Byte + 新 Int(300) → null（越界不可无损容纳）")
        void intNotFitByte() {
            assertNull(NbtValues.autoFit(new IntTag("", 300), new ByteTag("", 1)));
        }

        @Test
        @DisplayName("旧 Float + 新 Double(0.5) → FloatTag(0.5)")
        void doubleFitsFloat() {
            Tag fitted = NbtValues.autoFit(new DoubleTag("", 0.5), new FloatTag("", 0.1f));
            assertInstanceOf(FloatTag.class, fitted);
            assertEquals(0.5f, ((FloatTag) fitted).getData(), 0.0f);
        }

        @Test
        @DisplayName("旧 Int + 新 Double(1.5) → null（非整值不可无损装入 int）")
        void fractionalNotFitInt() {
            assertNull(NbtValues.autoFit(new DoubleTag("", 1.5), new IntTag("", 1)));
        }

        @Test
        @DisplayName("旧 Int + 新 Double(3.0) → IntTag(3)（整值可装入）")
        void wholeDoubleFitsInt() {
            Tag fitted = NbtValues.autoFit(new DoubleTag("", 3.0), new IntTag("", 1));
            assertInstanceOf(IntTag.class, fitted);
            assertEquals(3, ((IntTag) fitted).getData());
        }

        @Test
        @DisplayName("旧 Long + 新 Int → LongTag（宽化无损）")
        void intFitsLong() {
            Tag fitted = NbtValues.autoFit(new IntTag("", 7), new LongTag("", 1L));
            assertInstanceOf(LongTag.class, fitted);
            assertEquals(7L, ((LongTag) fitted).getData().longValue());
        }

        @Test
        @DisplayName("旧 String + 新 Int → null（非数值类型间不适配）")
        void crossKindNotFit() {
            assertNull(NbtValues.autoFit(new IntTag("", 1), new StringTag("", "x")));
        }

        @Test
        @DisplayName("旧 Byte + 新 Long(2^24+1) → null（超出 float/int 精度域的整值检查走 int 域）")
        void longNotFitByte() {
            assertNull(NbtValues.autoFit(new LongTag("", 128), new ByteTag("", 1)));
        }
    }

    // ==================== 读取方向：unwrap / toJavaValue ====================

    @Nested
    @DisplayName("原生解包 unwrap / 深解包 toJavaValue（表 4-2）")
    class Unwrap {

        @Test
        @DisplayName("各标量 Tag 按自身宽度解包")
        void scalarWidths() {
            assertEquals((byte) 1, NbtValues.unwrap(new ByteTag("", 1)));
            assertEquals((short) 2, NbtValues.unwrap(new ShortTag("", 2)));
            assertEquals(3, NbtValues.unwrap(new IntTag("", 3)));
            assertEquals(4L, NbtValues.unwrap(new LongTag("", 4L)));
            assertEquals(0.5f, NbtValues.unwrap(new FloatTag("", 0.5f)));
            assertEquals(0.25, NbtValues.unwrap(new DoubleTag("", 0.25)));
            assertEquals("x", NbtValues.unwrap(new StringTag("", "x")));
        }

        @Test
        @DisplayName("数组解包返回拷贝（改副本不影响原 Tag）")
        void arrayCopied() {
            IntArrayTag tag = new IntArrayTag("", new int[]{1, 2, 3});
            int[] copy = (int[]) NbtValues.unwrap(tag);
            copy[0] = 99;
            assertEquals(1, tag.data[0], "解包应返回拷贝，原 Tag 不受影响");
        }

        @Test
        @DisplayName("Compound → 浅 Map；List → 浅 List")
        void shallowContainers() {
            CompoundTag c = new CompoundTag("").putInt("n", 1);
            Map<?, ?> map = (Map<?, ?>) NbtValues.unwrap(c);
            assertEquals(1, map.size());
            assertInstanceOf(IntTag.class, map.get("n"), "浅解包保持 Tag");

            ListTag<Tag> list = new ListTag<>("").add(new IntTag("", 1));
            List<?> shallow = (List<?>) NbtValues.unwrap(list);
            assertEquals(1, shallow.size());
            assertInstanceOf(IntTag.class, shallow.get(0));
        }

        @Test
        @DisplayName("toJavaValue 深解包为嵌套 Map/List/标量")
        void deepUnwrap() {
            CompoundTag root = new CompoundTag("")
                    .put("display", new CompoundTag("").putString("Name", "x"))
                    .put("Lore", new ListTag<Tag>().add(new StringTag("", "L1")));
            Map<?, ?> map = (Map<?, ?>) NbtValues.toJavaValue(root);
            Map<?, ?> display = (Map<?, ?>) map.get("display");
            assertEquals("x", display.get("Name"));
            List<?> lore = (List<?>) map.get("Lore");
            assertEquals("L1", lore.get(0));
        }
    }

    // ==================== asXxx：宽化 / 窄化 / 模式 ====================

    @Nested
    @DisplayName("asXxx 读取解包：无损宽化自动、窄化依 CoerceMode（表 4-3）")
    class AsXxx {

        @Test
        @DisplayName("宽化无损自动：Byte → asInt / asLong / asDouble")
        void wideningAutomatic() {
            ByteTag b = new ByteTag("", 64);
            assertEquals(64, NbtValues.asInt(b));
            assertEquals(64L, NbtValues.asLong(b));
            assertEquals(64.0, NbtValues.asDouble(b));
            assertEquals(64.0f, NbtValues.asFloat(b));
        }

        @Test
        @DisplayName("Long → asInt：STRICT 溢出抛 / LENIENT 截断")
        void longNarrowing() {
            LongTag big = new LongTag("", 1L + Integer.MAX_VALUE);
            assertThrows(NbtTypeMismatchException.class, () -> NbtValues.asInt(big, CoerceMode.STRICT));
            assertEquals(Integer.MIN_VALUE, NbtValues.asInt(big, CoerceMode.LENIENT), "Java 原生截断语义");
            LongTag small = new LongTag("", 42L);
            assertEquals(42, NbtValues.asInt(small, CoerceMode.STRICT));
        }

        @Test
        @DisplayName("Double → asInt：STRICT 非整值抛 / LENIENT 截断")
        void doubleNarrowing() {
            assertThrows(NbtTypeMismatchException.class,
                    () -> NbtValues.asInt(new DoubleTag("", 1.5), CoerceMode.STRICT));
            assertEquals(1, NbtValues.asInt(new DoubleTag("", 1.5), CoerceMode.LENIENT));
            assertEquals(2, NbtValues.asInt(new DoubleTag("", 2.0), CoerceMode.STRICT), "整值 double 可无损转 int");
        }

        @Test
        @DisplayName("非数值：STRICT 抛 / LENIENT 类型零值（开放问题 #8 建议默认值）")
        void nonNumericModes() {
            StringTag s = new StringTag("", "x");
            assertThrows(NbtTypeMismatchException.class, () -> NbtValues.asInt(s, CoerceMode.STRICT));
            assertEquals(0, NbtValues.asInt(s, CoerceMode.LENIENT));
            assertEquals(0L, NbtValues.asLong(s, CoerceMode.LENIENT));
            assertEquals(0.0, NbtValues.asDouble(s, CoerceMode.LENIENT));
            assertEquals(0f, NbtValues.asFloat(s, CoerceMode.LENIENT));
            assertFalse(NbtValues.asBoolean(s, CoerceMode.LENIENT));
            assertNull(NbtValues.asString(new IntTag("", 1), CoerceMode.LENIENT));
        }

        @Test
        @DisplayName("asBoolean：数值非零为 true（开放问题 #7 建议默认值）")
        void booleanSemantics() {
            assertTrue(NbtValues.asBoolean(new ByteTag("", 1)));
            assertTrue(NbtValues.asBoolean(new IntTag("", -3)), "非零即真（含负数）");
            assertFalse(NbtValues.asBoolean(new ByteTag("", 0)));
            assertFalse(NbtValues.asBoolean(new DoubleTag("", 0.0)));
        }

        @Test
        @DisplayName("asString：仅 StringTag；其他 STRICT 抛")
        void asStringStrict() {
            assertThrows(NbtTypeMismatchException.class,
                    () -> NbtValues.asString(new IntTag("", 1), CoerceMode.STRICT));
            assertEquals("x", NbtValues.asString(new StringTag("", "x"), CoerceMode.STRICT));
        }

        @Test
        @DisplayName("asDouble：long 超 2^53 精度域 STRICT 抛")
        void longPrecisionDomain() {
            long big = 9_007_199_254_740_993L; // 2^53+1（非 2 的幂，double 无法精确表示）
            assertThrows(NbtTypeMismatchException.class,
                    () -> NbtValues.asDouble(new LongTag("", big), CoerceMode.STRICT));
            assertEquals((double) big, NbtValues.asDouble(new LongTag("", big), CoerceMode.LENIENT));
        }

        @Test
        @DisplayName("asFloat：double 丢精度 STRICT 抛")
        void floatPrecision() {
            assertThrows(NbtTypeMismatchException.class,
                    () -> NbtValues.asFloat(new DoubleTag("", 0.1), CoerceMode.STRICT));
            assertEquals(0.5f, NbtValues.asFloat(new DoubleTag("", 0.5), CoerceMode.STRICT));
        }
    }

    // ==================== convert：按目标 Class 解包 ====================

    @Nested
    @DisplayName("convert(Tag, Class)：NbtAPI.get(root, path, Class) 的底层")
    class Convert {

        @Test
        @DisplayName("示例表 #30：ByteTag 宽化为 Integer 64")
        void byteWidensToInteger() {
            Integer v = NbtValues.convert(new ByteTag("", 64), Integer.class, CoerceMode.LENIENT);
            assertEquals(64, v);
        }

        @Test
        @DisplayName("精确类型与宽化全链")
        void typeMatrix() {
            ByteTag b = new ByteTag("", 3);
            assertEquals((byte) 3, NbtValues.convert(b, Byte.class, CoerceMode.STRICT));
            assertEquals((short) 3, NbtValues.convert(b, Short.class, CoerceMode.STRICT));
            assertEquals(3, NbtValues.convert(b, Integer.class, CoerceMode.STRICT));
            assertEquals(3L, NbtValues.convert(b, Long.class, CoerceMode.STRICT));
            assertEquals(3.0, NbtValues.convert(b, Double.class, CoerceMode.STRICT));
            assertEquals(3.0f, NbtValues.convert(b, Float.class, CoerceMode.STRICT));
            assertEquals(true, NbtValues.convert(new ByteTag("", 1), Boolean.class, CoerceMode.STRICT));
            assertEquals("x", NbtValues.convert(new StringTag("", "x"), String.class, CoerceMode.STRICT));
        }

        @Test
        @DisplayName("原始类型 Class 同样受支持（int.class 等）")
        void primitiveClasses() {
            assertEquals(5, NbtValues.convert(new IntTag("", 5), int.class, CoerceMode.STRICT));
            assertEquals(5L, NbtValues.convert(new IntTag("", 5), long.class, CoerceMode.STRICT));
            assertEquals(5.0, NbtValues.convert(new IntTag("", 5), double.class, CoerceMode.STRICT));
        }

        @Test
        @DisplayName("容器类型：CompoundTag / Map / List / Tag / Object")
        void containerTypes() {
            CompoundTag c = new CompoundTag("").putInt("n", 1);
            assertSame(c, NbtValues.convert(c, CompoundTag.class, CoerceMode.STRICT));
            assertInstanceOf(Map.class, NbtValues.convert(c, Map.class, CoerceMode.STRICT));

            ListTag<Tag> l = new ListTag<>("").add(new IntTag("", 1));
            assertInstanceOf(List.class, NbtValues.convert(l, List.class, CoerceMode.STRICT));
            assertInstanceOf(Tag.class, NbtValues.convert(l, Tag.class, CoerceMode.STRICT));
            assertInstanceOf(List.class, NbtValues.convert(l, Object.class, CoerceMode.STRICT));
        }

        @Test
        @DisplayName("数组类型：byte[] / int[]")
        void arrayTypes() {
            assertArrayEquals(new byte[]{1, 2},
                    NbtValues.convert(new cn.nukkit.nbt.tag.ByteArrayTag("", new byte[]{1, 2}), byte[].class, CoerceMode.STRICT));
            assertArrayEquals(new int[]{3},
                    NbtValues.convert(new IntArrayTag("", new int[]{3}), int[].class, CoerceMode.STRICT));
        }

        @Test
        @DisplayName("类型不符：STRICT 抛 / LENIENT 返回 null")
        void mismatchModes() {
            assertThrows(NbtTypeMismatchException.class,
                    () -> NbtValues.convert(new IntTag("", 1), String.class, CoerceMode.STRICT));
            assertNull(NbtValues.convert(new IntTag("", 1), String.class, CoerceMode.LENIENT));
        }

        @Test
        @DisplayName("不支持的目标类型 → 抛异常（两模式一致）")
        void unsupportedTarget() {
            assertThrows(NbtTypeMismatchException.class,
                    () -> NbtValues.convert(new IntTag("", 1), Character.class, CoerceMode.LENIENT));
        }
    }
}
