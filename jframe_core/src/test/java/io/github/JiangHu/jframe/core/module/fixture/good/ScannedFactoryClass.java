package io.github.JiangHu.jframe.core.module.fixture.good;

import io.github.JiangHu.jframe.core.module.ModuleContext;
import io.github.JiangHu.jframe.core.module.annotation.JBean;
import io.github.JiangHu.jframe.core.module.annotation.JFactoryClass;

/**
 * 扫描成功用 fixture：工厂类 + 两个 @JBean 产品方法（B 持有 A 经 ctx.get 显式解决）。
 * 计数器用于断言"扫描零实例化、首次 get 才实例化工厂"。
 */
@JFactoryClass
public class ScannedFactoryClass {

    static int instantiated;

    public ScannedFactoryClass() {
        instantiated++;
    }

    public static int instantiatedCount() {
        return instantiated;
    }

    public static void reset() {
        instantiated = 0;
    }

    @JBean
    public DependencyA dependencyA() {
        return new DependencyA();
    }

    @JBean
    public DependencyB dependencyB(ModuleContext ctx) {
        return new DependencyB(ctx.get(DependencyA.class));
    }
}
