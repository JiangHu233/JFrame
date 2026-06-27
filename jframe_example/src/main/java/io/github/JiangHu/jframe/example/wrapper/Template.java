package io.github.JiangHu.jframe.example.wrapper;


import cn.nukkit.event.Event;

import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.annotation.InstanceProvider;
import io.github.JiangHu.jframe.event.annotation.KeyExtractor;


public class Template {
    /** 玩家 -> 统计实例。使用自定义 @InstanceProvider，因此需自行维护此表。 */
    public record Identifier(Object obj)  {}

    public Template(Identifier identifier) {

    }


    // ==================== 身份提取（必需，static） ====================

    @KeyExtractor
    public static Identifier extract(Event event) {
        return new Identifier(event);
    }

    // ==================== 实例工厂（static） ====================

    @InstanceProvider
    public static Template provide(Identifier identifier) {
        return new Template(new Identifier(null));
    }

    // ==================== 事件处理（实例方法） ====================

    @EventHandler
    @EventRoute
    public void onEvent(Event event) {

    }
}
