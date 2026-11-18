package io.github.JiangHu.jframe.db;

import io.github.JiangHu.jframe.db.annotation.ChildTable;
import io.github.JiangHu.jframe.db.annotation.Column;
import io.github.JiangHu.jframe.db.annotation.Indexed;
import io.github.JiangHu.jframe.db.annotation.RelationTable;
import io.github.JiangHu.jframe.db.support.InMemoryRedisStore;
import io.github.JiangHu.jframe.db.support.InMemoryRelationalStore;
import io.github.JiangHu.jframe.serialize.SaveIdentifiable;
import io.github.JiangHu.jframe.serialize.annotation.SaveField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DataManager} 组合层全套测试：类型往返、子表/关联表、三层缓存读路径、
 * 双实例失效广播、Redis 故障降级、异步方法、getBatch/findBy/forEach。
 */
@DisplayName("DataManager 组合层测试")
class DataManagerTest {

    // ========== 夹具 ==========

    static class Title implements SaveIdentifiable {
        @SaveField(required = true)
        String id;

        @SaveField
        String display;

        public Title() {
        }

        public Title(String id, String display) {
            this.id = id;
            this.display = display;
        }

        @Override
        public String saveKey() {
            return id;
        }
    }

    static class Achieve {
        @SaveField
        String code;

        @SaveField
        int score;

        public Achieve() {
        }

        public Achieve(String code, int score) {
            this.code = code;
            this.score = score;
        }
    }

    static class Player implements SaveIdentifiable {
        @SaveField(required = true)
        String uuid;

        @SaveField @Indexed @Column(length = 50)
        String name;

        @SaveField
        int level;

        @SaveField
        double health;

        @SaveField @Column(precision = 10, scale = 2)
        BigDecimal balance;

        @SaveField
        Instant lastLogin;

        @SaveField
        UUID token;

        @SaveField
        Map<String, Integer> stats;                 // JSON 列

        @SaveField @ChildTable
        List<String> mails;                          // 标量子表

        @SaveField @ChildTable
        List<Achieve> achieves;                      // 复杂子表

        @SaveField @RelationTable(refTable = "titles", refEntity = Title.class)
        List<Title> titles;

        public Player() {
        }

        Player(String uuid, String name, int level) {
            this.uuid = uuid;
            this.name = name;
            this.level = level;
        }

        @Override
        public String saveKey() {
            return uuid;
        }
    }

    /** 故障注入版 Redis：所有操作抛异常，验证降级 */
    static class BrokenRedisStore extends InMemoryRedisStore {
        @Override
        public String get(String key) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public void set(String key, String value, long ttlSeconds) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public boolean delete(String key) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public void publish(String channel, String message) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public io.github.JiangHu.jframe.db.spi.Subscription subscribe(
                String channel, java.util.function.BiConsumer<String, String> listener) {
            return () -> {
            };
        }
    }

    private static Player fullPlayer(String uuid, String name) {
        Player p = new Player(uuid, name, 42);
        p.health = 19.5;
        p.balance = new BigDecimal("88.50");
        p.lastLogin = Instant.ofEpochMilli(1731926400000L);
        p.token = UUID.nameUUIDFromBytes(uuid.getBytes());
        p.stats = Map.of("kills", 10, "deaths", 3);
        p.mails = List.of("welcome", "gift");
        p.achieves = List.of(new Achieve("first_kill", 10), new Achieve("builder", 5));
        p.titles = List.of(new Title("vip", "VIP"), new Title("hero", "Hero"));
        return p;
    }

    @Nested
    @DisplayName("纯 SQL 模式（无缓存层）")
    class PureSqlMode {

        @Test
        @DisplayName("全字段类型往返（标量/Instant/UUID/BigDecimal/JSON列/子表/关联表）")
        void fullRoundTrip() {
            DbAPI db = new DbAPI();
            InMemoryRelationalStore sql = new InMemoryRelationalStore();
            db.registerRelational(sql);

            DataManager<Title> titles = db.manage(Title.class).relational(sql, "titles").build();
            titles.save(new Title("vip", "VIP"));
            titles.save(new Title("hero", "Hero"));

            DataManager<Player> players = db.manage(Player.class)
                    .relational(sql, "players").keyColumn("uuid").build();
            Player original = fullPlayer("p1", "Steve");
            players.save(original);

            Player loaded = players.get("p1");
            assertNotNull(loaded);
            assertEquals("Steve", loaded.name);
            assertEquals(42, loaded.level);
            assertEquals(19.5, loaded.health);
            assertEquals(new BigDecimal("88.50"), loaded.balance);
            assertEquals(Instant.ofEpochMilli(1731926400000L), loaded.lastLogin);
            assertEquals(original.token, loaded.token);
            assertEquals(10, loaded.stats.get("kills"));
            assertEquals(List.of("welcome", "gift"), loaded.mails);
            assertEquals(2, loaded.achieves.size());
            assertEquals("first_kill", loaded.achieves.get(0).code);
            assertEquals(10, loaded.achieves.get(0).score);
            assertEquals(2, loaded.titles.size());
            assertTrue(loaded.titles.stream().anyMatch(t -> "vip".equals(t.id) && "VIP".equals(t.display)));
            assertTrue(loaded.titles.stream().anyMatch(t -> "hero".equals(t.id) && "Hero".equals(t.display)));
        }

