package io.github.JiangHu.jframe.engine.ecs.fixture;

import cn.nukkit.event.Cancellable;

/**
 * 测试用可取消事件（ignoreCancelled 验证）。
 */
public class FakeCancelEvent extends FakeEvent implements Cancellable {

    /** 取消标志 */
    public boolean cancelled;

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled() {
        cancelled = true;
    }

    @Override
    public void setCancelled(boolean value) {
        cancelled = value;
    }
}
