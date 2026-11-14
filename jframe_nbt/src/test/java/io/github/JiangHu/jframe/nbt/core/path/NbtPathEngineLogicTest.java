package io.github.JiangHu.jframe.nbt.core.path;

import cn.nukkit.nbt.tag.ByteArrayTag;
import cn.nukkit.nbt.tag.ByteTag;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.DoubleTag;
import cn.nukkit.nbt.tag.IntArrayTag;
import cn.nukkit.nbt.tag.IntTag;
import cn.nukkit.nbt.tag.ListTag;
import cn.nukkit.nbt.tag.StringTag;
import cn.nukkit.nbt.tag.Tag;
import io.github.JiangHu.jframe.nbt.NbtAPI;
import io.github.JiangHu.jframe.nbt.adapt.NbtTarget;
import io.github.JiangHu.jframe.nbt.core.NbtModifyException;
import io.github.JiangHu.jframe.nbt.core.NbtPathException;
import io.github.JiangHu.jframe.nbt.core.NbtPathNotFoundException;
import io.github.JiangHu.jframe.nbt.core.NbtPathTypeException;
import io.github.JiangHu.jframe.nbt.core.NbtTypeMismatchException;
import io.github.JiangHu.jframe.nbt.core.value.CoerceMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径引擎 get/set/insert/merge/delete 全矩阵测试 —— 数据来自 DESIGN.md 2.4 示例表
 * （用例编号在 @DisplayName 中标注），另含 NbtAPI 门面、CompoundTarget 与并发求值
 * （M1 验收标准 ①④）。
 */
@DisplayName("NbtPath 引擎（get/set/insert/merge/delete 矩阵）")
class NbtPathEngineLogicTest {

    private CompoundTag root;

    @BeforeEach
    void setUp() {
        root = sampleRoot();
    }

    /** DESIGN.md 2.4 示例树（附加 data:ByteArray 供数组元素用例）。 */
    private static CompoundTag sampleRoot() {
        CompoundTag diamondDisplay = new CompoundTag("")
                .putString("Name", "§b钻石")
                .put("Lore", new ListTag<Tag>().add(new StringTag("", "§7闪闪发光")));
        CompoundTag diamondTag = new CompoundTag("").put("display", diamondDisplay);
        CompoundTag diamond = new CompoundTag("")
                .putString("id", "minecraft:diamond")
                .putByte("Count", 64)
                .put("tag", diamondTag);
        CompoundTag iron = new CompoundTag("")
                .putString("id", "minecraft:iron_ingot")
                .putByte("Count", 3)
                .putShort("Damage", (short) 0);
        ListTag<Tag> items = new ListTag<Tag>().add(diamond).add(iron);

        CompoundTag speed = new CompoundTag("")
                .putString("Name", "generic.movement_speed").putDouble("Amount", 0.1);
        CompoundTag health = new CompoundTag("")
                .putString("Name", "generic.max_health").putDouble("Amount", 20.0);
        ListTag<Tag> attributes = new ListTag<Tag>().add(speed).add(health);

        CompoundTag custom = new CompoundTag("")
                .putInt("weird.key", 1)
                .put("Payload", new IntArrayTag("", new int[]{1, 2, 3}));

        return new CompoundTag("")
                .put("Items", items)
                .put("Attributes", attributes)
                .put("custom", custom)
                .putInt("version", 1)
                .put("data", new ByteArrayTag("", new byte[]{1, 2}));
    }

    private List<Tag> select(String path) {
        return NbtPath.compile(path).select(root);
    }

    // ==================== get：示例表 #1~#9、#25 ====================

    @Nested
    @DisplayName("get 读取矩阵（读宽容：0 命中返回空列表）")
    class Get {

        @Test
        @DisplayName("#1 Items[0].tag.display.Name → [\"§b钻石\"]")
        void case1() {
            List<Tag> hits = select("Items[0].tag.display.Name");
            assertEquals(1, hits.size());
            assertEquals("§b钻石", ((StringTag) hits.get(0)).data);
        }

