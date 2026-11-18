package io.github.JiangHu.jframe.db;

import io.github.JiangHu.jframe.db.support.InMemoryRedisStore;
import io.github.JiangHu.jframe.db.support.InMemoryRelationalStore;
import io.github.JiangHu.jframe.serialize.SaveIdentifiable;
import io.github.JiangHu.jframe.serialize.annotation.SaveField;
import io.github.JiangHu.jframe.serialize.exception.DataException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DbAPI} 注册/注销/令牌/生命周期测试。
 */
@DisplayName("DbAPI 注册与生命周期测试")
class DbApiTest {

    static class Item implements SaveIdentifiable {
        @SaveField
        String id;

        public Item() {
        }

        public Item(String id) {
            this.id = id;
        }

        @Override
        public String saveKey() {
            return id;
        }
    }

    @Test
    @DisplayName("匿名注册返回令牌实例；重复注册同一实例幂等")
    void anonymousTokenRegistration() {
        DbAPI db = new DbAPI();
        InMemoryRelationalStore store = new InMemoryRelationalStore();
        assertSame(store, db.registerRelational(store));

        // 幂等：再次以匿名注册同一实例不抛错
        assertSame(store, db.registerRelational(store));
    }

    @Test
    @DisplayName("命名注册可按名查找；同名冲突抛异常")
    void namedRegistrationAndConflict() {
        DbAPI db = new DbAPI();
        InMemoryRelationalStore a = new InMemoryRelationalStore();
        InMemoryRelationalStore b = new InMemoryRelationalStore();

        db.registerRelational("主库", a);
        assertSame(a, db.relational("主库"));

        assertThrows(DataException.class, () -> db.registerRelational("主库", b));
        assertThrows(DataException.class, () -> db.relational("不存在的名"));
    }

    @Test
    @DisplayName("类型不匹配查找抛异常（redis 名查 relational）")
    void typeMismatchLookup() {
        DbAPI db = new DbAPI();
        db.registerRedis("缓存", new InMemoryRedisStore());
        assertThrows(DataException.class, () -> db.relational("缓存"));
    }

    @Test
    @DisplayName("cache 死期必填：缺 TTL build 期 fail-fast")
    void cacheTtlRequired() {
        DbAPI db = new DbAPI();
        InMemoryRelationalStore sql = new InMemoryRelationalStore();
        InMemoryRedisStore redis = new InMemoryRedisStore();
        db.registerRelational(sql);
        db.registerRedis(redis);

        assertThrows(DataException.class, () ->
                db.manage(Item.class).relational(sql, "items").cache(redis, null).build());
        assertThrows(DataException.class, () ->
                db.manage(Item.class).relational(sql, "items").cache(redis, Duration.ZERO).build());
        // 正常路径可建
        assertNotNull(db.manage(Item.class).relational(sql, "items")
                .cache(redis, Duration.ofMinutes(1)).build());
    }

    @Test
    @DisplayName("缺 relational build 期 fail-fast")
    void relationalRequired() {
        DbAPI db = new DbAPI();
        assertThrows(DataException.class, () -> db.manage(Item.class).build());
    }

    @Test
    @DisplayName("注销后 manager 操作抛 DataException；重复注销幂等返回 false")
    void unregisterInvalidatesManagers() {
        DbAPI db = new DbAPI();
        InMemoryRelationalStore sql = new InMemoryRelationalStore();
        db.registerRelational("主库", sql);
        DataManager<Item> mgr = db.manage(Item.class).relational("主库", "items").build();
        mgr.save(new Item("a"));

        assertTrue(db.unregister("主库"));
        DataException ex = assertThrows(DataException.class, () -> mgr.get("a"));
        assertTrue(ex.getMessage().contains("已注销"));
        assertFalse(db.unregister("主库")); // 已不存在
        assertThrows(DataException.class, () -> db.relational("主库"));
    }

    @Test
    @DisplayName("shutdown 注销全部通道")
    void shutdownUnregistersAll() {
        DbAPI db = new DbAPI();
        db.registerRelational("库", new InMemoryRelationalStore());
        db.registerRedis("缓存", new InMemoryRedisStore());
        db.shutdown();
        assertThrows(DataException.class, () -> db.relational("库"));
        assertThrows(DataException.class, () -> db.redis("缓存"));
    }
}
