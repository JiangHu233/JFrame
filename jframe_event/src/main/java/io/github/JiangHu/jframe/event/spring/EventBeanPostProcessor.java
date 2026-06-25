package io.github.JiangHu.jframe.event.spring;

import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.routing.HandlerRegistry;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Spring Bean 后处理器：自动扫描 Bean 中的 {@link EventHandler} 方法并注册为全局处理器。
 * <p>
 * 当 Spring 容器创建任何 Bean 后，本处理器会检查该 Bean 是否包含
 * {@link EventHandler} 标注的方法。若有，则自动调用
 * {@link HandlerRegistry#register(Object)} 注册为全局事件处理器。
 *
 * <h3>使用方式</h3>
 * 用户只需在 Spring Bean 上标注 {@code @Component}，方法上标注 {@link EventHandler}，
 * 无需任何手动注册代码：
 * <pre>{@code
 * @Component
 * public class ChatLogger {
 *     @EventHandler
 *     @EventRoute(PlayerChatEvent.class)
 *     public void onChat(PlayerChatEvent event) {
 *         System.out.println(event.getMessage());
 *     }
 * }
 * }</pre>
 *
 * <h3>注意</h3>
 * <ul>
 *   <li>仅扫描 Spring 容器管理的<b>单例</b> Bean</li>
 *   <li>动态创建的包装类需手动调用 {@link HandlerRegistry#register(Class)}</li>
 *   <li>全局处理器不使用 {@code @KeyExtractor}（身份恒为 GLOBAL）</li>
 * </ul>
 *
 * @see HandlerRegistry#register(Object)
 * @see EventHandler
 */
public class EventBeanPostProcessor implements BeanPostProcessor {

    private final HandlerRegistry handlerRegistry;

    public EventBeanPostProcessor(HandlerRegistry handlerRegistry) {
        this.handlerRegistry = handlerRegistry;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (hasEventHandlerMethods(bean.getClass())) {
            handlerRegistry.register(bean);
        }
        return bean;
    }

    /**
     * 快速检查类中是否包含 {@link EventHandler} 标注的方法。
     *
     * @param clazz 要检查的类
     * @return 是否包含 {@link EventHandler} 方法
     */
    private boolean hasEventHandlerMethods(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isAnnotationPresent(EventHandler.class)) {
                    return true;
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }
}