        @Test
        @DisplayName("#2 Items[-1].id → 负索引取末元素")
        void case2() {
            List<Tag> hits = select("Items[-1].id");
            assertEquals(1, hits.size());
            assertEquals("minecraft:iron_ingot", ((StringTag) hits.get(0)).data);
        }

        @Test
        @DisplayName("#3 Items[].Count → [64b, 3b] 全体元素展开")
        void case3() {
            List<Tag> hits = select("Items[].Count");
            assertEquals(2, hits.size());
            assertEquals(64, ((ByteTag) hits.get(0)).getData().intValue());
            assertEquals(3, ((ByteTag) hits.get(1)).getData().intValue());
        }

        @Test
        @DisplayName("#4 Items[{id:\"minecraft:diamond\"}].Count → [64b] 复合过滤")
        void case4() {
            List<Tag> hits = select("Items[{id:\"minecraft:diamond\"}].Count");
            assertEquals(1, hits.size());
            assertEquals(64, ((ByteTag) hits.get(0)).getData().intValue());
        }

        @Test
        @DisplayName("#5 过滤+深路径+[] 展开 → Lore 全体")
        void case5() {
            List<Tag> hits = select("Items[{id:\"minecraft:diamond\", Count:64b}].tag.display.Lore[]");
            assertEquals(1, hits.size());
            assertEquals("§7闪闪发光", ((StringTag) hits.get(0)).data);
        }

        @Test
        @DisplayName("#6 Attributes[{Name:\"generic.movement_speed\"}].Amount → [0.1d]")
        void case6() {
            List<Tag> hits = select("Attributes[{Name:\"generic.movement_speed\"}].Amount");
            assertEquals(1, hits.size());
            assertEquals(0.1, ((DoubleTag) hits.get(0)).getData(), 0.0);
        }

        @Test
        @DisplayName("#7 {version:1} → 根过滤命中根自身")
        void case7() {
            List<Tag> hits = select("{version:1}");
            assertEquals(1, hits.size());
            assertSame(root, hits.get(0));
        }

        @Test
        @DisplayName("#8 custom.\"weird.key\" → 引号键导航")
        void case8() {
            List<Tag> hits = select("custom.\"weird.key\"");
            assertEquals(1, hits.size());
            assertEquals(1, ((IntTag) hits.get(0)).getData().intValue());
        }

        @Test
        @DisplayName("#9 custom.Payload[1] → IntArray 元素以 IntTag 命中")
        void case9() {
            List<Tag> hits = select("custom.Payload[1]");
            assertEquals(1, hits.size());
            assertEquals(2, ((IntTag) hits.get(0)).getData().intValue());
        }

        @Test
        @DisplayName("过滤类型敏感：{Count:64}(Int) 不匹配 64b；{Count:64b} 匹配")
        void filterTypeSensitive() {
            assertTrue(select("Items[{Count:64}]").isEmpty(), "1 不等于 1b（NBT 类型敏感）");
            assertEquals(1, select("Items[{Count:64b}]").size());
        }

        @Test
        @DisplayName("#25 中间命中 StringTag 继续下钻 → 0 命中（读静默）")
        void case25() {
            assertTrue(select("Items[0].id.x").isEmpty());
        }

        @Test
        @DisplayName("下标越界（正/负）→ 0 命中")
        void indexOutOfBounds() {
            assertTrue(select("Items[5]").isEmpty());
            assertTrue(select("Items[-3]").isEmpty());
        }

        @Test
        @DisplayName("空路径 → 根自身；根过滤不匹配 → 0 命中")
        void rootPath() {
            List<Tag> hits = select("");
            assertEquals(1, hits.size());
            assertSame(root, hits.get(0));
            assertTrue(select("{version:2}").isEmpty());
        }

