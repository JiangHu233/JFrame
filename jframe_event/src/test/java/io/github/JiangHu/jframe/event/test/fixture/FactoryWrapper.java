package io.github.JiangHu.jframe.event.test.fixture;

import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.annotation.InstanceProvider;
import io.github.JiangHu.jframe.event.annotation.KeyExtractor;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.MoveEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 策略2：自定义 @InstanceProvider 工厂（对接外部缓存容器）。
 * <p>
 * 验证点：
 * <ul>
 *   <li>框架使用工厂方法而非默认缓存创建实例</li>
 *   <li>工厂返回的实例被缓存复用（同一身份多次事件复用同一实例）</li>
 *   <li>工厂返回 null 时跳过该事件（不路由）</li>
 *   <li>{@code @EventRoute(value = MoveEvent.class)} 显式指定事件类型</li>
 * </ul>
 */
public class FactoryWrapper {

    /** 外部缓存：身份 → 实例 */
    private static final Map<String, FactoryWrapper> CACHE = new ConcurrentHashMap<>();

    /** 工厂被调用的次数（验证走的是工厂而非构造函数） */
    private static final AtomicInteger FACTORY_CALLS = new AtomicInteger();

    /** 处理器调用记录 */
    private static final List<String> INVOCATIONS = new ArrayList<>();

    private final String name;

    private FactoryWrapper(String name) {
        this.name = name;
    }

    /** 外部注册：将身份预置到缓存（模拟"创建魔法剑"等业务注册） */
    public static void register(String name) {
        CACHE.put(name, new FactoryWrapper(name));
    }

    /** 清空外部缓存 */
    public static void clearCache() {
        CACHE.clear();
    }

    /** 身份提取器 */
    @KeyExtractor
    public static String extract(MoveEvent event) {
        return event.getPlayerName();
    }

    /**
     * 实例工厂：从外部缓存查找，找不到返回 null（跳过该事件）。
     * <b>必须是 static</b>。
     */
    @InstanceProvider
    public static FactoryWrapper getOrCreate(String name) {
        FACTORY_CALLS.incrementAndGet();
        return CACHE.get(name);
    }

    /** 处理器：显式指定事件类型为 MoveEvent */
    @EventRoute(value = MoveEvent.class)
    @EventHandler
    public void onMove(MoveEvent event) {
        INVOCATIONS.add(name + "@" + event.getX());
    }

    public static int getFactoryCalls() {
        return FACTORY_CALLS.get();
    }

    public static List<String> getInvocations() {
        return INVOCATIONS;
    }

    public static void reset() {
        CACHE.clear();
        FACTORY_CALLS.set(0);
        INVOCATIONS.clear();
    }
}
