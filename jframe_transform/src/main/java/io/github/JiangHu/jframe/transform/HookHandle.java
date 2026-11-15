package io.github.JiangHu.jframe.transform;

import java.util.List;

/**
 * 钩子注册句柄——一次注册（可能因通配命中多个类/方法）的聚合视图与撤销入口。
 * <p>
 * 生命周期：注册即生效；{@link #unhook()} 或插件 disable（框架自动）后失效并尽力
 * 还原原始字节码。同形状增删钩子走注册表零 retransform；形状跃迁触发单类重织。
 */
public interface HookHandle {

    /**
     * 聚合状态：无任何已织入方法 = {@link HookStatus#PENDING}；任一 ACTIVE 即 ACTIVE；
     * 全部 FAILED = FAILED。
     *
     * @return 聚合状态
     */
    HookStatus status();

    /**
     * 本次注册织入（含待命）的类名清单——通配注册时可能多值。
     *
     * @return 类名列表（快照）
     */
    List<String> touchedClassNames();

    /**
     * 已织入形状（各方法取并集语义的最宽形状）。
     *
     * @return 形状
     */
    InstrumentShape shape();

    /**
     * 撤销本次注册：停分发；相关类无其他钩子时还原原始字节码（幂等）。
     */
    void unhook();
}
