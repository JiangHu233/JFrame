package io.github.JiangHu.jframe.core.module.fixture.boot;

import io.github.JiangHu.jframe.core.module.annotation.JBean;

/** 无 @JInit 的普通产品类。 */
@JBean
public class BootPlain {

    static int created;

    public BootPlain() {
        created++;
    }

    public static void reset() {
        created = 0;
    }

    public static int createdCount() {
        return created;
    }
}
