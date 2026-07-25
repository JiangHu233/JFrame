package io.github.JiangHu.jframe.data;

import cn.nukkit.plugin.Plugin;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import io.github.JiangHu.jframe.data.adapter.SaveFieldAdapter;
import io.github.JiangHu.jframe.data.annotation.SaveField;
import io.github.JiangHu.jframe.data.core.MetadataCache;
import io.github.JiangHu.jframe.data.exception.DataException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DataSaver} 单元测试。
 * <p>
 * 覆盖场景：基本往返、别名、嵌套对象、集合、required 缺失、transient 跳过、
 * 父类继承、loadInto 回填、SaveIdentifiable 自动命名、子目录创建、JSON 字符串互转。
 */
@DisplayName("DataSaver 保存工具测试")
class DataSaverTest {

    private MetadataCache cache;
    private DataSaver saver;
    private File tempRoot;

    @BeforeEach
    void setUp() throws Exception {
        cache = new MetadataCache();
        saver = new DataSaver(cache);
        tempRoot = Files.createTempDirectory("jframe-data-test").toFile();
        saver.setRootDir(tempRoot);
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteRecursively(tempRoot);
    }

    // ========== 测试夹具类 ==========

    /** 简单玩家数据：基本类型 + 别名 */
    static class PlayerData {
        @SaveField(value = "player_name", required = true)
        String name;

        @SaveField
        int level;

        @SaveField
        double health;

        @SaveField
        boolean vip;

        // 未标注的字段 → 不应被保存
        String transientCache = "should-not-save";

        public PlayerData() {
        }

        PlayerData(String name, int level, double health, boolean vip) {
            this.name = name;
            this.level = level;
            this.health = health;
            this.vip = vip;
        }
    }

    /** 含 transient 字段 */
    static class WithTransient {
        @SaveField
        String id;

        @SaveField
        transient String runtimeOnly = "runtime";

        public WithTransient() {
        }
    }

    /** 嵌套对象 */
    static class Location {
        @SaveField
        String world;

        @SaveField
        int x;

        @SaveField
        int y;

        public Location() {
        }

        Location(String world, int x, int y) {
            this.world = world;
            this.x = x;
            this.y = y;
        }
    }

    static class PlayerWithNested {
        @SaveField
        String name;

        @SaveField
        Location home;

        public PlayerWithNested() {
        }
    }

    /** 集合字段 */
    static class CollectionHolder {
        @SaveField
        List<String> tags;

        @SaveField
        Map<String, Integer> stats;

        @SaveField
        List<Location> waypoints;

        public CollectionHolder() {
        }
    }

    /** 父类继承 */
    static class BaseCreature {
        @SaveField
        String type;

        public BaseCreature() {
        }
    }

    static class Monster extends BaseCreature {
        @SaveField
        int damage;

        public Monster() {
        }
    }

    /** 实现 SaveIdentifiable */
    static class IdentifiableData implements SaveIdentifiable {
        @SaveField
        String name;

        private final String key;

        public IdentifiableData() {
            this.key = "default";
        }

        IdentifiableData(String key, String name) {
            this.key = key;
            this.name = name;
        }

        @Override
        public String saveKey() {
            return key;
        }
    }

    // ========== 基本往返 ==========

    @Nested
    @DisplayName("基本序列化/反序列化")
    class BasicRoundTrip {

        @Test
        @DisplayName("保存后加载应还原所有字段值")
        void saveAndLoadRestoresFields() {
            PlayerData original = new PlayerData("Steve", 42, 19.5, true);

            saver.save(original, "player");

            PlayerData loaded = saver.load(PlayerData.class, "player");

            assertNotNull(loaded);
            assertEquals("Steve", loaded.name);
            assertEquals(42, loaded.level);
            assertEquals(19.5, loaded.health);
            assertTrue(loaded.vip);
        }

        @Test
        @DisplayName("未标注字段不应出现在 JSON 中")
        void unannotatedFieldNotSaved() {
            PlayerData original = new PlayerData("Alex", 10, 20.0, false);

            String json = saver.toJson(original);

            assertFalse(json.contains("transientCache"), "未标注字段不应出现在 JSON 中");
            assertFalse(json.contains("should-not-save"));
        }

        @Test
        @DisplayName("未标注字段加载后保持默认值")
        void unannotatedFieldKeepsDefault() {
            saver.save(new PlayerData("Bob", 1, 1.0, false), "p");

            PlayerData loaded = saver.load(PlayerData.class, "p");

            // transientCache 是未标注字段，加载后应为新实例的默认值
            assertEquals("should-not-save", loaded.transientCache);
        }
    }

    // ========== 别名 ==========

    @Nested
    @DisplayName("字段别名")
    class AliasTest {

