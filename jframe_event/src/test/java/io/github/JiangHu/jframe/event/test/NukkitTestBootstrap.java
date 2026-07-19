package io.github.JiangHu.jframe.event.test;

import cn.nukkit.Server;

import java.lang.reflect.Field;

/**
 * 测试引导器：在<b>无运行中的 Nukkit 服务器</b>的纯 JVM 单元测试环境中，
 * 让 {@link Server#getInstance()} 返回一个非 null 的占位实例。
 * <p>
 * 事件模块的内部实现（{@code HandlerRegistry} / {@code HandlerTemplate}）在
 * 异常 / 警告路径会调用 {@code Server.getInstance().getLogger()}。
 * 若不引导，{@code getInstance()} 返回 null 会触发 NPE，使错误路径测试无法进行。
 * <p>
 * <b>原理</b>：通过 {@code sun.misc.Unsafe#allocateInstance} 绕过 {@link Server} 的
 * 重量级构造器（它会启动网络、文件 IO、线程池等），创建一个字段全为默认值的空壳实例，
 * 再用反射写入 {@code Server.instance} 静态字段。
 * 由于 {@code Server.getLogger()} 内部直接委托给静态的 {@code MainLogger.getLogger()}，
 * 因此空壳实例的 {@code getLogger()} 依然可用。
 * <p>
 * 已在 JDK 24 上验证可用。方法幂等，可安全多次调用。
 */
public final class NukkitTestBootstrap {

    private static volatile boolean initialized = false;

    private NukkitTestBootstrap() {
    }

    /**
     * 引导 Nukkit {@link Server} 单例。若已初始化则直接返回。
     *
     * @return 引导是否成功（失败时返回 false，但不抛异常——仅影响需要 Server 的错误路径）
     */
    public static boolean bootstrap() {
        if (initialized) {
            return true;
        }
        try {
            if (Server.getInstance() != null) {
                initialized = true;
                return true;
            }
            // 通过 Unsafe 分配一个不调用构造器的 Server 空壳
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Object shell = unsafeClass.getMethod("allocateInstance", Class.class)
                    .invoke(unsafe, Server.class);

            // 写入静态 instance 字段
            Field instanceField = Server.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, shell);

            initialized = Server.getInstance() != null;
            return initialized;
        } catch (Throwable t) {
            System.err.println("[NukkitTestBootstrap] 引导失败，错误路径日志可能不可用: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }
}
