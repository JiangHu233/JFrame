package io.github.JiangHu.jframe.data.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;

/**
 * 数据模块 Spring 配置入口。
 * <p>
 * 导入 {@code data-spring.xml} 完成 Bean 装配（元数据缓存 → 数据保存器）。
 */
@Configuration
@ImportResource("classpath:data-spring.xml")
public class DataSpringConfig {
}
