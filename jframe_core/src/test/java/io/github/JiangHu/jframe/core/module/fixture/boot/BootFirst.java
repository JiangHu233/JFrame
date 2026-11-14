package io.github.JiangHu.jframe.core.module.fixture.boot;

import io.github.JiangHu.jframe.core.module.BeanContext;
import io.github.JiangHu.jframe.core.module.annotation.JBean;
import io.github.JiangHu.jframe.core.module.annotation.JInit;

/** 扫描序靠前的 bean：init 时校验靠后 bean 已构造（三阶段时序断言用）。 */
@JBean
public class BootFirst implements BootMarker {

    public static final java.util.List<String> LOG = new java.util.ArrayList<>();

    public boolean secondConstructedWhenInit;
    public BootSecond second;
    public int count;
    public boolean enabled;
    public double ratio;
    public String[] names;
    public int markerCount;

    public BootFirst() {
        LOG.add("first-constructed");
    }

    @JInit
    public void init(BeanContext ctx) {
        LOG.add("first-init");
        this.second = ctx.get(BootSecond.class);
        this.secondConstructedWhenInit = BootSecond.createdCount() > 0;
        this.count = ctx.propInt("boot.count", 7);
        this.enabled = ctx.propBoolean("boot.enabled", false);
        this.ratio = ctx.propDouble("boot.ratio", 0.5);
        this.names = ctx.propStrings("boot.names", new String[]{"默认甲", "默认乙"});
        this.markerCount = ctx.allOf(BootMarker.class).size();
    }
}
