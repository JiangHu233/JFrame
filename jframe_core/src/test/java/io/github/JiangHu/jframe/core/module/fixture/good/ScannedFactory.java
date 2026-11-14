package io.github.JiangHu.jframe.core.module.fixture.good;

import io.github.JiangHu.jframe.core.module.annotation.JBean;
import io.github.JiangHu.jframe.core.module.annotation.JFactory;

/**
 * 扫描成功用 fixture：带 @JFactory 工厂方法的产品类（私有构造，证明走工厂路径）。
 */
@JBean
public class ScannedFactory {

    static int factoryUsed;

    private ScannedFactory() {
    }

    @JFactory
    public static ScannedFactory make() {
        factoryUsed++;
        return new ScannedFactory();
    }

    public static int factoryUsedCount() {
        return factoryUsed;
    }

    public static void reset() {
        factoryUsed = 0;
    }
}