        @Test
        @DisplayName("根过滤 + 段序列组合")
        void rootFilterWithSegments() {
            List<Tag> hits = select("{version:1}.Items[0].id");
            assertEquals(1, hits.size());
            assertEquals("minecraft:diamond", ((StringTag) hits.get(0)).data);
        }

        @Test
        @DisplayName("段类型与节点类型冲突 → 0 命中（对非 List 用 []/下标/过滤）")
        void typeConflictSilent() {
            assertTrue(select("version[0]").isEmpty());
            assertTrue(select("version[]").isEmpty());
            assertTrue(select("version[{a:1}]").isEmpty());
        }

        @Test
        @DisplayName("多命中文档序：Attributes[].Name 按容器自然顺序")
        void documentOrder() {
            List<Tag> hits = select("Attributes[].Name");
            assertEquals(2, hits.size());
            assertEquals("generic.movement_speed", ((StringTag) hits.get(0)).data);
            assertEquals("generic.max_health", ((StringTag) hits.get(1)).data);
        }

        @Test
        @DisplayName("ByteArray 元素寻址：data[0] → ByteTag(1)；data[] → 2 命中")
        void byteArrayElements() {
            List<Tag> one = select("data[0]");
            assertEquals(1, one.size());
            assertEquals(1, ((ByteTag) one.get(0)).getData().intValue());
            assertEquals(2, select("data[]").size());
        }

        @Test
        @DisplayName("matches：至少一个命中")
        void matches() {
            assertTrue(NbtPath.compile("Items[0].tag").matches(root));
            assertFalse(NbtPath.compile("Items[5]").matches(root));
        }
    }

    // ==================== set：示例表 #13~#15、#23、#26~#28 ====================

    @Nested
    @DisplayName("set 写入矩阵（写严格：0 命中默认 fail-fast）")
    class Set {

        @Test
        @DisplayName("#13 单命中替换")
        void case13() {
            NbtPath.compile("Items[0].tag.display.Name").set(root, new StringTag("", "§c红钻"));
            assertEquals("§c红钻", ((StringTag) select("Items[0].tag.display.Name").get(0)).data);
        }

        @Test
        @DisplayName("#14 多命中全替换")
        void case14() {
            NbtPath.compile("Items[].Count").set(root, new ByteTag("", 1));
            List<Tag> hits = select("Items[].Count");
            assertEquals(2, hits.size());
            assertEquals(1, ((ByteTag) hits.get(0)).getData().intValue());
            assertEquals(1, ((ByteTag) hits.get(1)).getData().intValue());
        }

        @Test
        @DisplayName("#15 0 命中默认抛 NbtModifyException（开放问题 #4 建议默认值）")
        void case15() {
            assertThrows(NbtModifyException.class,
                    () -> NbtPath.compile("Items[5]").set(root, new IntTag("", 1)));
        }

        @Test
        @DisplayName("#23 类型校验拒绝 IntTag→旧 ByteTag；withTypeCheck(false) 放行")
        void case23() {
            assertThrows(NbtPathTypeException.class,
                    () -> NbtPath.compile("Items[0].Count").set(root, new IntTag("", 64)));
            NbtPath.compile("Items[0].Count")
                    .set(root, new IntTag("", 64), NbtWriteOption.DEFAULT.withTypeCheck(false));
            Tag tag = select("Items[0].Count").get(0);
            assertInstanceOf(IntTag.class, tag);
            assertEquals(64, ((IntTag) tag).getData().intValue());
        }

        @Test
        @DisplayName("#26 中间节点非 Compound → 0 命中抛 NbtModifyException")
        void case26() {
            assertThrows(NbtModifyException.class,
                    () -> NbtPath.compile("Items[0].id.x").set(root, new IntTag("", 1)));
        }

        @Test
        @DisplayName("#27 setObject String 直传 → 推断 StringTag")
        void case27() {
            NbtPath.compile("Items[0].tag.display.Name").setObject(root, "铁剑");
            assertEquals("铁剑", ((StringTag) select("Items[0].tag.display.Name").get(0)).data);
        }

