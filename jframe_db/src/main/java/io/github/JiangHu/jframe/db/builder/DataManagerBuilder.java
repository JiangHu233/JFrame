package io.github.JiangHu.jframe.db.builder;

import io.github.JiangHu.jframe.db.DataManager;
import io.github.JiangHu.jframe.db.DbAPI;
import io.github.JiangHu.jframe.db.cache.LocalCache;
import io.github.JiangHu.jframe.db.mapping.EntityMapping;
import io.github.JiangHu.jframe.db.mapping.RowCodec;
import io.github.JiangHu.jframe.db.spi.RelationalStore;
import io.github.JiangHu.jframe.db.spi.RelationalTable;
import io.github.JiangHu.jframe.db.spi.RedisStore;
import io.github.JiangHu.jframe.serialize.core.meta.MetadataCache;
import io.github.JiangHu.jframe.serialize.exception.DataException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * DataManager 构建器 — {@code db.manage(实体类)} 的链式装配说明书。
 * <pre>{@code
 * DataManager<PlayerData> players = db.manage(PlayerData.class)
 *         .relational(games, "players")              // 必需：RelationalStore（或注册名）+ 表名
 *         .keyColumn("uuid")                          // 可选（默认 "id"）
 *         .cache(redis, Duration.ofMinutes(10))       // 可选：Redis 层——死期必填（无默认值）
 *         .localCache()                               // 可选：本机层——默认无期限
 *         .build();                                   // 自动建表/补列
 * }</pre>
 * <p>
 * 校验（build 期 fail-fast）：relational 必填；cache 的死期必填
 * （死期是悬空免疫与外部改库最终一致的唯一保底，框架不代填默认值）。
 *
 * @param <T> 实体类型
 */
public final class DataManagerBuilder<T> {

    private final DbAPI db;
    private final Class<T> type;

    private RelationalStore store;
    private String storeName;
    private String tableName;
    private String keyColumn = "id";

    private RedisStore cacheRedis;
    private long cacheTtlSeconds = -1;
    private boolean sliding;

    private boolean localEnabled;
    private long localTtlMillis = -1;
    private int maxEntries = -1;

    /** @param db   门面（内部依赖注入） */
    public DataManagerBuilder(DbAPI db, Class<T> type) {
        this.db = db;
        this.type = type;
    }

    /** 主存：RelationalStore 实例（注册返回的令牌）。 */
    public DataManagerBuilder<T> relational(RelationalStore store, String table) {
        this.store = store;
        this.storeName = db.storeName(store);
        this.tableName = table;
        return this;
    }

    /** 主存：按注册名查找（跨插件场景）。 */
    public DataManagerBuilder<T> relational(String storeName, String table) {
        return relational(db.relational(storeName), table);
    }

    /** 主键列名（默认 "id"）。 */
    public DataManagerBuilder<T> keyColumn(String column) {
        this.keyColumn = column;
        return this;
    }

    /** Redis 缓存层（死期必填；TTL 是悬空免疫与外部改库最终一致的保底）。 */
    public DataManagerBuilder<T> cache(RedisStore redis, Duration ttl) {
        this.cacheRedis = redis;
        this.cacheTtlSeconds = requireTtl(ttl);
        return this;
    }

    /** Redis 缓存层 + 滑动续期（读命中顺延死期：热数据跟随使用存活、冷数据到点消失；
     * 注意会推迟外部改库的可见性，默认关）。 */
    public DataManagerBuilder<T> cache(RedisStore redis, Duration ttl, boolean sliding) {
        this.cacheRedis = redis;
        this.cacheTtlSeconds = requireTtl(ttl);
        this.sliding = sliding;
        return this;
    }

    /** Redis 缓存层（按注册名）。 */
    public DataManagerBuilder<T> cache(String redisName, Duration ttl) {
        return cache(db.redis(redisName), ttl);
    }

    /** Redis 缓存层（按注册名 + 滑动续期）。 */
    public DataManagerBuilder<T> cache(String redisName, Duration ttl, boolean sliding) {
        return cache(db.redis(redisName), ttl, sliding);
    }

    /** 本机内存层：默认无期限（进程即释放权）。 */
    public DataManagerBuilder<T> localCache() {
        this.localEnabled = true;
        return this;
    }

    /** 本机内存层 + TTL（需要外部改库最终一致时显式配置）。 */
    public DataManagerBuilder<T> localCache(Duration ttl) {
        this.localEnabled = true;
        this.localTtlMillis = requirePositive(ttl);
        return this;
    }

    /** 本机内存层 + 容量上限（超出先逐过期再逐最久未访问，防批量场景撑爆内存）。 */
    public DataManagerBuilder<T> localCache(int maxEntries) {
        this.localEnabled = true;
        this.maxEntries = maxEntries;
        return this;
    }

    /** 本机内存层 + TTL + 容量上限。 */
    public DataManagerBuilder<T> localCache(Duration ttl, int maxEntries) {
        this.localEnabled = true;
        this.localTtlMillis = requirePositive(ttl);
        this.maxEntries = maxEntries;
        return this;
    }

    /**
     * 构建：解析实体映射 → ensureTable（主表 + 子表 + 关联表 + ref 表，幂等）→
     * 组装 DataManager（订阅失效广播、注册本地失效路由）。
     */
    public DataManager<T> build() {
        if (store == null || tableName == null || tableName.isEmpty()) {
            throw new DataException("DataManager 构建缺少 .relational(store, 表名)（实体 "
                    + type.getName() + "）");
        }
        MetadataCache cache = db.metadataCache();
        EntityMapping mapping = EntityMapping.of(type, cache, tableName, keyColumn);

        RelationalTable table = store.ensureTable(mapping.mainTable());
        List<RelationalTable> childTables = new ArrayList<>();
        for (EntityMapping.ChildField child : mapping.children()) {
            childTables.add(store.ensureTable(child.table()));
        }
        List<RelationalTable> relationTables = new ArrayList<>();
        List<EntityMapping> refMappings = new ArrayList<>();
        List<RelationalTable> refTables = new ArrayList<>();
        for (EntityMapping.RelationField relation : mapping.relations()) {
            relationTables.add(store.ensureTable(relation.table()));
            EntityMapping refMapping = EntityMapping.of(relation.refEntity(), cache,
                    relation.refTable(), relation.refKeyColumn());
            refMappings.add(refMapping);
            refTables.add(store.ensureTable(refMapping.mainTable()));
        }

        LocalCache local = localEnabled ? new LocalCache(localTtlMillis, maxEntries) : null;
        DataManager<T> manager = new DataManager<>(db, type, mapping, store, storeName, table,
                new RowCodec(db.gson()), childTables, relationTables, refMappings, refTables,
                cacheRedis, cacheTtlSeconds, sliding, local);
        db.trackManager(store, manager);
        return manager;
    }

    private static long requireTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new DataException("cache 层的死期（TTL）必填且必须为正——死期是悬空数据免疫" +
                    "与外部改库最终一致的唯一保底，框架不代填默认值（也不要传 0/负数表示永久）");
        }
        return ttl.toSeconds() < 1 ? 1 : ttl.toSeconds();
    }

    private static long requirePositive(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new DataException("localCache 的 TTL 必须为正（无期限请用无参 localCache()）");
        }
        return ttl.toMillis();
    }
}
