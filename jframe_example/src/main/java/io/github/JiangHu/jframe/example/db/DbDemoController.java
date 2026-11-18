package io.github.JiangHu.jframe.example.db;

import cn.nukkit.command.CommandSender;
import io.github.JiangHu.jframe.command.annotation.CommandController;
import io.github.JiangHu.jframe.command.annotation.CommandMapping;
import io.github.JiangHu.jframe.command.annotation.Sender;
import io.github.JiangHu.jframe.db.DataManager;
import io.github.JiangHu.jframe.db.DbAPI;
import io.github.JiangHu.jframe.db.RegionHandle;
import io.github.JiangHu.jframe.db.annotation.ChildTable;
import io.github.JiangHu.jframe.db.annotation.Column;
import io.github.JiangHu.jframe.db.annotation.Indexed;
import io.github.JiangHu.jframe.db.spi.RedisStore;
import io.github.JiangHu.jframe.db.spi.RelationalStore;
import io.github.JiangHu.jframe.db.spi.Subscription;
import io.github.JiangHu.jframe.db.support.InMemoryRedisStore;
import io.github.JiangHu.jframe.db.support.InMemoryRelationalStore;
import io.github.JiangHu.jframe.serialize.SaveIdentifiable;
import io.github.JiangHu.jframe.serialize.annotation.SaveField;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 数据库模块<b>实机演示控制器</b>（根命令 {@code /dbdemo}）——零数据库、开箱即跑。
 * <p>
 * 用内存假适配器（{@link InMemoryRelationalStore} / {@link InMemoryRedisStore}）演示
 * jframe_db 全链路：适配器注册（令牌）→ 列映射 ORM（自动建表 + @Indexed + @ChildTable）→
 * 三层缓存组合 → 异步方法 → 数据区域（认领/心跳/末位回收）→ 跨服消息 loopback。
 * 真实环境只需把内存适配器换成 MySQL/Redis 适配器（见 jframe_db/README.md）。
 */
@CommandController("dbdemo")
public class DbDemoController {

    private static DbAPI db;
    private static DataManager<DemoPlayer> players;
    private static RegionHandle pool;
    private static Subscription chatSub;
    private static final AtomicReference<String> LAST_MESSAGE = new AtomicReference<>();

    /** 演示实体：标量列 + 索引列 + JSON 列 + 子表容器 */
    public static class DemoPlayer implements SaveIdentifiable {
        @SaveField(required = true)
        public String uuid;

        @SaveField @Indexed @Column(length = 50)
        public String name;

        @SaveField
        public int level;

        @SaveField
        public Instant lastLogin;

        @SaveField
        public Map<String, Integer> stats;      // → JSON 列

        @SaveField @ChildTable
        public List<String> mails;              // → 子表 demo_players_mails

        public DemoPlayer() {
        }

        public DemoPlayer(String uuid, String name, int level) {
            this.uuid = uuid;
            this.name = name;
            this.level = level;
        }

        @Override
        public String saveKey() {
            return uuid;
        }
    }

    /**
     * 注入 DbAPI 并完成演示装配（ExamplePlugin 在 scan 前调用一次）。
     *
     * @param api 数据库模块门面
     */
    public static void setDb(DbAPI api) {
        db = api;
        // ① 注册内存适配器：无名 = 返回实例即令牌（真实环境换成 MySQL/Redis 适配器）
        RelationalStore games = db.registerRelational(new InMemoryRelationalStore());
        RedisStore cache = db.registerRedis("演示缓存", new InMemoryRedisStore());

        // ② 组合层 manager：主存 + Redis 缓存（死期必填）+ 本地缓存（默认无期限）
        players = db.manage(DemoPlayer.class)
                .relational(games, "demo_players")
                .keyColumn("uuid")
                .cache(cache, Duration.ofMinutes(10))
                .localCache()
                .build();

        // ③ 数据区域：多插件/多服共享认领，无人认领自动蒸发
        pool = db.region("演示匹配池", cache, Duration.ofMinutes(5)).claim();

        // ④ 跨服消息 loopback 订阅
        chatSub = cache.subscribe("demo:chat", (ch, msg) -> LAST_MESSAGE.set(msg));
    }

    /** 关服清理（ExamplePlugin.onDisable 调用）。 */
    public static void shutdown() {
        if (chatSub != null) {
            chatSub.close();
        }
        if (pool != null) {
            pool.release(); // 末位 release 立即删键
        }
    }

    /** {@code /dbdemo}：全链路演示。 */
    @CommandMapping(value = "", desc = "jframe_db 全链路演示（内存适配器，零数据库开箱即跑）")
    public void demo(@Sender CommandSender sender) {
        sender.sendMessage("§b===== jframe_db 演示（内存适配器） =====");

        // 1. 自动建表 + 保存（主行 + 子表 + Redis 快照 + 本地 + 失效广播）
        DemoPlayer steve = new DemoPlayer("u1", "Steve", 42);
        steve.lastLogin = Instant.now();
        steve.stats = Map.of("kills", 10);
        steve.mails = List.of("welcome", "gift");
        players.save(steve);
        sender.sendMessage("§a[ORM] §fsave(Steve) → 自动建表(含子表 demo_players_mails) + Redis 快照 + 本地缓存");

        // 2. 读取（本地 → Redis → SQL 穿透）
        DemoPlayer loaded = players.get("u1");
        sender.sendMessage("§a[缓存] §fget(u1) = " + loaded.name + " Lv." + loaded.level
                + "，mails=" + loaded.mails);

        // 3. 索引查询（@Indexed 真实列）
        List<DemoPlayer> hit = players.findBy("name", "Steve");
        sender.sendMessage("§a[索引] §ffindBy(name, Steve) 命中 " + hit.size() + " 条");

        // 4. 异步（虚拟线程，不卡主线程）
        CompletableFuture<Long> count = players.countAsync();
        sender.sendMessage("§a[异步] §fcountAsync() = " + count.join());

        // 5. 数据区域（共享池：认领/心跳/没人要自动回收）
        pool.put("room:123", Map.of("host", "Steve", "players", 4));
        sender.sendMessage("§a[区域] §fput(room:123) → keys=" + pool.keys() + "，size=" + pool.size());

        // 6. 跨服消息 loopback（publish → subscribe）
        db.redis("演示缓存").publish("demo:chat", "{\"from\":\"lobby-1\",\"text\":\"gg\"}");
        sender.sendMessage("§a[消息] §fpublish(demo:chat) → 订阅收到: " + LAST_MESSAGE.get());

        // 7. 释放权演示：delete 连带清缓存与子表
        players.delete("u1");
        sender.sendMessage("§a[删除] §fdelete(u1) → exists=" + players.exists("u1")
                + "，count=" + players.count());
        sender.sendMessage("§7真实环境：把内存适配器换成 MySQL/Redis 适配器即可（见 jframe_db/README.md）");
    }
}
