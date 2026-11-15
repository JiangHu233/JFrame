package io.github.JiangHu.jframe.transform;

import io.github.JiangHu.jframe.transform.registry.HookRegistry;

/**
 * 测试基座——同一 surefire JVM 内共享唯一注册表（Bridge.sink 为静态单点，
 * 多实例互相覆盖会破坏隔离；测试间用不同目标类/方法互不干扰）。
 */
public final class TransformTestBase {

    /** 共享注册表（懒触发 agent / transformer 安装） */
    private static volatile HookRegistry shared;

    static {
        // 运行 JVM 可能高于 ByteBuddy 已知类文件版本上限（如 JDK 24 + 1.15.x），
        // 实验模式放行（读写的都是更低版本类文件，无实质风险）
        System.setProperty("net.bytebuddy.experimental", "true");
    }

    private TransformTestBase() {
    }

    /**
     * 共享注册表（首个调用触发自附加安装）。
     *
     * @return 注册表
     */
    public static HookRegistry registry() {
        HookRegistry result = shared;
        if (result == null) {
            synchronized (TransformTestBase.class) {
                if (shared == null) {
                    shared = new HookRegistry();
                }
            }
            result = shared;
        }
        return result;
    }

    /**
     * 测试用作用域（插件名 "test"）。
     *
     * @return 作用域
     */
    public static TransformPluginScope scope() {
        return new TransformPluginScope(registry(), "test",
                TransformTestBase.class.getClassLoader());
    }
}