        @Test
        @DisplayName("save(entity) 用 saveKey 定位；get 不存在返回 null")
        void saveByKeyAndMiss() {
            DbAPI db = new DbAPI();
            InMemoryRelationalStore sql = new InMemoryRelationalStore();
            db.registerRelational(sql);
            DataManager<Player> players = db.manage(Player.class)
                    .relational(sql, "players").build();

            players.save(new Player("u1", "Alex", 1));
            assertNotNull(players.get("u1"));
            assertNull(players.get("nope"));
        }

        @Test
        @DisplayName("delete 连带清子表与关联行")
        void deleteCascadesChildrenAndRelations() {
            DbAPI db = new DbAPI();
            InMemoryRelationalStore sql = new InMemoryRelationalStore();
            db.registerRelational(sql);
            DataManager<Title> titles = db.manage(Title.class).relational(sql, "titles").build();
            titles.save(new Title("vip", "VIP"));
            DataManager<Player> players = db.manage(Player.class)
                    .relational(sql, "players").build();
            players.save(fullPlayer("p1", "Steve"));

            assertTrue(players.delete("p1"));
            assertNull(players.get("p1"));
            assertFalse(players.exists("p1"));
            assertEquals(0, players.count());
            // 子表/关联表被清空
            players.save(fullPlayer("p1", "Steve2"));
            Player again = players.get("p1");
            assertEquals("Steve2", again.name);
            assertEquals(2, again.mails.size()); // 旧数据未残留（重插后仍 2 条）
        }

        @Test
        @DisplayName("exists/count/findBy/findByIn/getBatch/forEach")
        void querySurface() {
            DbAPI db = new DbAPI();
            InMemoryRelationalStore sql = new InMemoryRelationalStore();
            db.registerRelational(sql);
            DataManager<Player> players = db.manage(Player.class)
                    .relational(sql, "players").keyColumn("uuid").build();
            Player a = fullPlayer("p1", "Steve");
            Player b = fullPlayer("p2", "Alex");
            players.save(a);
            players.save(b);

            assertTrue(players.exists("p1"));
            assertEquals(2, players.count());

            List<Player> byName = players.findBy("name", "Steve");
            assertEquals(1, byName.size());
            assertEquals("p1", byName.get(0).uuid);

            // enum/大小写敏感 name 查询另一个值
            assertEquals(1, players.findByIn("name", List.of("Steve")).size());
            assertEquals(0, players.findBy("name", "Nobody").size());

            Map<String, Player> batch = players.getBatch(List.of("p1", "p2", "p3"));
            assertEquals(2, batch.size());
            assertNotNull(batch.get("p2"));

            final int[] seen = {0};
            players.forEach(1, batchList -> seen[0] += batchList.size());
            assertEquals(2, seen[0]);
        }

        @Test
        @DisplayName("loadOrCreate：缺失创建落库，存在不调用提供器")
        void loadOrCreateSemantics() {
            DbAPI db = new DbAPI();
            InMemoryRelationalStore sql = new InMemoryRelationalStore();
            db.registerRelational(sql);
            DataManager<Player> players = db.manage(Player.class)
                    .relational(sql, "players").build();

            Player created = players.loadOrCreate("p1", () -> new Player("p1", "New", 1));
            assertEquals("New", created.name);
            // 第二次读取已有
            Player loaded = players.loadOrCreate("p1", () -> new Player("p1", "Other", 9));
            assertEquals("New", loaded.name);
        }
    }

    /** 直接操作 SQL 正本的测试辅助表定义（仅关键列） */
    private static io.github.JiangHu.jframe.db.spi.TableDef playersTable() {
        return new io.github.JiangHu.jframe.db.spi.TableDef("players", "uuid", List.of(
                io.github.JiangHu.jframe.db.spi.ColumnDef.key("uuid"),
                io.github.JiangHu.jframe.db.spi.ColumnDef.of("name",
                        io.github.JiangHu.jframe.db.spi.ColumnType.STRING),
                io.github.JiangHu.jframe.db.spi.ColumnDef.of("level",
                        io.github.JiangHu.jframe.db.spi.ColumnType.INT)));
    }

    /** 外部直改正本的测试辅助（绕过 manager，模拟 OA/运维 SQL） */
    private static void externalRename(InMemoryRelationalStore sql, String uuid, String name) {
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("uuid", uuid);
        values.put("name", name);
        sql.ensureTable(playersTable()).upsert(
                new io.github.JiangHu.jframe.db.spi.Row("uuid", values));
    }

    @Nested
    @DisplayName("三层缓存组合模式")
    class CachedMode {

