package io.github.JiangHu.jframe.main.utils;

import io.github.JiangHu.jframe.ai.config.AiSpringConfig;
import io.github.JiangHu.jframe.command.config.CommandSpringConfig;
import io.github.JiangHu.jframe.core.config.CoreSpringConfig;
import io.github.JiangHu.jframe.event.config.EventSpringConfig;
import io.github.JiangHu.jframe.form.config.FormSpringConfig;
import io.github.JiangHu.jframe.inventory.config.InventorySpringConfig;
import io.github.JiangHu.jframe.async.config.AsyncSpringConfig;
import lombok.Getter;
import io.github.JiangHu.jframe.main.JFrameMain;
/**
 * JFrame 模块枚举。
 * <p>
 * 用于声明需要动态导入的模块，{@link JFrameMain} 会读取该枚举数组，
 * 并将 core/thread/form 对应的 SpringConfig 动态导入到当前容器中。
 *
 * @see JFrameMain
 */
public enum ConfigEnum {
    CORE(CoreSpringConfig.class),
    THREAD(AsyncSpringConfig.class),
    FORM(FormSpringConfig.class),
    EVENT(EventSpringConfig.class),
    COMMAND(CommandSpringConfig.class),
    INVENTORY(InventorySpringConfig.class),
    AI(AiSpringConfig.class),
    ;

    @Getter
    private final Class<?> springConfigClass;

    ConfigEnum(Class<?> springConfigClass) {
        this.springConfigClass = springConfigClass;
    }

    /**
     * 返回该模块对应的 SpringConfig 全限定类名，供 {@link org.springframework.context.annotation.ImportSelector} 使用。
     *
     * @return SpringConfig 的全限定类名
     */
    public String getClassName() {
        return springConfigClass.getName();
    }
}
