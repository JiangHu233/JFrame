package io.github.JiangHu.jframe.core.module.fixture.good;

/**
 * 扫描成功用 fixture：B —— 持有 A，由 {@link ScannedFactoryClass} 经 ctx.get(A) 组装。
 */
public class DependencyB {

    private final DependencyA a;

    public DependencyB(DependencyA a) {
        this.a = a;
    }

    public DependencyA getA() {
        return a;
    }
}
