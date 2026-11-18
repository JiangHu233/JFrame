package io.github.JiangHu.jframe.db;

import io.github.JiangHu.jframe.db.cache.LocalCache;
import io.github.JiangHu.jframe.db.mapping.EntityMapping;
import io.github.JiangHu.jframe.db.mapping.RowCodec;
import io.github.JiangHu.jframe.db.spi.RelationalStore;
import io.github.JiangHu.jframe.db.spi.RelationalTable;
import io.github.JiangHu.jframe.db.spi.RedisStore;
import io.github.JiangHu.jframe.db.spi.Row;
import io.github.JiangHu.jframe.db.spi.TransactionalStore;
import io.github.JiangHu.jframe.db.support.DbConstants;
import io.github.JiangHu.jframe.serialize.SaveIdentifiable;
import io.github.JiangHu.jframe.serialize.exception.DataException;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * 组合层实体管理器 — 业务代码日常打交道的服务对象（同步 + 异步双版本）。
 * <p>
 * 读路径（穿透回填）：本地内存 → Redis → SQL → 逐层回填（miss 返回 null）。
 * <p>
 * 写路径（SQL 为准）：SQL upsert（成败以此为准；@ChildTable/@RelationTable 多行写入
 * 在 {@link TransactionalStore} 适配器上包事务）→ Redis set 快照+死期（失败降级：
 * 尝试 delete，仍失败等死期，日志继续）→ 本地刷新 → 发布失效广播（他服失效本地缓存）。
 * <p>
 * 释放权三层：框架缓存键命名空间 {@code __jframe_db__:{table}:{key}} + 死期必填
 * （时间即释放权，崩溃免疫）+ 写时更新 + {@link #clearCache()} 显式清理。
 * <p>
 * Redis 故障降级：组合层内所有 Redis 调用失败被捕获（warning 日志），自动退化为
 * 直连 SQL，业务不崩。异步版本经虚拟线程执行，不卡主线程；回主线程由调用方自理。
 *
 * @param <T> 实体类型
 */
public final class DataManager<T> {

    private static final Logger LOG = Logger.getLogger(DataManager.class.getName());

    /** 已知 Redis 键记录上限（clearCache 的 best-effort 集合；超出后停止记录） */
    private static final int KNOWN_KEYS_CAP = 65536;

    private final DbAPI db;
    private final Class<T> type;
    private final EntityMapping mapping;
    private final RelationalStore store;
    private final String storeName;
    private final RelationalTable table;
    private final RowCodec codec;

    /** 子表句柄（与 ChildField 同序） */
    private final List<RelationalTable> childTables;

    /** 关联表句柄与 ref 实体映射（与 RelationField 同序） */
    private final List<RelationalTable> relationTables;
    private final List<EntityMapping> refMappings;
    private final List<RelationalTable> refTables;

    /** Redis 缓存层（null = 无） */
    private final RedisStore cacheRedis;
    private final long cacheTtlSeconds;
    private final boolean sliding;

    /** 本地内存层（null = 无；默认无期限） */
    private final LocalCache local;

    /** clearCache best-effort 已知键 */
    private final Set<String> knownRedisKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 【内部 API】由 DataManagerBuilder 装配调用。 */
    public DataManager(DbAPI db, Class<T> type, EntityMapping mapping, RelationalStore store,
                String storeName, RelationalTable table, RowCodec codec,
                List<RelationalTable> childTables, List<RelationalTable> relationTables,
                List<EntityMapping> refMappings, List<RelationalTable> refTables,
                RedisStore cacheRedis, long cacheTtlSeconds, boolean sliding, LocalCache local) {
        this.db = db;
        this.type = type;
        this.mapping = mapping;
        this.store = store;
        this.storeName = storeName;
        this.table = table;
        this.codec = codec;
        this.childTables = childTables;
        this.relationTables = relationTables;
        this.refMappings = refMappings;
        this.refTables = refTables;
        this.cacheRedis = cacheRedis;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.sliding = sliding;
        this.local = local;
        if (cacheRedis != null) {
            db.coordinator().ensureSubscribed(cacheRedis);
            db.coordinator().route(mapping.tableName(), this::onInvalidate);
        }
    }

    /** 收到其他实例的失效广播：丢本地缓存 */
    private void onInvalidate(String key) {
        if (local != null) {
            local.invalidate(key);
        }
    }

    // ==================== 读 ====================

    /**
     * 按主键读取实体（本地 → Redis → SQL 穿透回填）。
     *
     * @param key 主键
     * @return 实体；不存在返回 null
     */
    public T get(String key) {
        db.checkStore(store, "get");
        if (local != null) {
            T cached = local.get(key);
            if (cached != null) {
                return cached;
            }
        }
        String redisKey = redisKey(key);
        if (cacheRedis != null) {
            try {
                String json = cacheRedis.get(redisKey);
                if (json != null) {
                    T entity = codec.fromSnapshot(json, type);
                    if (sliding) {
                        try {
                            cacheRedis.expire(redisKey, cacheTtlSeconds);
                        } catch (Exception ignored) {
                            // 续期失败不影响本次命中
                        }
                    }
                    if (local != null) {
                        local.put(key, entity);
                    }
                    return entity;
                }
            } catch (Exception e) {
                degrade("get(redis)", e);
            }
        }
        Row row = table.get(key);
        if (row == null) {
            return null;
        }
        T entity = decode(row);
        backfill(key, entity);
        return entity;
    }

    /**
     * 读取实体；不存在时返回默认值（不落库）。
     *
     * @param key      主键
     * @param fallback 默认值提供器
     * @return 实体或默认值
     */
    public T getOrDefault(String key, Supplier<T> fallback) {
        T value = get(key);
        return value != null ? value : fallback.get();
    }

    /**
     * 读取实体；不存在时用提供器创建、<b>落库</b>并返回（首次生成默认数据的惯用法）。
     *
     * @param key      主键
     * @param provider 默认实体提供器（不能返回 null）
     * @return 实体
     */
    public T loadOrCreate(String key, Supplier<T> provider) {
        T existing = get(key);
        if (existing != null) {
            return existing;
        }
        T created = provider.get();
        if (created == null) {
            throw new DataException("loadOrCreate 的默认实体提供器返回了 null（表 "
                    + mapping.tableName() + "，键 " + key + "）");
        }
        save(key, created);
        return created;
    }

    /**
     * 批量按主键读取（缓存感知：命中层直接用，未命中部分走 SQL 批量并回填）。
     *
     * @param keys 主键集合
     * @return 主键 → 实体（存在者）
     */
    public Map<String, T> getBatch(Collection<String> keys) {
        db.checkStore(store, "getBatch");
        Map<String, T> result = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            T cached = getQuiet(key);
            if (cached != null) {
                result.put(key, cached);
            } else {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            for (Row row : table.getBatch(missing)) {
                T entity = decode(row);
                backfill(row.key(), entity);
                result.put(row.key(), entity);
            }
        }
        return result;
    }

    /** 不触发 SQL 的缓存探测（getBatch 内部用；避免重复 checkStore） */
    private T getQuiet(String key) {
        if (local != null) {
            T cached = local.get(key);
            if (cached != null) {
                return cached;
            }
        }
        if (cacheRedis != null) {
            try {
                String json = cacheRedis.get(redisKey(key));
                if (json != null) {
                    return codec.fromSnapshot(json, type);
                }
            } catch (Exception e) {
                degrade("getBatch(redis)", e);
            }
        }
        return null;
    }

    // ==================== 写 ====================

    /**
     * 保存实体（主键 = {@code saveKey()}）。
     *
     * @param entity 实体（须实现 SaveIdentifiable 且 saveKey 合法）
     */
    public void save(T entity) {
        if (!(entity instanceof SaveIdentifiable identifiable)) {
            throw new DataException("实体 " + type.getName() +
                    " 未实现 SaveIdentifiable，请用 save(key, entity) 显式指定主键");
        }
        save(identifiable.saveKey(), entity);
    }

    /**
     * 保存实体到指定主键：SQL upsert（主行 + 子表重写 + 关联差集维护）→
     * Redis 快照刷新 → 本地刷新 → 失效广播。
     *
     * @param key    主键
     * @param entity 实体
     */
    public void save(String key, T entity) {
        db.checkStore(store, "save");
        if (key == null || key.isEmpty()) {
            throw new DataException("save 的主键不能为空（表 " + mapping.tableName() + "）");
        }
        Runnable write = () -> {
            table.upsert(codec.toMainRow(entity, mapping, key));
            writeChildren(key, entity);
            writeRelations(key, entity);
        };
        if (store instanceof TransactionalStore ts) {
            ts.inTransaction(s -> {
                write.run();
                return null;
            });
        } else {
            write.run();
        }
        afterWrite(key, entity);
    }

    /**
     * 按主键删除（主行 + 子表 + 关联行）；缓存层随之失效并广播。
     *
     * @param key 主键
     * @return 行存在并删除返回 true
     */
    public boolean delete(String key) {
        db.checkStore(store, "delete");
        boolean existed = table.delete(key);
        for (RelationalTable childTable : childTables) {
            for (Row old : childTable.findBy("parent_key", key)) {
                childTable.delete(old.key());
            }
        }
        for (RelationalTable relationTable : relationTables) {
            for (Row old : relationTable.findBy("owner_key", key)) {
                relationTable.delete(old.key());
            }
        }
        if (cacheRedis != null) {
            String redisKey = redisKey(key);
            try {
                cacheRedis.delete(redisKey);
            } catch (Exception e) {
                degrade("delete(redis)", e);
            }
            knownRedisKeys.remove(redisKey);
            db.coordinator().publishInvalidate(cacheRedis, mapping.tableName(), key);
        }
        if (local != null) {
            local.invalidate(key);
        }
        return existed;
    }

    // ==================== 查询 ====================

    /**
     * 按索引列等值查询（直读 SQL，绕过缓存）。
     *
     * @param fieldName 字段名或 @SaveField 别名（须为 @Indexed 标量字段）
     * @param value     查询值（enum 传枚举常量或 name 均可）
     * @return 命中实体列表
     */
    public List<T> findBy(String fieldName, Object value) {
        db.checkStore(store, "findBy");
        EntityMapping.ScalarField scalar = mapping.scalarByName(fieldName)
                .orElseThrow(() -> new DataException("findBy 的字段 " + fieldName + " 不存在于实体 "
                        + type.getName() + "（或为容器字段，不能索引查询）"));
        Object columnValue = toQueryValue(scalar, value);
        List<T> result = new ArrayList<>();
        for (Row row : table.findBy(scalar.meta().alias(), columnValue)) {
            result.add(decode(row));
        }
        return result;
    }

    /** findBy/findByIn 的查询值归一（enum → name 等） */
    private Object toQueryValue(EntityMapping.ScalarField scalar, Object value) {
        return value == null ? null
                : io.github.JiangHu.jframe.db.mapping.TypeMapping.toColumnValue(
                        value, scalar.columnType());
    }

    /**
     * 按索引列 IN 查询（直读 SQL）。
     *
     * @param fieldName 字段名或别名（@Indexed）
     * @param values    查询值集合
     * @return 命中实体列表
     */
    public List<T> findByIn(String fieldName, Collection<?> values) {
        db.checkStore(store, "findByIn");
        EntityMapping.ScalarField scalar = mapping.scalarByName(fieldName)
                .orElseThrow(() -> new DataException("findByIn 的字段 " + fieldName + " 不存在于实体 "
                        + type.getName()));
        List<Object> columnValues = new ArrayList<>(values.size());
        for (Object v : values) {
            columnValues.add(toQueryValue(scalar, v));
        }
        List<T> result = new ArrayList<>();
        for (Row row : table.findByIn(scalar.meta().alias(), columnValues)) {
            result.add(decode(row));
        }
        return result;
    }

    /**
     * 按主键判断存在（缓存层命中直接返回，否则 SQL）。
     *
     * @param key 主键
     * @return 存在返回 true
     */
    public boolean exists(String key) {
        db.checkStore(store, "exists");
        if (local != null && local.get(key) != null) {
            return true;
        }
        if (cacheRedis != null) {
            try {
                if (cacheRedis.get(redisKey(key)) != null) {
                    return true;
                }
            } catch (Exception e) {
                degrade("exists(redis)", e);
            }
        }
        return table.exists(key);
    }

    /**
     * 行数统计（直读 SQL）。
     *
     * @return 总行数
     */
    public long count() {
        db.checkStore(store, "count");
        return table.count();
    }

    /**
     * 流式遍历全表（直读 SQL，防 OOM；含子表/关联装配）。
     *
     * @param batchSize 每批行数（正数）
     * @param batch     批回调
     */
    public void forEach(int batchSize, Consumer<List<T>> batch) {
        db.checkStore(store, "forEach");
        table.forEach(batchSize, rows -> {
            List<T> entities = new ArrayList<>(rows.size());
            for (Row row : rows) {
                entities.add(decode(row));
            }
            batch.accept(entities);
        });
    }

    // ==================== 缓存清理 ====================

    /**
     * 显式清空本表缓存（全服语义：其他实例下次读自动回源重建，安全但会回源）。
     * <p>
     * 本地缓存全清；Redis 删除<b>本进程已知键</b>（SPI 无按前缀枚举能力，
     * 未知键随死期自然回收——死期才是释放保底）；清空前若名册显示还有其他活跃
     * 实例会输出 warning。
     */
    public void clearCache() {
        int others = db.roster().activeCount() - 1;
        if (others > 0) {
            LOG.warning("[jframe_db] clearCache(" + mapping.tableName() + ")：名册显示还有 "
                    + others + " 台活跃实例，清空将导致它们回源重建");
        }
        if (local != null) {
            local.clear();
        }
        if (cacheRedis != null) {
            for (String key : List.copyOf(knownRedisKeys)) {
                try {
                    cacheRedis.delete(key);
                } catch (Exception e) {
                    degrade("clearCache(redis)", e);
                }
            }
            knownRedisKeys.clear();
        }
    }

    // ==================== 异步版本 ====================

    /** {@link #get} 的异步版（虚拟线程执行）。 */
    public CompletableFuture<T> getAsync(String key) {
        return db.executors().supplyAsync(() -> get(key));
    }

    /** {@link #loadOrCreate} 的异步版。 */
    public CompletableFuture<T> loadOrCreateAsync(String key, Supplier<T> provider) {
        return db.executors().supplyAsync(() -> loadOrCreate(key, provider));
    }

    /** {@link #save(Object)} 的异步版。 */
    public CompletableFuture<Void> saveAsync(T entity) {
        return db.executors().supplyAsync(() -> {
            save(entity);
            return null;
        });
    }

    /** {@link #save(String, Object)} 的异步版。 */
    public CompletableFuture<Void> saveAsync(String key, T entity) {
        return db.executors().supplyAsync(() -> {
            save(key, entity);
            return null;
        });
    }

    /** {@link #delete} 的异步版。 */
    public CompletableFuture<Boolean> deleteAsync(String key) {
        return db.executors().supplyAsync(() -> delete(key));
    }

    /** {@link #exists} 的异步版。 */
    public CompletableFuture<Boolean> existsAsync(String key) {
        return db.executors().supplyAsync(() -> exists(key));
    }

    /** {@link #count} 的异步版。 */
    public CompletableFuture<Long> countAsync() {
        return db.executors().supplyAsync(this::count);
    }

    /** {@link #findBy} 的异步版。 */
    public CompletableFuture<List<T>> findByAsync(String fieldName, Object value) {
        return db.executors().supplyAsync(() -> findBy(fieldName, value));
    }

    /** {@link #findByIn} 的异步版。 */
    public CompletableFuture<List<T>> findByInAsync(String fieldName, Collection<?> values) {
        return db.executors().supplyAsync(() -> findByIn(fieldName, values));
    }

    /** {@link #getBatch} 的异步版。 */
    public CompletableFuture<Map<String, T>> getBatchAsync(Collection<String> keys) {
        return db.executors().supplyAsync(() -> getBatch(keys));
    }

    /** {@link #clearCache} 的异步版。 */
    public CompletableFuture<Void> clearCacheAsync() {
        return db.executors().supplyAsync(() -> {
            clearCache();
            return null;
        });
    }

    // ==================== 内部 ====================

    /** 完整装配：主行 → 实体，再装配子表容器与关联实体 */
    private T decode(Row row) {
        T entity = codec.fromMainRow(row, mapping);
        String key = row.key();
        for (int i = 0; i < mapping.children().size(); i++) {
            EntityMapping.ChildField child = mapping.children().get(i);
            List<Object> values = codec.decodeChildValues(
                    childTables.get(i).findBy("parent_key", key), child);
            RowCodec.setFieldValue(child.meta().field(), entity, newCollection(child.meta().field(), values));
        }
        for (int i = 0; i < mapping.relations().size(); i++) {
            EntityMapping.RelationField relation = mapping.relations().get(i);
            Set<String> refKeys = RowCodec.relationRefKeys(
                    relationTables.get(i).findBy("owner_key", key));
            List<Object> refs = new ArrayList<>(refKeys.size());
            if (!refKeys.isEmpty()) {
                for (Row refRow : refTables.get(i).getBatch(refKeys)) {
                    refs.add(codec.fromMainRow(refRow, refMappings.get(i)));
                }
            }
            RowCodec.setFieldValue(relation.meta().field(), entity, newCollection(relation.meta().field(), refs));
        }
        return entity;
    }

    /** 按字段声明类型创建容器实例 */
    private static Collection<Object> newCollection(Field field, List<Object> values) {
        Class<?> declared = field.getType();
        if (Set.class.isAssignableFrom(declared)) {
            return new LinkedHashSet<>(values);
        }
        return new ArrayList<>(values);
    }

    /** 子表重写：删旧插新 */
    private void writeChildren(String key, T entity) {
        for (int i = 0; i < mapping.children().size(); i++) {
            EntityMapping.ChildField child = mapping.children().get(i);
            RelationalTable childTable = childTables.get(i);
            for (Row old : childTable.findBy("parent_key", key)) {
                childTable.delete(old.key());
            }
            Collection<?> elements;
            try {
                elements = (Collection<?>) child.meta().field().get(entity);
            } catch (IllegalAccessException e) {
                throw new DataException("无法读取子表字段 " + child.meta().field().getName(), e);
            }
            for (Row row : codec.toChildRows(key, elements, child)) {
                childTable.upsert(row);
            }
        }
    }

    /** 关联行差集维护（不级联保存 ref 实体） */
    private void writeRelations(String key, T entity) {
        for (int i = 0; i < mapping.relations().size(); i++) {
            EntityMapping.RelationField relation = mapping.relations().get(i);
            RelationalTable relationTable = relationTables.get(i);
            Set<String> oldKeys = RowCodec.relationRefKeys(
                    relationTable.findBy("owner_key", key));
            Collection<?> refs;
            try {
                refs = (Collection<?>) relation.meta().field().get(entity);
            } catch (IllegalAccessException e) {
                throw new DataException("无法读取关联字段 " + relation.meta().field().getName(), e);
            }
            Set<String> newKeys = new LinkedHashSet<>();
            if (refs != null) {
                for (Object ref : refs) {
                    newKeys.add(((SaveIdentifiable) ref).saveKey());
                }
            }
            // 差集删除
            for (String oldRef : oldKeys) {
                if (!newKeys.contains(oldRef)) {
                    relationTable.delete(key + ":" + oldRef);
                }
            }
            // 差集新增
            for (Row row : codec.toRelationRows(key, refs, relation)) {
                if (oldKeys.contains(String.valueOf(row.get("ref_key")))) {
                    continue;
                }
                relationTable.upsert(row);
            }
        }
    }

    /** 写路径后置：Redis 刷新（降级容错）→ 本地刷新 → 失效广播 */
    private void afterWrite(String key, T entity) {
        String redisKey = redisKey(key);
        if (cacheRedis != null) {
            boolean refreshed = false;
            try {
                cacheRedis.set(redisKey, codec.snapshot(entity), cacheTtlSeconds);
                refreshed = true;
            } catch (Exception e) {
                try {
                    cacheRedis.delete(redisKey); // 写不进新值就失效，防旧值久留
                } catch (Exception e2) {
                    degrade("afterWrite(redis 失效兜底)", e2);
                }
                degrade("afterWrite(redis set)", e);
            }
            if (refreshed && knownRedisKeys.size() < KNOWN_KEYS_CAP) {
                knownRedisKeys.add(redisKey);
            }
            db.coordinator().publishInvalidate(cacheRedis, mapping.tableName(), key);
        }
        if (local != null) {
            local.put(key, entity);
        }
    }

    /** SQL 命中后回填两层缓存 */
    private void backfill(String key, T entity) {
        if (cacheRedis != null) {
            String redisKey = redisKey(key);
            try {
                cacheRedis.set(redisKey, codec.snapshot(entity), cacheTtlSeconds);
                if (knownRedisKeys.size() < KNOWN_KEYS_CAP) {
                    knownRedisKeys.add(redisKey);
                }
            } catch (Exception e) {
                degrade("backfill(redis)", e);
            }
        }
        if (local != null) {
            local.put(key, entity);
        }
    }

    private String redisKey(String key) {
        return DbConstants.cacheKey(mapping.tableName(), key);
    }

    /** Redis 故障降级：warning + 继续（主数据在 SQL，业务不崩） */
    private void degrade(String op, Exception e) {
        LOG.warning("[jframe_db] Redis 降级（" + op + "，表 " + mapping.tableName()
                + "，通道 " + storeName + "）：" + e);
    }
}
