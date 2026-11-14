package io.github.JiangHu.jframe.engine.pool;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 对象池原语（engine 内部，不下沉 core、不跨模块共享）— ArrayDeque 空闲表 + 指标 + debug 双重释放检测。
 *
 * <h3>线程模型</h3>
 * <b>单线程、零锁</b>：全部方法须在同一线程调用（ECS 语境下即 Nukkit 主线程），
 * 与 {@code EcsEngine} 的线程模型一致；需要多线程时应另行包装，本类不提供。
 *
 * <h3>语义</h3>
 * <ul>
 *   <li>{@link #obtain()}：优先取空闲实例；空闲表空时调用 factory 新建（factory 为 null
 *       时返回 null —— 供「实例只由持有方创建、池只管回收」的场景，如 EcsEntity 壳）；</li>
 *   <li>{@link #free(Object)}：先执行 resetter（可空），再入空闲表；超 {@code maxSize}
 *       直接丢弃（断引用交 GC）；resetter 抛异常则不入池（对象同样丢弃）；</li>
 *   <li>debug 模式（构造开关）：维护借出实例的 {@link IdentityHashMap} 追踪，
 *       双重 free / free 从未 obtain 的实例抛 {@link IllegalStateException}；
 *       非 debug 模式零额外开销。</li>
 * </ul>
 *
 * <h3>指标</h3>
 * {@code created / reused / hitRate / peak} 为三个计数器与一个峰值，纳秒级开销，
 * 供压测与容量校准（命中率长期低于 50% 的类建议关闭池化）。
 *
 * @param <T> 池化对象类型
 */
public final class ObjectPool<T> {

    /** 空闲实例表（LIFO） */
    private final ArrayDeque<T> free = new ArrayDeque<>();

    /** 新建工厂（可空 = 池不自建实例，仅回收持有方创建的对象） */
    private final Supplier<T> factory;

    /** 归池前重置器（可空） */
    private final Consumer<T> resetter;

    /** 空闲表容量上限（超出即丢弃） */
    private final int maxSize;

    /** debug 双重释放检测（可空 = 关闭） */
    private final IdentityHashMap<T, T> inFlight;

    /** 累计新建数 */
    private long created;

    /** 累计复用数 */
    private long reused;

    /** 空闲表历史峰值 */
    private int peak;

    /**
     * 构造对象池。
     *
     * @param factory  新建工厂（可空；空则 obtain 空闲表时返回 null）
     * @param resetter 归池前重置器（可空）
     * @param maxSize  空闲表容量上限（&le;0 视为 1）
     * @param debug    开启双重释放检测
     */
    public ObjectPool(Supplier<T> factory, Consumer<T> resetter, int maxSize, boolean debug) {
        this.factory = factory;
        this.resetter = resetter;
        this.maxSize = Math.max(1, maxSize);
        this.inFlight = debug ? new IdentityHashMap<>() : null;
    }

    /**
     * 取实例：空闲表非空出栈复用（LIFO，刚归还的热实例优先）；否则 factory 新建
     * （factory 为 null 返回 null）。
     *
     * @return 实例；空闲表空且无工厂时 null
     */
    public T obtain() {
        T pooled = free.pollLast();
        if (pooled != null) {
            reused++;
            if (inFlight != null) {
                inFlight.put(pooled, pooled);
            }
            return pooled;
        }
        if (factory == null) {
            return null;
        }
        T created = factory.get();
        this.created++;
        if (inFlight != null && created != null) {
            inFlight.put(created, created);
        }
        return created;
    }

    /**
     * 归还：先 resetter（异常则丢弃不入池），再入空闲表；超上限丢弃。
     * <p>null 入参直接忽略。
     *
     * @param instance 实例
     * @throws IllegalStateException debug 模式下双重 free / free 从未 obtain 的实例
     */
    public void free(T instance) {
        if (instance == null) {
            return;
        }
        if (inFlight != null && inFlight.remove(instance) == null) {
            throw new IllegalStateException(
                    "双重 free 或 free 未借出实例: " + instance.getClass().getName());
        }
        if (resetter != null) {
            resetter.accept(instance);
        }
        if (free.size() < maxSize) {
            free.add(instance);
            if (free.size() > peak) {
                peak = free.size();
            }
        }
        // 超上限：丢弃（断引用交 GC）；debug 模式下该实例已出 inFlight，再 free 会正确报错
    }

    /**
     * 清空空闲表与借出追踪（引擎停止释放引用用）。
     */
    public void clear() {
        free.clear();
        if (inFlight != null) {
            inFlight.clear();
        }
    }

    /**
     * 当前空闲数。
     *
     * @return 空闲实例数
     */
    public int size() {
        return free.size();
    }

    /**
     * 容量上限。
     *
     * @return maxSize
     */
    public int maxSize() {
        return maxSize;
    }

    /**
     * 累计新建数。
     *
     * @return 计数
     */
    public long created() {
        return created;
    }

    /**
     * 累计复用数。
     *
     * @return 计数
     */
    public long reused() {
        return reused;
    }

    /**
     * 空闲表历史峰值。
     *
     * @return 峰值
     */
    public int peak() {
        return peak;
    }

    /**
     * 命中率 = reused / (created + reused)；无记录时 0。
     *
     * @return [0,1]
     */
    public double hitRate() {
        long total = created + reused;
        return total == 0 ? 0 : (double) reused / total;
    }

    @Override
    public String toString() {
        return "ObjectPool{idle=" + free.size() + "/" + maxSize
                + ", created=" + created + ", reused=" + reused
                + ", peak=" + peak + "}";
    }
}