        @Test
        @DisplayName("value 别名应作为 JSON 键")
        void aliasUsedAsJsonKey() {
            PlayerData original = new PlayerData("Steve", 1, 1.0, false);

            String json = saver.toJson(original);

            assertTrue(json.contains("\"player_name\""), "应使用别名 player_name");
            assertFalse(json.contains("\"name\""), "不应使用字段名 name");
        }

        @Test
        @DisplayName("未指定 value 时使用字段名作为 JSON 键")
        void defaultAliasIsFieldName() {
            PlayerData original = new PlayerData("Steve", 99, 1.0, false);

            String json = saver.toJson(original);

            assertTrue(json.contains("\"level\""));
            assertTrue(json.contains("\"health\""));
        }
    }

    // ========== required 校验 ==========

    @Nested
    @DisplayName("required 必需字段校验")
    class RequiredTest {

        @Test
        @DisplayName("required 字段缺失时应抛出 DataException")
        void missingRequiredThrows() {
            // 构造缺少 player_name 的 JSON
            String json = """
                    {
                      "level": 5,
                      "health": 10.0,
                      "vip": false
                    }
                    """;

            DataException ex = assertThrows(DataException.class,
                    () -> saver.fromJson(json, PlayerData.class));

            assertTrue(ex.getMessage().contains("player_name"));
            assertTrue(ex.getMessage().contains("必需"));
        }

        @Test
        @DisplayName("required 字段为 null 时应抛出 DataException")
        void nullRequiredThrows() {
            String json = """
                    {
                      "player_name": null,
                      "level": 5,
                      "health": 10.0,
                      "vip": false
                    }
                    """;

            assertThrows(DataException.class,
                    () -> saver.fromJson(json, PlayerData.class));
        }

        @Test
        @DisplayName("非 required 字段缺失时保持默认值")
        void missingOptionalKeepsDefault() {
            String json = """
                    {
                      "player_name": "Steve"
                    }
                    """;

            PlayerData loaded = saver.fromJson(json, PlayerData.class);

            assertEquals("Steve", loaded.name);
            assertEquals(0, loaded.level); // 默认值
            assertEquals(0.0, loaded.health);
            assertFalse(loaded.vip);
        }
    }

    // ========== transient ==========

    @Test
    @DisplayName("transient 字段不应被保存")
    void transientFieldNotSaved() {
        WithTransient obj = new WithTransient();
        obj.id = "abc";

        String json = saver.toJson(obj);

        assertTrue(json.contains("\"id\""));
        assertFalse(json.contains("runtimeOnly"), "transient 字段不应被保存");
        assertFalse(json.contains("runtime"));
    }

    // ========== 嵌套对象 ==========

    @Nested
    @DisplayName("嵌套对象")
    class NestedTest {

        @Test
        @DisplayName("嵌套对象应递归序列化")
        void nestedObjectSerialized() {
            PlayerWithNested player = new PlayerWithNested();
            player.name = "Steve";
            player.home = new Location("world", 100, 64);

            String json = saver.toJson(player);

            assertTrue(json.contains("\"home\""));
            assertTrue(json.contains("\"world\""));
            assertTrue(json.contains("100"));
        }

        @Test
        @DisplayName("嵌套对象应递归反序列化")
        void nestedObjectDeserialized() {
            saver.save(createNestedPlayer(), "nested");

            PlayerWithNested loaded = saver.load(PlayerWithNested.class, "nested");

            assertNotNull(loaded.home);
            assertEquals("world", loaded.home.world);
            assertEquals(100, loaded.home.x);
            assertEquals(64, loaded.home.y);
        }

        private PlayerWithNested createNestedPlayer() {
            PlayerWithNested p = new PlayerWithNested();
            p.name = "Steve";
            p.home = new Location("world", 100, 64);
            return p;
        }
    }

    // ========== 集合 ==========

    @Nested
    @DisplayName("集合与容器")
    class CollectionTest {

        @Test
        @DisplayName("List 字段应正确序列化/反序列化")
        void listFieldRoundTrip() {
            CollectionHolder holder = new CollectionHolder();
            holder.tags = new ArrayList<>(Arrays.asList("a", "b", "c"));

            saver.save(holder, "col");
            CollectionHolder loaded = saver.load(CollectionHolder.class, "col");

            assertNotNull(loaded.tags);
            assertEquals(3, loaded.tags.size());
            assertEquals("a", loaded.tags.get(0));
            assertEquals("c", loaded.tags.get(2));
        }

        @Test
        @DisplayName("Map 字段应正确序列化/反序列化")
        void mapFieldRoundTrip() {
            CollectionHolder holder = new CollectionHolder();
            holder.stats = new LinkedHashMap<>();
            holder.stats.put("kills", 10);
            holder.stats.put("deaths", 3);

            saver.save(holder, "col");
            CollectionHolder loaded = saver.load(CollectionHolder.class, "col");

            assertNotNull(loaded.stats);
            assertEquals(10, loaded.stats.get("kills"));
            assertEquals(3, loaded.stats.get("deaths"));
        }

