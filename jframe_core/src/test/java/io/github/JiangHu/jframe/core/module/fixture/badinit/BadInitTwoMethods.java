package io.github.JiangHu.jframe.core.module.fixture.badinit;

import io.github.JiangHu.jframe.core.module.annotation.JBean;
import io.github.JiangHu.jframe.core.module.annotation.JInit;

/** 非法 @JInit：声明了两个 init 方法。 */
@JBean
public class BadInitTwoMethods {

    @JInit
    public void initA() {
    }

    @JInit
    public void initB() {
    }
}