        @Test
        @DisplayName("#28 setObject 32 → autoFit 适配旧 ByteTag → ByteTag(32)")
        void case28() {
            NbtPath.compile("Items[0].Count").setObject(root, 32);
            Tag tag = select("Items[0].Count").get(0);
            assertInstanceOf(ByteTag.class, tag);
            assertEquals(32, ((ByteTag) tag).getData().intValue());
        }

        @Test
        @DisplayName("autoFit 失败（300 不入 byte）→ NbtTypeMismatchException")
        void autoFitFailure() {
            assertThrows(NbtTypeMismatchException.class,
                    () -> NbtPath.compile("Items[0].Count").setObject(root, 300));
        }

        @Test
        @DisplayName("autoFit 关闭 + 类型不符 → NbtPathTypeException")
        void autoFitDisabled() {
            assertThrows(NbtPathTypeException.class,
                    () -> NbtPath.compile("Items[0].Count")
                            .setObject(root, 32, NbtWriteOption.DEFAULT.withAutoFit(false)));
        }

        @Test
        @DisplayName("setObject null → 等价 delete")
        void setObjectNullDeletes() {
            NbtPath.compile("version").setObject(root, null);
            assertTrue(select("version").isEmpty());
        }

        @Test
        @DisplayName("createPath：全键段路径自动建父 Compound")
        void createPathBuilds() {
            NbtPath.compile("a.b.c").setObject(root, "x", NbtWriteOption.DEFAULT.withCreatePath(true));
            assertTrue(root.contains("a"));
            assertEquals("x", ((StringTag) select("a.b.c").get(0)).data);
        }

        @Test
        @DisplayName("createPath：尾段非键段（下标）拒绝")
        void createPathRejectsIndexTail() {
            assertThrows(NbtModifyException.class, () -> NbtPath.compile("Items[5]")
                    .setObject(root, 1, NbtWriteOption.DEFAULT.withCreatePath(true)));
        }

        @Test
        @DisplayName("createPath：根过滤不匹配拒绝（根过滤是对目标树的断言）")
        void createPathRejectsRootFilterMismatch() {
            assertThrows(NbtModifyException.class, () -> NbtPath.compile("{version:999}.newKey")
                    .setObject(root, 1, NbtWriteOption.DEFAULT.withCreatePath(true)));
        }

        @Test
        @DisplayName("对根自身 set → NbtModifyException（无父容器）")
        void setRootRejected() {
            assertThrows(NbtModifyException.class,
                    () -> NbtPath.compile("").set(root, new IntTag("", 1)));
        }

        @Test
        @DisplayName("IntArray 元素 set 按原元素类型收窄")
        void intArrayElementSet() {
            NbtPath.compile("custom.Payload[0]").set(root, new IntTag("", 9));
            IntArrayTag payload = (IntArrayTag) ((CompoundTag) root.get("custom")).get("Payload");
            assertEquals(9, payload.data[0]);
        }

        @Test
        @DisplayName("ByteArray 元素 set 收窄为 byte")
        void byteArrayElementSet() {
            NbtPath.compile("data[0]").set(root, new IntTag("", 5));
            assertEquals(5, ((ByteArrayTag) root.get("data")).data[0]);
        }
    }

    // ==================== insert：示例表 #16、#17 ====================

    @Nested
    @DisplayName("insert 插入矩阵（只作用于 ListTag）")
    class Insert {

        @Test
        @DisplayName("#16 Items 末尾追加")
        void case16() {
            CompoundTag extra = new CompoundTag("").putString("id", "minecraft:gold_ingot");
            NbtPath.compile("Items").insert(root, extra);
            ListTag<?> items = (ListTag<?>) root.get("Items");
            assertEquals(3, items.size());
            assertEquals("minecraft:gold_ingot",
                    ((CompoundTag) items.get(2)).getString("id"));
        }

