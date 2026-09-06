package io.github.JiangHu.jframe.title.nukkit;

import cn.nukkit.Player;

/**
 * 标题发送适配接口：隔离对 Nukkit {@link Player} 发包 API 的直接依赖
 *
 * <p>title 模块内所有发包调用（sendTitle / sendActionBar / clearTitle）
 * 均经本接口完成；测试以录制桩替换以脱离 Nukkit 运行时。</p>
 */
public interface TitleSender {

    /**
     * 发送主标题 + 副标题（携带时序参数）
     *
     * @param title    主标题文本
     * @param subtitle 副标题文本（多行以 \n 分隔）
     * @param fadeIn   淡入时长（tick）
     * @param stay     停留时长（tick）
     * @param fadeOut  淡出时长（tick）
     */
    void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut);

    /**
     * 发送动作栏文本
     *
     * @param text 动作栏文本
     */
    void sendActionBar(String text);

    /** 清除当前标题（title / subtitle 通道） */
    void clearTitle();

    /**
     * 为指定玩家创建发送器
     *
     * @param player 目标玩家
     * @return 绑定该玩家的默认发送器
     */
    static TitleSender of(Player player) {
        return new DefaultTitleSender(player);
    }
}
