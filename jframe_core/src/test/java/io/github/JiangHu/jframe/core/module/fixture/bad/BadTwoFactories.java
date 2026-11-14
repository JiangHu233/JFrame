package io.github.JiangHu.jframe.core.module.fixture.bad;

import io.github.JiangHu.jframe.core.module.annotation.JBean;
import io.github.JiangHu.jframe.core.module.annotation.JFactory;

/**
 * 扫描失败用 fixture：声明了多个 @JFactory 方法，scan 应抛 IllegalArgumentException。
 */
@JBean
public class BadTwoFactories {

    @JFactory
    public static BadTwoFactories first() {
        return new BadTwoFactories();
    }

    @JFactory
    public static BadTwoFactories second() {
        return new BadTwoFactories();
    }
}
