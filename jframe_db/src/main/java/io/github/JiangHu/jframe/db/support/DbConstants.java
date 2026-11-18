package io.github.JiangHu.jframe.db.support;

/**
 * jframe_db 保留前缀与键/频道命名约定。
 * <p>
 * {@code __jframe_db__} 前缀为框架保留——框架写入的缓存键、内部频道
 * 一律带此前缀，可辨识可清理；业务插件直用 RedisStore 时<b>禁用</b>同前缀。
 */
public final class DbConstants {

    /** 框架保留键前缀 */
    public static final String PREFIX = "__jframe_db__";

    /** 实体缓存键前缀（完整键 = {prefix}:{table}:{key}） */
    public static final String CACHE_PREFIX = PREFIX + ":";

    /** 多实例缓存失效广播频道（保留，业务禁用） */
    public static final String CHANNEL_INVALIDATE = PREFIX + ":invalidate";

    /** 实例名册频道（join / 重播 / left；保留，业务禁用） */
    public static final String CHANNEL_ROSTER = PREFIX + ":roster";

    /** 数据区域键前缀（完整键 = {prefix}:region:{名}） */
    public static final String REGION_PREFIX = PREFIX + ":region:";

    /** 数据区域短写锁键后缀（完整锁键 = 区域键 + ":lock"） */
    public static final String REGION_LOCK_SUFFIX = ":lock";

    private DbConstants() {
    }

    /**
     * 实体缓存键。
     *
     * @param table 表名
     * @param key   主键
     * @return 完整键
     */
    public static String cacheKey(String table, String key) {
        return CACHE_PREFIX + table + ":" + key;
    }

    /**
     * 数据区域键。
     *
     * @param name 区域名
     * @return 完整键
     */
    public static String regionKey(String name) {
        return REGION_PREFIX + name;
    }
}
