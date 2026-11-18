package io.github.JiangHu.jframe.db;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.JiangHu.jframe.db.builder.DataManagerBuilder;
import io.github.JiangHu.jframe.db.builder.RegionBuilder;
import io.github.JiangHu.jframe.db.cache.CacheCoordinator;
import io.github.JiangHu.jframe.db.cache.Roster;
import io.github.JiangHu.jframe.db.spi.RelationalStore;
import io.github.JiangHu.jframe.db.spi.RedisStore;
import io.github.JiangHu.jframe.serialize.core.engine.SaveFieldTypeAdapterFactory;
import io.github.JiangHu.jframe.serialize.core.meta.MetadataCache;
import io.github.JiangHu.jframe.serialize.exception.DataException;
import io.github.JiangHu.jframe.db.support.DbExecutors;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 数据库模块门面（db 模块对外服务）— <b>零数据库依赖的数据存取入口</b>。
 * <p>
 * JFrame 自身不持有任何数据库连接：依赖插件实现
 * {@link RelationalStore}（SQL 型）/ {@link RedisStore}（Redis 型）适配器并注册，
 * 业务代码即可获得：
 * <ul>
 *   <li>{@link DataManager}：列映射 ORM + 三层缓存组合 + 失效广播 + 异步</li>
 *   <li>{@link RegionHandle}：跨服共享数据池（认领/心跳/没人要自动回收）</li>
 *   <li>{@link #redis(String)}：Redis 直用（防重/互斥/限时标记/跨服消息）</li>
 * </ul>
 *
 * <h3>注册粒度与令牌</h3>
 * <p>
 * 一个注册 = 一条存储通道（一个 SQL 库 / 一个 Redis 实例或 db 编号）。
 * <b>名字可选</b>：不命名 = 返回实例即令牌、仅本插件私用（登记簿按实例身份记录）；
 * 命名 = 开放给其他插件按名查找（跨插件场景）。一个通道可服务任意多张表；
 * manager 的主存与缓存可来自不同通道。
 *
 * <h3>生命周期</h3>
 * <p>
 * {@link #unregister(String)} / {@link #unregister(Object)}：句柄失效化（依赖它的
 * manager/region 后续操作抛 {@link DataException} 带指引）、清本地缓存、退订广播、
 * 自动 release 本插件认领的区域；<b>不清共享 Redis 缓存</b>（多实例共享，
 * 单台关停无权替全服释放——释放保底靠死期）。
 */
public final class DbAPI {

    /** 匿名注册的序号发生器 */
    private static final AtomicLong ANON = new AtomicLong();

    private final String instanceId = UUID.randomUUID().toString();
    private final MetadataCache cache = new MetadataCache();
    private final Gson gson = new GsonBuilder()
            .registerTypeAdapterFactory(new SaveFieldTypeAdapterFactory(cache))
            .disableHtmlEscaping()
            // Instant 适配：Gson 反射路径在 JDK 24 模块封锁下不可用，统一 epochMilli
            .registerTypeAdapter(java.time.Instant.class,
                    new com.google.gson.TypeAdapter<java.time.Instant>() {
                        @Override
                        public void write(com.google.gson.stream.JsonWriter out,
                                          java.time.Instant value) throws java.io.IOException {
                            out.value(value == null ? null : value.toEpochMilli());
                        }

                        @Override
                        public java.time.Instant read(com.google.gson.stream.JsonReader in)
                                throws java.io.IOException {
                            com.google.gson.JsonElement element =
                                    com.google.gson.JsonParser.parseReader(in);
                            return element.isJsonNull() ? null
                                    : java.time.Instant.ofEpochMilli(element.getAsLong());
                        }
                    })
            .create();
    private final DbExecutors executors = new DbExecutors();
    private final CacheCoordinator coordinator = new CacheCoordinator(instanceId);
    private final Roster roster = new Roster(instanceId, "jframe");

    /** 注册名 → store（命名注册） */
    private final Map<String, Object> named = new ConcurrentHashMap<>();

    /** store → 注册名（匿名注册为生成的内部名） */
    private final Map<Object, String> identity = new ConcurrentHashMap<>();

    /** store → 是否有效（注销后 false） */
    private final Map<Object, Boolean> valid = new ConcurrentHashMap<>();

    /** store → 依赖它的 manager（注销时失效缓存/路由） */
    private final Map<Object, List<DataManager<?>>> managers = new ConcurrentHashMap<>();

    /** store → 本插件认领的区域（注销时 release） */
    private final Map<Object, List<RegionHandle>> regions = new ConcurrentHashMap<>();

    /** 名册绑定的 Redis 通道（首个注册的 RedisStore） */
    private volatile RedisStore rosterRedis;

    // ==================== 注册 ====================

    /**
     * 注册 SQL 型存储通道（不命名 = 令牌私用）。
     *
     * @param store 适配器实现
     * @return 注册后的实例（即令牌）
     */
    public RelationalStore registerRelational(RelationalStore store) {
        return registerRelational(null, store);
    }

    /**
     * 注册 SQL 型存储通道（命名 = 开放给其他插件按名查找）。
     *
     * @param name  通道名（跨插件共享时使用；重复名抛异常）
     * @param store 适配器实现
     * @return 注册后的实例
     */
    public RelationalStore registerRelational(String name, RelationalStore store) {
        Objects.requireNonNull(store, "store");
        String regName = register(name, store);
        if (!(store instanceof io.github.JiangHu.jframe.db.spi.TransactionalStore)) {
            java.util.logging.Logger.getLogger(DbAPI.class.getName()).info(
                    "[jframe_db] 注册 RelationalStore「" + regName + "」未实现 TransactionalStore——" +
                            "@ChildTable/@RelationTable 的多行写入无事务保护，存在半写风险");
        }
        return store;
    }

    /**
     * 注册 Redis 型存储通道（不命名 = 令牌私用）。
     *
     * @param store 适配器实现
     * @return 注册后的实例（即令牌）
     */
    public RedisStore registerRedis(RedisStore store) {
        return registerRedis(null, store);
    }

    /**
     * 注册 Redis 型存储通道（命名 = 开放给其他插件按名查找）。
     *
     * @param name  通道名
     * @param store 适配器实现
     * @return 注册后的实例
     */
    public RedisStore registerRedis(String name, RedisStore store) {
        Objects.requireNonNull(store, "store");
        register(name, store);
        RedisStore bound = rosterRedis;
        if (bound == null) {
            synchronized (this) {
                if (rosterRedis == null) {
                    rosterRedis = store;
                    roster.start(store, executors);
                }
            }
        }
        return store;
    }

    private String register(String name, Object store) {
        String existing = identity.get(store);
        if (existing != null) {
            if (name != null && !existing.equals(name)) {
                throw new DataException("该 store 实例已以「" + existing + "」注册，不能重复以「" + name + "」注册");
            }
            return existing; // 幂等：重复注册同一实例
        }
        String regName = name != null ? name : "anon-" + ANON.incrementAndGet();
        Object prev = named.putIfAbsent(regName, store);
        if (prev != null) {
            throw new DataException("存储通道名「" + regName + "」已被占用（" +
                    prev.getClass().getName() + "），请换名");
        }
        identity.put(store, regName);
        valid.put(store, true);
        return regName;
    }

    // ==================== 注销 ====================

    /**
     * 按名注销存储通道。
     *
     * @param name 注册名
     * @return 注销成功返回 true；名称不存在返回 false
     */
    public boolean unregister(String name) {
        Object store = name == null ? null : named.remove(name);
        if (store == null) {
            return false;
        }
        unregisterStore(store);
        return true;
    }

    /**
     * 按实例注销存储通道（令牌注销）。
     *
     * @param store 注册时返回的实例
     * @return 注销成功返回 true；未注册返回 false
     */
    public boolean unregister(Object store) {
        Objects.requireNonNull(store, "store");
        String name = identity.get(store);
        if (name == null) {
            return false;
        }
        named.remove(name);
        unregisterStore(store);
        return true;
    }

    private void unregisterStore(Object store) {
        valid.put(store, false);
        List<DataManager<?>> list = managers.remove(store);
        if (list != null) {
            for (DataManager<?> manager : list) {
                manager.clearCache(); // 清本地 + 已知 Redis 键（共享缓存主体靠死期回收）
            }
        }
        List<RegionHandle> regionList = regions.remove(store);
        if (regionList != null) {
            for (RegionHandle region : regionList) {
                try {
                    region.forceRelease();
                } catch (Exception ignored) {
                }
            }
        }
        if (store instanceof RedisStore redis) {
            coordinator.unsubscribe(redis);
            if (rosterRedis == redis) {
                roster.stop();
                rosterRedis = null;
            }
        }
    }

    // ==================== 查找 ====================

    /**
     * 按名查找 SQL 型通道。
     *
     * @param name 注册名
     * @return 通道实例
     * @throws DataException 名称未注册
     */
    public RelationalStore relational(String name) {
        Object store = lookup(name);
        if (!(store instanceof RelationalStore rs)) {
            throw new DataException("「" + name + "」注册的不是 RelationalStore（实际 "
                    + store.getClass().getName() + "）");
        }
        return rs;
    }

    /**
     * 按名查找 Redis 型通道。
     *
     * @param name 注册名
     * @return 通道实例
     * @throws DataException 名称未注册
     */
    public RedisStore redis(String name) {
        Object store = lookup(name);
        if (!(store instanceof RedisStore rs)) {
            throw new DataException("「" + name + "」注册的不是 RedisStore（实际 "
                    + store.getClass().getName() + "）");
        }
        return rs;
    }

    private Object lookup(String name) {
        Object store = name == null ? null : named.get(name);
        if (store == null) {
            throw new DataException("存储通道「" + name + "」未注册——请确认适配器插件已启用并完成注册，" +
                    "或用 DbAPI.registerRelational/registerRedis 登记");
        }
        return store;
    }

    // ==================== 工厂 ====================

    /**
     * 实体管理器构建器（列映射 ORM + 可选三层缓存）。
     *
     * @param type 实体类（须实现 SaveIdentifiable）
     * @param <T>  实体类型
     * @return 构建器
     */
    public <T> DataManagerBuilder<T> manage(Class<T> type) {
        return new DataManagerBuilder<>(this, type);
    }

    /**
     * 数据区域工厂（跨服共享数据池；区域死期必填）。
     *
     * @param name 区域名
     * @param redis Redis 通道
     * @param ttl   区域死期（无人续死期时整片自动蒸发）
     * @return 区域构建器（claim() 认领）
     */
    public RegionBuilder region(String name, RedisStore redis, Duration ttl) {
        Objects.requireNonNull(redis, "redis");
        return new RegionBuilder(this, redis, name, RegionBuilder.requireTtl(name, ttl));
    }

    /**
     * 数据区域工厂（按注册名）。
     *
     * @param name      区域名
     * @param redisName Redis 通道注册名
     * @param ttl       区域死期
     * @return 区域构建器
     */
    public RegionBuilder region(String name, String redisName, Duration ttl) {
        return region(name, redis(redisName), ttl);
    }

    // ==================== 内部访问与生命周期 ====================
    // 以下成员供 builder 等框架内部子包装配使用（跨包可见性），业务插件不应调用。

    /** store 有效性检查（manager/region 每次操作入口调用）。【内部 API】 */
    public void checkStore(Object store, String op) {
        if (!valid.getOrDefault(store, false)) {
            throw new DataException("存储通道「" + identity.get(store) + "」已注销，无法执行 "
                    + op + "——如需继续使用请重新注册适配器并重建 DataManager");
        }
    }

    /** 登记依赖（注销时清理）。【内部 API】 */
    public void trackManager(Object store, DataManager<?> manager) {
        managers.computeIfAbsent(store, s -> new CopyOnWriteArrayList<>()).add(manager);
    }

    /** 登记区域（注销时 release）。【内部 API】 */
    public void trackRegion(Object store, RegionHandle handle) {
        regions.computeIfAbsent(store, s -> new CopyOnWriteArrayList<>()).add(handle);
    }

    /** 注册名（匿名注册返回内部生成名）。【内部 API】 */
    public String storeName(Object store) {
        String name = identity.get(store);
        return name != null ? name : "<未注册>";
    }

    /** 本实例 ID。【内部 API】 */
    public String instanceId() {
        return instanceId;
    }

    /** serialize 内核元数据缓存。【内部 API】 */
    public MetadataCache metadataCache() {
        return cache;
    }

    /** serialize 注解管线 Gson。【内部 API】 */
    public Gson gson() {
        return gson;
    }

    /** 执行器。【内部 API】 */
    public DbExecutors executors() {
        return executors;
    }

    /** 失效广播协调器。【内部 API】 */
    public CacheCoordinator coordinator() {
        return coordinator;
    }

    /** 实例名册。【内部 API】 */
    public Roster roster() {
        return roster;
    }

    /** 模块关服：注销全部通道（走 unregister 语义）+ 关闭执行器。 */
    public void shutdown() {
        for (String name : List.copyOf(named.keySet())) {
            unregister(name);
        }
        executors.shutdown();
    }
}