        @Test
        @DisplayName("嵌套对象的 List 应递归处理每个元素")
        void listOfNestedObjects() {
            CollectionHolder holder = new CollectionHolder();
            holder.waypoints = new ArrayList<>();
            holder.waypoints.add(new Location("spawn", 0, 0));
            holder.waypoints.add(new Location("base", 100, 64));

            saver.save(holder, "col");
            CollectionHolder loaded = saver.load(CollectionHolder.class, "col");

            assertNotNull(loaded.waypoints);
            assertEquals(2, loaded.waypoints.size());
            assertEquals("spawn", loaded.waypoints.get(0).world);
            assertEquals("base", loaded.waypoints.get(1).world);
            assertEquals(64, loaded.waypoints.get(1).y);
        }
    }

    // ========== 父类继承 ==========

    @Test
    @DisplayName("父类的 @SaveField 字段应被保存")
    void parentClassFieldsSaved() {
        Monster monster = new Monster();
        monster.type = "zombie";
        monster.damage = 15;

        String json = saver.toJson(monster);

        assertTrue(json.contains("\"type\""), "父类字段 type 应被保存");
        assertTrue(json.contains("\"damage\""));
    }

    @Test
    @DisplayName("父类的 @SaveField 字段应被加载")
    void parentClassFieldsLoaded() {
        Monster monster = new Monster();
        monster.type = "skeleton";
        monster.damage = 20;

        saver.save(monster, "mob");
        Monster loaded = saver.load(Monster.class, "mob");

        assertEquals("skeleton", loaded.type);
        assertEquals(20, loaded.damage);
    }

    // ========== loadInto 回填 ==========

    @Nested
    @DisplayName("loadInto 回填已有实例")
    class LoadIntoTest {

        @Test
        @DisplayName("loadInto 应将数据填充到已有实例")
        void loadIntoBackfillsExistingInstance() {
            PlayerData original = new PlayerData("Steve", 50, 18.0, true);
            saver.save(original, "p");

            // 创建一个已有实例（模拟被其他系统持有的对象）
            PlayerData existing = new PlayerData();

            saver.loadInto(existing, "p");

            assertEquals("Steve", existing.name);
            assertEquals(50, existing.level);
            assertEquals(18.0, existing.health);
            assertTrue(existing.vip);
        }

        @Test
        @DisplayName("fromJsonInto 应将 JSON 字符串填充到已有实例")
        void fromJsonIntoBackfills() {
            PlayerData existing = new PlayerData();

            String json = """
                    {
                      "player_name": "Alex",
                      "level": 7,
                      "health": 20.0,
                      "vip": true
                    }
                    """;

            saver.fromJsonInto(existing, json);

            assertEquals("Alex", existing.name);
            assertEquals(7, existing.level);
            assertEquals(20.0, existing.health);
            assertTrue(existing.vip);
        }
    }

    // ========== SaveIdentifiable 自动命名 ==========

    @Nested
    @DisplayName("SaveIdentifiable 自动命名")
    class SaveIdentifiableTest {

        @Test
        @DisplayName("save(obj) 应使用 saveKey() 作为文件名")
        void saveUsesSaveKey() {
            IdentifiableData data = new IdentifiableData("player-001", "Steve");

            saver.save(data);

            File expected = new File(tempRoot, "player-001.json");
            assertTrue(expected.exists(), "应创建以 saveKey 命名的文件");
        }

        @Test
        @DisplayName("未实现 SaveIdentifiable 时 save(obj) 应抛出异常")
        void saveWithoutIdentifiableThrows() {
            PlayerData data = new PlayerData("Steve", 1, 1.0, false);

            assertThrows(DataException.class, () -> saver.save(data));
        }

        @Test
        @DisplayName("saveKey 返回 null 时应抛出异常")
        void nullSaveKeyThrows() {
            IdentifiableData data = new IdentifiableData(null, "Steve");

            assertThrows(DataException.class, () -> saver.save(data));
        }
    }

    // ========== 文件路径与子目录 ==========

    @Nested
    @DisplayName("文件路径解析")
    class FilePathTest {

        @Test
        @DisplayName("相对路径含子目录时应自动创建父目录")
        void subdirectoryAutoCreated() {
            PlayerData data = new PlayerData("Steve", 1, 1.0, false);

            saver.save(data, "players/steve");

            File expected = new File(tempRoot, "players/steve.json");
            assertTrue(expected.exists(), "应在子目录下创建文件");
            assertTrue(expected.getParentFile().exists(), "父目录应被自动创建");
        }

