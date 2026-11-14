package io.github.JiangHu.jframe.floatingtext.nukkit;

import cn.nukkit.Server;
import io.github.JiangHu.jframe.floatingtext.viewers.TextViewer;
import io.github.JiangHu.jframe.floatingtext.viewers.ViewerDirectory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 生产环境在线玩家目录 — 扫 {@code Server.getInstance().getOnlinePlayers()}。
 */
public class DefaultViewerDirectory implements ViewerDirectory {

    @Override
    public Collection<TextViewer> online() {
        List<TextViewer> viewers = new ArrayList<>();
        for (cn.nukkit.Player player : Server.getInstance().getOnlinePlayers().values()) {
            if (player.isOnline()) {
                viewers.add(TextViewer.of(player));
            }
        }
        return viewers;
    }
}