        @Test
        @DisplayName("#17 Items[1] 下标 1 插入，原元素后移")
        void case17() {
            CompoundTag extra = new CompoundTag("").putString("id", "NEW");
            NbtPath.compile("Items[1]").insert(root, extra);
            ListTag<?> items = (ListTag<?>) root.get("Items");
            assertEquals(3, items.size());
            assertEquals("NEW", ((CompoundTag) items.get(1)).getString("id"));
            assertEquals("minecraft:iron_ingot", ((CompoundTag) items.get(2)).getString("id"));
        }

        @Test
        @DisplayName("负索引插入：Items[-1] 插在原最后元素位置")
        void negativeIndexInsert() {
            CompoundTag extra = new CompoundTag("").putString("id", "NEW");
            NbtPath.compile("Items[-1]").insert(root, extra);
            ListTag<?> items = (ListTag<?>) root.get("Items");
            assertEquals(3, items.size());
            assertEquals("NEW", ((CompoundTag) items.get(1)).getString("id"));
            assertEquals("minecraft:iron_ingot", ((CompoundTag) items.get(2)).getString("id"));
        }

        @Test
        @DisplayName("下标越界默认抛；withAppend(true) 改为追加")
        void outOfBounds() {
            CompoundTag extra = new CompoundTag("").putString("id", "NEW");
            NbtPath items5 = NbtPath.compile("Items[5]");
            assertThrows(NbtModifyException.class, () -> items5.insert(root, extra));
            new NbtPathWriter(new NbtPathMatcher())
                    .insert(root, items5, extra, NbtWriteOption.DEFAULT.withAppend(true));
            assertEquals(3, ((ListTag<?>) root.get("Items")).size());
        }

        @Test
        @DisplayName("目标非 ListTag → NbtPathTypeException")
        void notAList() {
            assertThrows(NbtPathTypeException.class,
                    () -> NbtPath.compile("version").insert(root, new IntTag("", 1)));
        }

        @Test
        @DisplayName("0 命中 → NbtModifyException（insert 不支持 createPath）")
        void zeroHit() {
            assertThrows(NbtModifyException.class,
                    () -> NbtPath.compile("nope").insert(root, new IntTag("", 1)));
        }

        @Test
        @DisplayName("insertObject：Map 推断为 CompoundTag 后插入")
        void insertObjectInfers() {
            NbtPath.compile("Items").insertObject(root, Map.of("id", "minecraft:gold_ingot"));
            ListTag<?> items = (ListTag<?>) root.get("Items");
            assertEquals(3, items.size());
            assertEquals("minecraft:gold_ingot", ((CompoundTag) items.get(2)).getString("id"));
        }
    }

    // ==================== merge：示例表 #18、#29 ====================

    @Nested
    @DisplayName("merge 深合并矩阵（仅 Compound）")
    class Merge {

        @Test
        @DisplayName("#18 深合并：同名 Compound 递归、异类型键覆盖、无关键保留")
        void case18() {
            CompoundTag patch = new CompoundTag("")
                    .put("display", new CompoundTag("")
                            .put("Lore", new ListTag<Tag>().add(new StringTag("", "新Lore"))));
            NbtPath.compile("Items[0].tag").merge(root, patch);
            CompoundTag display = (CompoundTag) ((CompoundTag) ((ListTag<?>) root.get("Items"))
                    .get(0)).getCompound("tag").get("display");
            assertEquals("§b钻石", ((StringTag) display.get("Name")).data, "无关键 Name 应保留");
            assertEquals("新Lore", ((StringTag) ((ListTag<?>) display.get("Lore")).get(0)).data,
                    "异类型键 Lore 应覆盖");
        }

        @Test
        @DisplayName("标量键覆盖")
        void scalarOverwrite() {
            NbtPath.compile("").merge(root, new CompoundTag("").putInt("version", 2));
            assertEquals(2, ((IntTag) root.get("version")).getData().intValue());
        }

        @Test
        @DisplayName("目标非 Compound → NbtModifyException")
        void notCompound() {
            assertThrows(NbtModifyException.class,
                    () -> NbtPath.compile("Items[0].id").merge(root, new CompoundTag("").putInt("x", 1)));
        }