        @Test
        @DisplayName("save(obj, File) 应使用绝对路径文件")
        void saveToAbsoluteFile() {
            PlayerData data = new PlayerData("Steve", 1, 1.0, false);
            File absolute = new File(tempRoot, "custom/path/data.json");

            saver.save(data, absolute);

            assertTrue(absolute.exists());
        }

        @Test
        @DisplayName("load 不存在的文件应返回 null（鲁棒性：缺失≠失败）")
        void loadNonExistentReturnsNull() {
            assertNull(saver.load(PlayerData.class, "nonexistent"),
                    "文件不存在时 load 应返回 null 而非抛出异常");
        }

        @Test
        @DisplayName("未设置 rootDir 时相对路径操作应抛出异常")
        void noRootDirThrows() {
            DataSaver noRootSaver = new DataSaver(cache);

            assertThrows(DataException.class,
                    () -> noRootSaver.save(new PlayerData("x", 1, 1, false), "p"));
        }
    }

    // ========== JSON 字符串互转 ==========

    @Nested
    @DisplayName("JSON 字符串互转")
    class JsonStringTest {

        @Test
        @DisplayName("toJson 应返回格式化的 JSON 字符串")
        void toJsonReturnsFormattedString() {
            PlayerData data = new PlayerData("Steve", 1, 1.0, false);

            String json = saver.toJson(data);

            assertNotNull(json);
            assertTrue(json.contains("{"));
            assertTrue(json.contains("}"));
        }

        @Test
        @DisplayName("fromJson 应从 JSON 字符串还原对象")
        void fromJsonRestoresObject() {
            String json = """
                    {
                      "player_name": "Test",
                      "level": 99,
                      "health": 5.5,
                      "vip": true
                    }
                    """;

            PlayerData loaded = saver.fromJson(json, PlayerData.class);

            assertEquals("Test", loaded.name);
            assertEquals(99, loaded.level);
            assertEquals(5.5, loaded.health);
            assertTrue(loaded.vip);
        }

        @Test
        @DisplayName("toJson → fromJson 往返应保持数据一致")
        void toJsonFromJsonRoundTrip() {
            PlayerData original = new PlayerData("Round", 77, 13.3, true);

            String json = saver.toJson(original);
            PlayerData restored = saver.fromJson(json, PlayerData.class);

            assertEquals(original.name, restored.name);
            assertEquals(original.level, restored.level);
            assertEquals(original.health, restored.health);
            assertEquals(original.vip, restored.vip);
        }
    }

    // ========== 字段级适配器夹具 ==========

    /** 二维坐标（不标注 @SaveField，仅作为字段类型） */
    static class Pos {
        int x;
        int y;

        public Pos() {
        }

