package io.github.JiangHu.jframe.event.config;

import cn.nukkit.plugin.PluginBase;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;

@Configuration
@ImportResource("classpath:event-spring.xml")
public class SpringConfig {
}
