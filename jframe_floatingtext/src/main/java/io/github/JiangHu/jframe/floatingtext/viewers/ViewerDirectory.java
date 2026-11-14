package io.github.JiangHu.jframe.floatingtext.viewers;

import java.util.Collection;

/**
 * 在线玩家目录 — 管理器由此解析「当前在线观众」（初始显示 / 周期对账用）。
 *
 * <p>生产实现扫 {@code Server.getInstance().getOnlinePlayers()}；测试注入假目录。
 */
@FunctionalInterface
public interface ViewerDirectory {

    /**
     * 当前在线玩家的模块内表示。
     *
     * @return 在线观众集合
     */
    Collection<TextViewer> online();
}