        @Test
        @DisplayName("读穿透回填：SQL → Redis → 本地；写后 Redis+本地刷新")
        void readThroughAndBackfill() {
            DbAPI db = new DbAPI();
            InMemoryRelationalStore sql = new InMemoryRelationalStore();
            InMemoryRedisStore redis = new InMemoryRedisStore();
            db.registerRelational(sql);
            db.registerRedis(redis);

            DataManager<Player> players = db.manage(Player.class)
                    .relational(sql, "players")
                    .cache(redis, Duration.ofMinutes(10))
                    .localCache()
                    .build();

            Player original = fullPlayer("p1", "Steve");
            players.save(original);

            // save 已写 Redis + 本地；get 走本地命中
            Player local = players.get("p1");
            assertEquals("Steve", local.name);

            // Redis 快照可独立解码（键 = __jframe_db__:players:p1）
            String snapshot = redis.get("__jframe_db__:players:p1");
            assertNotNull(snapshot);

            // 直接改 SQL 正本 → 本地缓存命中旧值（未过期）；clearCache 后回源
            externalRename(sql, "p1", "Changed");
            assertEquals("Steve", players.get("p1").name); // 本地缓存生效
            players.clearCache();
            assertNull(redis.get("__jframe_db__:players:p1"));
            assertEquals("Changed", players.get("p1").name); // 回源 SQL 重建
        }

        @Test
        @DisplayName("缓存死期到自动失效（外部改库最终一致）")
        void ttlExpiry() {
            DbAPI db = new DbAPI();
            InMemoryRelationalStore sql = new InMemoryRelationalStore();
            InMemoryRedisStore redis = new InMemoryRedisStore();
            db.registerRelational(sql);
            db.registerRedis(redis);

            DataManager<Player> players = db.manage(Player.class)
                    .relational(sql, "players")
                    .cache(redis, Duration.ofSeconds(60))
                    .build();
            players.save(fullPlayer("p1", "Steve"));

            // SQL 正本被外部改掉，缓存里还是旧值
            externalRename(sql, "p1", "Changed");
            assertEquals("Steve", players.get("p1").name);

            redis.advanceTime(61); // 死期到，缓存蒸发
            assertNull(redis.get("__jframe_db__:players:p1"));
            assertEquals("Changed", players.get("p1").name);
        }

        @Test
        @DisplayName("双实例失效广播：A save → B 本地缓存失效")
        void dualInstanceInvalidation() {
            InMemoryRelationalStore sql = new InMemoryRelationalStore();
            InMemoryRedisStore redis = new InMemoryRedisStore();

            DbAPI dbA = new DbAPI();
            dbA.registerRelational(sql);
            dbA.registerRedis(redis);
            DbAPI dbB = new DbAPI();
            dbB.registerRelational(sql);
            dbB.registerRedis(redis);

            DataManager<Player> a = dbA.manage(Player.class).relational(sql, "players")
                    .cache(redis, Duration.ofMinutes(10)).localCache().build();
            DataManager<Player> b = dbB.manage(Player.class).relational(sql, "players")
                    .cache(redis, Duration.ofMinutes(10)).localCache().build();

            a.save(fullPlayer("p1", "Steve"));
            assertEquals("Steve", b.get("p1").name); // B 回填本地缓存

            // A 改名 → 广播 → B 本地失效 → B 再读拿到新值
            Player renamed = fullPlayer("p1", "SteveV2");
            a.save(renamed);
            assertEquals("SteveV2", b.get("p1").name);
        }

        @Test
        @DisplayName("Redis 故障自动降级直连 SQL（业务不崩）")
        void redisDegradation() {
            DbAPI db = new DbAPI();
            InMemoryRelationalStore sql = new InMemoryRelationalStore();
            db.registerRelational(sql);

            DataManager<Player> players = db.manage(Player.class)
                    .relational(sql, "players")
                    .cache(new BrokenRedisStore(), Duration.ofMinutes(10))
                    .build();

            players.save(fullPlayer("p1", "Steve")); // SQL 成功，Redis 降级
            Player loaded = players.get("p1");       // Redis 挂 → 直连 SQL
            assertNotNull(loaded);
            assertEquals("Steve", loaded.name);
        }

        @Test
        @DisplayName("异步方法完成性与异常传播")
        void asyncMethods() throws Exception {
            DbAPI db = new DbAPI();
            InMemoryRelationalStore sql = new InMemoryRelationalStore();
            db.registerRelational(sql);
            DataManager<Player> players = db.manage(Player.class)
                    .relational(sql, "players").build();

            CompletableFuture<Player> future = players.getAsync("p1");
            assertNull(future.get(5, TimeUnit.SECONDS)); // miss

            players.saveAsync(fullPlayer("p1", "Steve")).get(5, TimeUnit.SECONDS);
            assertEquals("Steve", players.getAsync("p1").get(5, TimeUnit.SECONDS).name);
            assertEquals(1L, players.countAsync().get(5, TimeUnit.SECONDS));

            CompletableFuture<Object> failed = db.executors().supplyAsync(() -> {
                throw new IllegalStateException("boom");
            });
            assertTrue(failed.isCompletedExceptionally() || failed.handle((r, e) -> e).get() != null);
        }
    }
}
