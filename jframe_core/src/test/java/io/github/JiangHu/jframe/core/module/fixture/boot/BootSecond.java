package io.github.JiangHu.jframe.core.module.fixture.boot;

import io.github.JiangHu.jframe.core.module.BeanContext;
import io.github.JiangHu.jframe.core.module.annotation.JBean;
import io.github.JiangHu.jframe.core.module.annotation.JInit;

/** 扫描序靠后的 bean：@JInit 反向绑定 BootFirst（字段级循环引用免疫验证）。 */
@JBean
public class BootSecond {

    static int created;

    public BootFirst first;

    public BootSecond() {
        created++;
        BootFirst.LOG.add("second-constructed");
    }

    @JInit
    public void init(BeanContext ctx) {
        BootFirst.LOG.add("second-init");
        this.first = ctx.get(BootFirst.class);
    }

    public static void reset() {
        created = 0;
    }

    public static int createdCount() {
        return created;
    }
}
