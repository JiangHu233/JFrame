package io.github.JiangHu.jframe.core.module.fixture.bad2;

import io.github.JiangHu.jframe.core.module.annotation.JBean;
import io.github.JiangHu.jframe.core.module.annotation.JFactoryClass;

/**
 * 扫描失败用 fixture：同一类同时标注 @JBean 与 @JFactoryClass，scan 应抛 IllegalArgumentException。
 */
@JBean
@JFactoryClass
public class BadConflict {
}
