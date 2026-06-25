package io.github.JiangHu.jframe.main.utils;

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
    CORE(io.github.JiangHu.jframe.core.config.SpringConfig.class),
    THREAD(io.github.JiangHu.jframe.thread.config.SpringConfig.class),
    FORM(io.github.JiangHu.jframe.form.config.SpringConfig.class),
    EVENT(io.github.JiangHu.jframe.event.config.SpringConfig.class),
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
