package io.github.JiangHu.jframe.inventory.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;

/**
 * 箱子界面模块 Spring 配置。
 * <p>
 * 导入 {@code inventory-spring.xml}，定义模块所需的 Bean：
 * <ul>
 *   <li>{@code inventoryManager} —— 事件监听 + 视图管理</li>
 *   <li>{@code inventoryAPI} —— 公开门面</li>
 * </ul>
 */
@Configuration
@ComponentScan("io.github.JiangHu.jframe.inventory")
@ImportResource("classpath:inventory-spring.xml")
public class InventorySpringConfig {
}
