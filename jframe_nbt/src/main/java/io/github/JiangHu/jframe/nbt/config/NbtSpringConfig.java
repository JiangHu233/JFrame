package io.github.JiangHu.jframe.nbt.config;

import io.github.JiangHu.jframe.nbt.NbtAPI;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;

/**
 * NBT 路径模块 Spring 配置入口。
 * <p>
 * 导入 {@code nbt-spring.xml}，装配 NBT 模块的 Bean DAG：
 * {@code nbtPathParser → nbtPathMatcher → nbtPathWriter → nbtAPI}。
 * <p>
 * 通过 {@code ConfigEnum.NBT} 由 {@code JFrameMain} 动态导入
 * （M1 仅登记，主模块接线属 jframe_main 集成项，见 DEVELOPER.md 偏差登记）。
 *
 * @see NbtAPI
 */
@Configuration
@ImportResource("classpath:nbt-spring.xml")
public class NbtSpringConfig {
}