        @Test
        @DisplayName("#29 mergeObject：Map 递归推断后合并")
        void case29() {
            NbtPath.compile("custom").mergeObject(root, Map.of("rev", 2, "note", "x"));
            CompoundTag custom = (CompoundTag) root.get("custom");
            assertEquals(2, ((IntTag) custom.get("rev")).getData().intValue());
            assertEquals("x", ((StringTag) custom.get("note")).data);
            assertEquals(1, ((IntTag) custom.get("weird.key")).getData().intValue(), "原有键保留");
        }

        @Test
        @DisplayName("0 命中默认抛；createPath 开启时自动建")
        void zeroHitAndCreatePath() {
            assertThrows(NbtModifyException.class,
                    () -> NbtPath.compile("fresh").merge(root, new CompoundTag("").putInt("n", 1)));
            new NbtPathWriter(new NbtPathMatcher()).merge(root, NbtPath.compile("fresh"),
                    new CompoundTag("").putInt("n", 1), NbtWriteOption.DEFAULT.withCreatePath(true));
            assertEquals(1, ((IntTag) ((CompoundTag) root.get("fresh")).get("n")).getData().intValue());
        }

        @Test
        @DisplayName("合并值拷贝：merge 后修改 patch 不影响树（防共享引用）")
        void patchCopied() {
            CompoundTag patch = new CompoundTag("").putInt("n", 1);
            NbtPath.compile("custom").merge(root, patch);
            patch.putInt("extra", 9);
            CompoundTag custom = (CompoundTag) root.get("custom");
            assertFalse(custom.getTags().containsKey("extra"), "树不应看到 patch 后续修改");
        }
    }

    // ==================== delete：示例表 #19、#20 ====================

    @Nested
    @DisplayName("delete 删除矩阵（幂等）")
    class Delete {

        @Test
        @DisplayName("#19 过滤定位删键：iron 的 Damage 消失、其余保留")
        void case19() {
            NbtPath.compile("Items[{id:\"minecraft:iron_ingot\"}].Damage").delete(root);
            CompoundTag iron = (CompoundTag) ((ListTag<?>) root.get("Items")).get(1);
            assertFalse(iron.getTags().containsKey("Damage"));
            assertTrue(iron.getTags().containsKey("Count"));
        }

        @Test
        @DisplayName("#20 删除 Items[0].tag 整个子树")
        void case20() {
            NbtPath.compile("Items[0].tag").delete(root);
            CompoundTag diamond = (CompoundTag) ((ListTag<?>) root.get("Items")).get(0);
            assertFalse(diamond.getTags().containsKey("tag"));
            assertTrue(diamond.getTags().containsKey("id"));
        }

        @Test
        @DisplayName("幂等：重复 delete 0 命中静默成功")
        void idempotent() {
            assertDoesNotThrow(() -> NbtPath.compile("version").delete(root));
            assertDoesNotThrow(() -> NbtPath.compile("version").delete(root));
            assertTrue(select("version").isEmpty());
        }

        @Test
        @DisplayName("List 元素删除：Items[1] → 剩 1 个")
        void listElement() {
            NbtPath.compile("Items[1]").delete(root);
            ListTag<?> items = (ListTag<?>) root.get("Items");
            assertEquals(1, items.size());
            assertEquals("minecraft:diamond", ((CompoundTag) items.get(0)).getString("id"));
        }

        @Test
        @DisplayName("[] 全删：Items[] → 空列表")
        void deleteAll() {
            NbtPath.compile("Items[]").delete(root);
            assertEquals(0, ((ListTag<?>) root.get("Items")).size());
        }

        @Test
        @DisplayName("数组元素不支持 delete → NbtPathTypeException（M1 限制）")
        void arrayElementRejected() {
            assertThrows(NbtPathTypeException.class,
                    () -> NbtPath.compile("custom.Payload[0]").delete(root));
        }