        Pos(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pos)) return false;
            Pos pos = (Pos) o;
            return x == pos.x && y == pos.y;
        }

        @Override
        public int hashCode() {
            return 31 * x + y;
        }

        @Override
        public String toString() {
            return "(" + x + "," + y + ")";
        }
    }

    /** 将 Pos 序列化为 "x,y" 字符串，而非默认的嵌套对象 */
    static class PosAdapter implements SaveFieldAdapter<Pos> {
        @Override
        public JsonElement toJson(Pos pos) {
            if (pos == null) {
                return JsonNull.INSTANCE;
            }
            return new JsonPrimitive(pos.x + "," + pos.y);
        }

        @Override
        public Pos fromJson(JsonElement json) {
            if (json == null || json.isJsonNull()) {
                return null;
            }
            String[] parts = json.getAsString().split(",");
            return new Pos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }
    }

    /** 含适配器字段与普通字段混合的玩家数据 */
    static class PlayerWithPos {
        @SaveField
        String name;

        @SaveField(adapter = PosAdapter.class)
        Pos location;

        @SaveField
        int score;

        public PlayerWithPos() {
        }
    }

    @Nested
    @DisplayName("字段级自定义适配器")
    class FieldAdapterTest {

        @Test
        @DisplayName("适配器将字段序列化为自定义格式（字符串而非对象）")
        void adapterUsesCustomFormat() {
            PlayerWithPos player = new PlayerWithPos();
            player.name = "Steve";
            player.location = new Pos(10, 20);
            player.score = 88;

            String json = saver.toJson(player);
            // 去除空白以兼容 pretty-printing 格式
            String compact = json.replaceAll("\\s+", "");

            // location 应为 "10,20" 字符串，而非 {"x":10,"y":20} 对象
            assertTrue(compact.contains("\"location\":\"10,20\""),
                    "适配器应将 Pos 序列化为 'x,y' 字符串，实际: " + json);
            assertFalse(compact.contains("\"location\":{"),
                    "location 不应为对象格式，实际: " + json);
        }

        @Test
        @DisplayName("适配器往返：序列化后反序列化值正确还原")
        void adapterRoundTrip() {
            PlayerWithPos original = new PlayerWithPos();
            original.name = "Alex";
            original.location = new Pos(-5, 100);
            original.score = 42;

            String json = saver.toJson(original);
            PlayerWithPos restored = saver.fromJson(json, PlayerWithPos.class);

            assertEquals(original.name, restored.name);
            assertEquals(original.location, restored.location);
            assertEquals(original.score, restored.score);
        }

        @Test
        @DisplayName("适配器字段为 null 时正确处理")
        void adapterNullField() {
            PlayerWithPos original = new PlayerWithPos();
            original.name = "Null";
            original.location = null;
            original.score = 0;

            String json = saver.toJson(original);
            PlayerWithPos restored = saver.fromJson(json, PlayerWithPos.class);

            assertNull(restored.location, "null 字段经适配器往返后应仍为 null");
            assertEquals("Null", restored.name);
        }

        @Test
        @DisplayName("适配器字段与普通字段共存互不干扰")
        void adapterCoexistsWithNormalField() {
            PlayerWithPos original = new PlayerWithPos();
            original.name = "Hero";
            original.location = new Pos(3, 4);
            original.score = 99;

            String json = saver.toJson(original);
            PlayerWithPos restored = saver.fromJson(json, PlayerWithPos.class);

            // 普通字段（name/score）走 Gson 默认序列化
            assertEquals("Hero", restored.name);
            assertEquals(99, restored.score);
            // 适配器字段（location）走自定义序列化
            assertEquals(new Pos(3, 4), restored.location);
        }

        @Test
        @DisplayName("文件保存/加载场景下适配器生效")
        void adapterFileRoundTrip() {
            PlayerWithPos original = new PlayerWithPos();
            original.name = "FileTest";
            original.location = new Pos(7, 8);
            original.score = 77;

            saver.save(original, "pos_player.json");
            PlayerWithPos restored = saver.load(PlayerWithPos.class, "pos_player.json");

            assertEquals(original.name, restored.name);
            assertEquals(original.location, restored.location);
            assertEquals(original.score, restored.score);
        }
    }

    // ========== 子路径拼接与路径导航 ==========

    @Nested
    @DisplayName("子路径拼接与路径导航")
    class SubPathAndNavigationTest {

        @Test
        @DisplayName("sub() 应拼接出正确的子根路径")
        void subAppendsPath() {
            DataSaver players = saver.sub("players");

            assertEquals(new File(tempRoot, "players"), players.getRootDir());
        }

        @Test
        @DisplayName("sub() 支持多级路径拼接")
        void subMultipleSegments() {
            DataSaver vip = saver.sub("players", "vip", "2024");

            // 逐级构造期望值，避免不同平台路径分隔符差异
            File expected = new File(tempRoot, "players");
            expected = new File(expected, "vip");
            expected = new File(expected, "2024");
            assertEquals(expected, vip.getRootDir());
        }

        @Test
        @DisplayName("parent() / isRoot() 应正确反映层级关系")
        void parentAndIsRoot() {
            assertTrue(saver.isRoot());
            assertNull(saver.parent());

            DataSaver players = saver.sub("players");
            assertFalse(players.isRoot());
            assertSame(saver, players.parent());

            DataSaver vip = players.sub("vip");
            assertSame(players, vip.parent());
        }

        @Test
        @DisplayName("子保存器应将文件写入子目录，不污染父级")
        void subSaverWritesToSubDir() {
            DataSaver players = saver.sub("players");

            players.save(new PlayerData("Steve", 1, 1.0, false), "steve");

            assertTrue(new File(tempRoot, "players/steve.json").exists(),
                    "文件应写入子目录");
            assertFalse(new File(tempRoot, "steve.json").exists(),
                    "父级目录不应出现同名文件");
        }

        @Test
        @DisplayName("子保存器应从子目录加载文件")
        void subSaverLoadsFromSubDir() {
            DataSaver players = saver.sub("players");
            players.save(new PlayerData("Alex", 5, 10.0, true), "alex");

            PlayerData loaded = players.load(PlayerData.class, "alex");

            assertEquals("Alex", loaded.name);
            assertEquals(5, loaded.level);
            assertTrue(loaded.vip);
        }

        @Test
        @DisplayName("parent() 应返回上级保存器，可加载上级目录的文件")
        void parentReturnsUpAndCanLoad() {
            // 在根目录存放"全局模板"
            saver.save(new PlayerData("Global", 1, 20.0, false), "template");

            DataSaver players = saver.sub("players");
            // 显式向上导航到根目录后加载
            PlayerData loaded = players.parent().load(PlayerData.class, "template");

            assertEquals("Global", loaded.name);
            assertEquals(20.0, loaded.health);
        }

        @Test
        @DisplayName("链式 parent().parent() 可多级向上导航")
        void parentChainMultiLevel() {
            // 仅根目录有 default.json
            saver.save(new PlayerData("RootDefault", 1, 20.0, false), "default");

            DataSaver vip = saver.sub("players").sub("vip");

            // 连续两次 parent() 回到根目录
            PlayerData loaded = vip.parent().parent().load(PlayerData.class, "default");

            assertEquals("RootDefault", loaded.name);
        }

        @Test
        @DisplayName("parent() 后可再次 sub() 向下导航")
        void parentThenSubRedescend() {
            DataSaver vip = saver.sub("players").sub("vip");

            // 先回到 players，再下钻到 vip，路径应一致
            DataSaver playersAgain = vip.parent();
            assertEquals(saver.sub("players").getRootDir(), playersAgain.getRootDir());

            DataSaver vipAgain = playersAgain.sub("vip");
            assertEquals(vip.getRootDir(), vipAgain.getRootDir());
        }

        @Test
        @DisplayName("未设置 rootDir 时 sub() 应抛出异常")
        void subWithoutRootThrows() {
            DataSaver noRoot = new DataSaver(cache);

            assertThrows(DataException.class, () -> noRoot.sub("players"));
        }

        @Test
        @DisplayName("root() 应从任意深度回到根保存器")
        void rootReturnsToRoot() {
            DataSaver vip = saver.sub("players").sub("vip");

            assertSame(saver, vip.root());
            assertTrue(vip.root().isRoot());
        }

        @Test
        @DisplayName("root() 在根保存器上返回自身")
        void rootOnRootReturnsSelf() {
            assertSame(saver, saver.root());
        }

        @Test
        @DisplayName("root() 回到根后可加载根目录文件")
        void rootCanLoadRootFile() {
            saver.save(new PlayerData("RootCfg", 1, 20.0, false), "config");

            DataSaver vip = saver.sub("players").sub("vip");
            PlayerData loaded = vip.root().load(PlayerData.class, "config");

            assertEquals("RootCfg", loaded.name);
        }

        @Test
        @DisplayName("子保存器调用 setRootDir 应抛出异常（不能篡改根路径）")
        void subSaverSetRootDirThrows() {
            DataSaver players = saver.sub("players");

            assertThrows(DataException.class,
                    () -> players.setRootDir(new File(tempRoot, "hacked")));
        }
    }

    @Nested
    @DisplayName("forPlugin 跨插件独立根保存器")
    class ForPluginTest {

        /**
         * 用动态代理构造一个仅实现 {@code getDataFolder()} 的 Plugin 桩，
         * 其余方法返回默认值（测试不关心）。
         */
        private Plugin fakePlugin(File dataFolder) {
            return (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                    Plugin.class.getClassLoader(),
                    new Class<?>[]{Plugin.class},
                    (proxy, method, args) -> {
                        if ("getDataFolder".equals(method.getName())) {
                            return dataFolder;
                        }
                        return null;
                    });
        }

        @Test
        @DisplayName("forPlugin 返回以插件数据目录为根的保存器")
        void forPluginSetsRootToPluginDataFolder() throws Exception {
            File pluginDir = Files.createTempDirectory("fake-plugin").toFile();
            Plugin plugin = fakePlugin(pluginDir);

            DataSaver pluginSaver = saver.forPlugin(plugin);

            assertEquals(pluginDir, pluginSaver.getRootDir());
        }

        @Test
        @DisplayName("forPlugin 返回的保存器是独立的根保存器")
        void forPluginReturnsIndependentRoot() throws Exception {
            File pluginDir = Files.createTempDirectory("fake-plugin").toFile();
            Plugin plugin = fakePlugin(pluginDir);

            DataSaver pluginSaver = saver.forPlugin(plugin);

            assertTrue(pluginSaver.isRoot());
            assertNotSame(saver, pluginSaver);
            // 与原 saver 的根路径不同（互不干扰）
            assertNotEquals(saver.getRootDir(), pluginSaver.getRootDir());
        }

        @Test
        @DisplayName("forPlugin(null) 应抛出 DataException")
        void forPluginNullThrows() {
            assertThrows(DataException.class, () -> saver.forPlugin(null));
        }

        @Test
        @DisplayName("不同插件的保存器数据相互隔离")
        void differentPluginsDataIsolated() throws Exception {
            File dirA = Files.createTempDirectory("plugin-a").toFile();
            File dirB = Files.createTempDirectory("plugin-b").toFile();
            DataSaver saverA = saver.forPlugin(fakePlugin(dirA));
            DataSaver saverB = saver.forPlugin(fakePlugin(dirB));

            PlayerData dataA = new PlayerData("Alice", 10, 20.0, true);
            PlayerData dataB = new PlayerData("Bob", 99, 50.0, false);
            saverA.save(dataA, "config");
            saverB.save(dataB, "config");

            // 各自只读到自己的数据
            PlayerData loadedA = saverA.load(PlayerData.class, "config");
            PlayerData loadedB = saverB.load(PlayerData.class, "config");
            assertEquals("Alice", loadedA.name);
            assertEquals("Bob", loadedB.name);

            // 文件确实落在各自目录下
            assertTrue(new File(dirA, "config.json").exists());
            assertTrue(new File(dirB, "config.json").exists());
        }

        @Test
        @DisplayName("forPlugin 返回的保存器可独立使用 sub/parent 导航")
        void forPluginSaverSupportsNavigation() throws Exception {
            File pluginDir = Files.createTempDirectory("fake-plugin").toFile();
            DataSaver pluginSaver = saver.forPlugin(fakePlugin(pluginDir));

            DataSaver sub = pluginSaver.sub("players");
            PlayerData data = new PlayerData("Nav", 5, 10.0, false);
            sub.save(data, "p1");

            // 通过导航能正确加载
            PlayerData loaded = pluginSaver.sub("players").load(PlayerData.class, "p1");
            assertEquals("Nav", loaded.name);
            // root() 回到插件根
            assertSame(pluginSaver, sub.root());
        }
    }

    // ========== 文件存在性判断 ==========

    @Nested
    @DisplayName("exists 文件存在性判断")
    class ExistsTest {

        @Test
        @DisplayName("exists(String) 文件不存在时返回 false")
        void existsStringReturnsFalseWhenAbsent() {
            assertFalse(saver.exists("players/steve"));
        }

        @Test
        @DisplayName("exists(String) 保存后返回 true")
        void existsStringReturnsTrueAfterSave() {
            saver.save(new PlayerData("Steve", 1, 1.0, false), "players/steve");

            assertTrue(saver.exists("players/steve"));
            assertTrue(new File(tempRoot, "players/steve.json").exists());
        }

        @Test
        @DisplayName("exists(File) 绝对路径文件判断")
        void existsFileAbsolute() {
            File absolute = new File(tempRoot, "custom/data.json");
            assertFalse(saver.exists(absolute));

            saver.save(new PlayerData("Steve", 1, 1.0, false), absolute);
            assertTrue(saver.exists(absolute));
        }

        @Test
        @DisplayName("exists((File) null) 返回 false 而非抛异常")
        void existsNullFileReturnsFalse() {
            assertFalse(saver.exists((File) null));
        }

        @Test
        @DisplayName("exists(obj) 判断 SaveIdentifiable 序列化目标文件")
        void existsIdentifiable() {
            IdentifiableData data = new IdentifiableData("player-001", "Steve");
            assertFalse(saver.exists(data));

            saver.save(data);
            assertTrue(saver.exists(data));
            assertTrue(new File(tempRoot, "player-001.json").exists());
        }

        @Test
        @DisplayName("exists(非 SaveIdentifiable) 应抛出 DataException")
        void existsNonIdentifiableThrows() {
            PlayerData data = new PlayerData("Steve", 1, 1.0, false);

            assertThrows(DataException.class, () -> saver.exists(data));
        }

        // ----- fileOf：通过对象 saveKey 解析对应文件 -----

        @Test
        @DisplayName("fileOf(obj) 返回 saveKey 对应的存储文件路径")
        void fileOfResolvesSaveKeyToFile() {
            IdentifiableData data = new IdentifiableData("player-001", "Steve");

            File file = saver.fileOf(data);

            assertEquals(new File(tempRoot, "player-001.json"), file);
        }

        @Test
        @DisplayName("fileOf(obj) 与 save(obj) 写入的文件一致")
        void fileOfMatchesSaveTarget() {
            IdentifiableData data = new IdentifiableData("player-001", "Steve");

            saver.save(data);

            // save(obj) 落盘的文件正是 fileOf(obj) 指向的文件
            assertTrue(saver.fileOf(data).exists());
            assertEquals(saver.fileOf(data), new File(tempRoot, "player-001.json"));
        }

        @Test
        @DisplayName("fileOf(obj) 在子保存器下解析到子目录")
        void fileOfRespectsSubSaver() {
            DataSaver sub = saver.sub("players");

            File file = sub.fileOf(new IdentifiableData("001", "Steve"));

            assertEquals(new File(tempRoot, "players/001.json"), file);
        }

        @Test
        @DisplayName("fileOf(非 SaveIdentifiable) 应抛出 DataException")
        void fileOfNonIdentifiableThrows() {
            PlayerData data = new PlayerData("Steve", 1, 1.0, false);

            assertThrows(DataException.class, () -> saver.fileOf(data));
        }
    }

    // ========== 加载或新建 ==========

    @Nested
    @DisplayName("loadOrSave 加载或新建")
    class LoadOrSaveTest {

        @Test
        @DisplayName("文件不存在时用默认值创建并落盘")
        void loadOrSaveCreatesDefaultWhenAbsent() {
            PlayerData def = new PlayerData("Default", 1, 20.0, false);

            PlayerData result = saver.loadOrSave(PlayerData.class, "config", () -> def);

            // 返回默认值
            assertEquals("Default", result.name);
            // 文件已落盘
            assertTrue(saver.exists("config"));
            assertTrue(new File(tempRoot, "config.json").exists());
        }

        @Test
        @DisplayName("文件存在时读取已有数据，不调用 supplier（惰性）")
        void loadOrSaveLoadsExistingWhenPresent() {
            // 先写入已有数据
            saver.save(new PlayerData("Existing", 99, 50.0, true), "config");

            AtomicInteger callCount = new AtomicInteger(0);
            PlayerData result = saver.loadOrSave(PlayerData.class, "config", () -> {
                callCount.incrementAndGet();
                return new PlayerData("Default", 1, 1.0, false);
            });

            // 读取的是已有数据
            assertEquals("Existing", result.name);
            assertEquals(99, result.level);
            // supplier 不应被调用（惰性）
            assertEquals(0, callCount.get());
        }

        @Test
        @DisplayName("首次 loadOrSave 落盘后，再次调用应读取而非覆盖")
        void loadOrSaveSecondCallReadsBack() {
            PlayerData def = new PlayerData("Default", 1, 20.0, false);
            saver.loadOrSave(PlayerData.class, "config", () -> def);

            // 修改默认值，第二次调用应读取首次落盘的内容
            PlayerData result = saver.loadOrSave(PlayerData.class, "config",
                    () -> new PlayerData("Other", 2, 2.0, true));

            assertEquals("Default", result.name);
            assertEquals(1, result.level);
        }

        @Test
        @DisplayName("loadOrSave(File, supplier) 绝对路径版本")
        void loadOrSaveAbsoluteFile() {
            File absolute = new File(tempRoot, "custom/cfg.json");

            PlayerData result = saver.loadOrSave(PlayerData.class, absolute,
                    () -> new PlayerData("Abs", 5, 10.0, false));

            assertEquals("Abs", result.name);
            assertTrue(absolute.exists());
        }

        @Test
        @DisplayName("defaultSupplier 为 null 时抛出 DataException")
        void loadOrSaveNullSupplierThrows() {
            assertThrows(DataException.class,
                    () -> saver.loadOrSave(PlayerData.class, "config", null));
        }

        @Test
        @DisplayName("defaultSupplier 返回 null 时抛出 DataException")
        void loadOrSaveNullReturningSupplierThrows() {
            assertThrows(DataException.class,
                    () -> saver.loadOrSave(PlayerData.class, "config", () -> null));
        }

        // ----- 自动命名重载：loadOrSave(Class, Supplier) 依 saveKey 决定文件名 -----

        @Test
        @DisplayName("自动命名：文件不存在时用默认值创建并落盘，文件名 = saveKey")
        void autoNameCreatesDefaultWhenAbsent() {
            IdentifiableData result = saver.loadOrSave(new IdentifiableData("player-001", "Steve"));

            // 返回默认值
            assertEquals("Steve", result.name);
            // 文件名由 saveKey() 决定
            assertTrue(saver.exists("player-001"));
            assertTrue(new File(tempRoot, "player-001.json").exists());
        }

        @Test
        @DisplayName("自动命名：文件已存在时读取已有数据（默认对象被忽略）")
        void autoNameLoadsExistingWhenPresent() {
            // 先落盘已有数据
            saver.save(new IdentifiableData("player-001", "Existing"), "player-001");

            // 文件已存在，传入的默认对象不会被落盘，读取的是已有数据
            IdentifiableData result = saver.loadOrSave(new IdentifiableData("player-001", "Default"));

            assertEquals("Existing", result.name);
        }

        @Test
        @DisplayName("自动命名：首次落盘后再次调用应读取而非覆盖")
        void autoNameSecondCallReadsBack() {
            saver.loadOrSave(new IdentifiableData("player-001", "Steve"));

            IdentifiableData result = saver.loadOrSave(new IdentifiableData("player-001", "Other"));

            assertEquals("Steve", result.name);
        }

        @Test
        @DisplayName("自动命名：defaultObj 为 null 时抛出 DataException")
        void autoNameNullObjThrows() {
            assertThrows(DataException.class,
                    () -> saver.loadOrSave((IdentifiableData) null));
        }
    }

    // ========== 工具方法 ==========

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
