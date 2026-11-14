package io.github.JiangHu.jframe.core.module.fixture.good;

import io.github.JiangHu.jframe.core.module.annotation.JBean;

/**
 * 扫描成功用 fixture：无 @JFactory 的产品类（等价 register(Class) 无参 new）。
 */
@JBean
public class ScannedPlain {

    static int created;

    public ScannedPlain() {
        created++;
    }

    public static int createdCount() {
        return created;
    }

    public static void reset() {
        created = 0;
    }
}