        @Test
        @DisplayName("对根自身 delete → NbtModifyException")
        void rootRejected() {
            assertThrows(NbtModifyException.class, () -> NbtPath.compile("").delete(root));
        }
    }

    // ==================== selectSingle：示例表 #22 ====================

    @Nested
    @DisplayName("selectSingle 恰一读取")
    class Single {

        @Test
        @DisplayName("#22 恰一个命中 → Optional 有值")
        void exactlyOne() {
            Optional<Tag> hit = NbtPath.compile("Items[0].id").selectSingle(root);
            assertTrue(hit.isPresent());
            assertEquals("minecraft:diamond", ((StringTag) hit.get()).data);
        }

        @Test
        @DisplayName("#22 多命中 → NbtPathNotFoundException")
        void multipleHits() {
            assertThrows(NbtPathNotFoundException.class,
                    () -> NbtPath.compile("Items[].id").selectSingle(root));
        }

        @Test
        @DisplayName("0 命中 → Optional.empty（不抛）")
        void zeroHit() {
            assertTrue(NbtPath.compile("Items[5].id").selectSingle(root).isEmpty());
        }
    }

    // ==================== NbtAPI 门面 + 推断糖 + CompoundTarget ====================

    @Nested
    @DisplayName("NbtAPI 门面（含 v1.2 目标类型推断糖的成功/CCE/var 退化）")
    class ApiFacade {

        private final NbtAPI api = new NbtAPI(new NbtPathParser(), new NbtPathMatcher(),
                new NbtPathWriter(new NbtPathMatcher()));

        @Test
        @DisplayName("compile 走 LRU 缓存：同表达式同实例")
        void compileCache() {
            assertSame(api.compile("Items[0].id"), api.compile("Items[0].id"));
        }

        @Test
        @DisplayName("get(Tag,String) → 全命中 Tag 列表")
        void getList() {
            List<Tag> hits = api.get(root, "Items[].Count");
            assertEquals(2, hits.size());
        }

        @Test
        @DisplayName("推断糖成功：String name = api.get(root, compile(...))")
        void inferenceSugarSuccess() {
            String name = api.get(root, api.compile("Items[0].tag.display.Name"));
            assertEquals("§b钻石", name);
        }

        @Test
        @DisplayName("推断糖 CCE：目标类型与实际不符 → ClassCastException（fail-fast）")
        void inferenceSugarCce() {
            NbtPath path = api.compile("Items[0].tag.display.Name");
            assertThrows(ClassCastException.class, () -> {
                Integer bad = api.get(root, path);
            });
        }

        @Test
        @DisplayName("推断糖 var 退化：var 接收 → Object 通用解包，永不 CCE")
        void inferenceSugarVarFallback() {
            var value = api.get(root, api.compile("Items[0].tag.display.Name"));
            assertEquals("§b钻石", value);
            var number = api.get(root, api.compile("version"));
            assertEquals(1, number);
        }

        @Test
        @DisplayName("推断糖 0 命中 → null")
        void inferenceSugarZeroHit() {
            String miss = api.get(root, api.compile("nope.nope"));
            assertNull(miss);
        }

        @Test
        @DisplayName("#30 get(root, path, Integer.class)：ByteTag 无损宽化为 64")
        void case30() {
            Integer count = api.get(root, "Items[0].Count", Integer.class);
            assertEquals(64, count);
        }

        @Test
        @DisplayName("get(Tag,String,Class) 0 命中 → null")
        void typedZeroHit() {
            assertNull(api.get(root, "nope", Integer.class));
        }

        @Test
        @DisplayName("find：命中 present / 0 命中 empty")
        void find() {
            Optional<String> name = api.find(root, "Items[0].tag.display.Name");
            assertTrue(name.isPresent());
            assertEquals("§b钻石", name.get());
            assertTrue(api.find(root, "nope.nope").isEmpty());
        }

