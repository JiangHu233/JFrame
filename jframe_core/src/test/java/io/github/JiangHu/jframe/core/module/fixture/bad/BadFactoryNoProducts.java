package io.github.JiangHu.jframe.core.module.fixture.bad;

import io.github.JiangHu.jframe.core.module.annotation.JFactoryClass;

/**
 * 扫描失败用 fixture：@JFactoryClass 没有任何 @JBean 方法，scan 应抛 IllegalArgumentException。
 */
@JFactoryClass
public class BadFactoryNoProducts {

    public String notAnnotated() {
        return "x";
    }
}