        @Test
        @DisplayName("便捷原生族：命中值 / 0 命中零值")
        void convenienceReaders() {
            assertEquals("§b钻石", api.getString(root, "Items[0].tag.display.Name"));
            assertEquals(1, api.getInt(root, "version"));
            assertEquals(0.1, api.getDouble(root, "Attributes[0].Amount"), 0.0);
            assertTrue(api.getBoolean(root, "Items[0].Count"), "64b 非零为真（开放问题 #7）");
            assertNull(api.getString(root, "nope"));
            assertEquals(0, api.getInt(root, "nope"));
            assertEquals(0.0, api.getDouble(root, "nope"));
            assertFalse(api.getBoolean(root, "nope"));
        }

        @Test
        @DisplayName("setDefaultCoerceMode(LENIENT)：便捷族降级为零值而非抛出")
        void lenientMode() {
            try {
                api.setDefaultCoerceMode(CoerceMode.LENIENT);
                assertNull(api.getString(root, "version"), "LENIENT 下非 String → null");
                assertEquals(0, api.getInt(root, "Items[0].tag.display.Name"), "LENIENT 下非数值 → 0");
            } finally {
                api.setDefaultCoerceMode(CoerceMode.STRICT);
            }
        }

        @Test
        @DisplayName("STRICT 默认：便捷族类型不符抛 NbtPathException")
        void strictModeThrows() {
            assertThrows(NbtPathException.class, () -> api.getString(root, "version"));
        }

        @Test
        @DisplayName("门面写入族：set/insert/merge/delete")
        void facadeWrites() {
            api.set(root, "version", 2);
            assertEquals(2, ((IntTag) root.get("version")).getData().intValue());

            api.insert(root, "Items", Map.of("id", "minecraft:gold_ingot"));
            assertEquals(3, ((ListTag<?>) root.get("Items")).size());

            api.merge(root, "custom", Map.of("rev", 1));
            assertEquals(1, ((IntTag) ((CompoundTag) root.get("custom")).get("rev")).getData().intValue());

            api.delete(root, "version");
            assertFalse(root.getTags().containsKey("version"));
        }

        @Test
        @DisplayName("CompoundTarget：of(root) 目标式操作（就地修改）")
        void compoundTarget() {
            NbtTarget target = api.of(root);
            assertEquals(1, target.get("Items[0].id").size());
            target.set("version", 9);
            assertEquals(9, ((IntTag) root.get("version")).getData().intValue());
            target.insert("Items", Map.of("id", "NEW"));
            assertEquals(3, ((ListTag<?>) root.get("Items")).size());
            target.merge("custom", Map.of("rev", 5));
            assertEquals(5, ((IntTag) ((CompoundTag) root.get("custom")).get("rev")).getData().intValue());
            target.delete("data");
            assertFalse(root.getTags().containsKey("data"));
        }
    }

    // ==================== 并发求值（M1 验收标准 ④） ====================

    @Nested
    @DisplayName("NbtPath 并发求值（不可变对象多线程共享）")
    class Concurrency {

        @Test
        @DisplayName("8 线程 × 200 轮并发 select 同一 NbtPath 实例：结果一致、无异常")
        void concurrentSelect() throws Exception {
            NbtPath path = NbtPath.compile("Items[{id:\"minecraft:diamond\"}].tag.display.Name");
            int threads = 8;
            int rounds = 200;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
            try {
                for (int t = 0; t < threads; t++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < rounds; i++) {
                                List<Tag> hits = path.select(root);
                                if (hits.size() != 1 || !"§b钻石".equals(((StringTag) hits.get(0)).data)) {
                                    errors.add(new AssertionError("并发求值结果错误，轮次 " + i));
                                    return;
                                }
                            }
                        } catch (Throwable e) {
                            errors.add(e);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(20, TimeUnit.SECONDS), "并发求值超时");
            } finally {
                pool.shutdownNow();
            }
            assertTrue(errors.isEmpty(), () -> "并发求值出现异常: " + errors.get(0));
        }
    }
}
