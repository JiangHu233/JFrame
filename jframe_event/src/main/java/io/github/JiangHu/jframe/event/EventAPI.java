package io.github.JiangHu.jframe.event;

import cn.nukkit.event.Event;
import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.event.routing.HandlerRegistry;
import io.github.JiangHu.jframe.event.scan.WrapperScanner;

import java.util.List;

/**
 * 事件服务（<b>面向用户的统一入口</b>）：暴露事件系统的全部公开 API。
 * <p>
 * 本类是一个轻量门面（facade），自身不持有业务逻辑，仅做<b>委托转发</b>：
 * <ul>
 *   <li>{@code register/unregister/evict} → 委托给 {@link HandlerRegistry}（路由引擎）</li>
 *   <li>{@code subscribe/unsubscribe} → 转发给 {@link EventEngine}（Nukkit 桥接引擎）</li>
 * </ul>
 *
 * <h3>为什么拆分出 EventEngine？</h3>
 * <p>
 * 早期版本中，本类同时承担"Nukkit 订阅"和"路由委托"两项职责，
 * 导致 {@code EventAPI ↔ HandlerRegistry} 形成构造器循环依赖，Spring 无法创建。
 * <p>
 * 拆分后依赖关系变为<b>单向 DAG</b>（无环）：
 * <pre>
 *   EventEngine（无依赖）
 *       ↑
 *   HandlerRegistry（构造器依赖 EventEngine）
 *       ↑
 *   EventAPI（构造器依赖 HandlerRegistry + EventEngine）  ← 本类
 * </pre>
 * 三者均可使用构造器注入，Spring 按 EventEngine → HandlerRegistry → EventAPI 顺序创建。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 注册包装类（委托给 HandlerRegistry）
 * eventService.register(PlayerStatWrapper.class);
 *
 * // 订阅原始事件（转发给 EventEngine）
 * eventService.subscribe(PlayerMoveEvent.class, event -> {
 *     System.out.println("玩家移动了");
 *     return false;
 * });
 * }</pre>
 *
 * @see EventEngine
 * @see HandlerRegistry
 * @see EventConsumer
 */
public class EventAPI {

    /** 路由引擎：register/unregister/evict 的实际执行者 */
    private final HandlerRegistry handlerRegistry;

    /** Nukkit 桥接引擎：subscribe/unsubscribe 的实际执行者 */
    private final EventEngine engine;

    /**
     * 构造事件服务。
     * <p>
     * 通过 Spring 构造器注入。创建顺序由依赖关系保证：
     * EventEngine（无依赖）→ HandlerRegistry（依赖 EventEngine）→ EventAPI（依赖两者）。
     *
     * @param handlerRegistry 路由引擎
     * @param engine          Nukkit 桥接引擎
     */
    public EventAPI(HandlerRegistry handlerRegistry, EventEngine engine) {
        this.handlerRegistry = handlerRegistry;
        this.engine = engine;
    }

    // ========== 路由委托（→ HandlerRegistry） ==========

    /**
     * 注册包装类为对象处理器。
     * <p>
     * 扫描类中的 {@link io.github.JiangHu.jframe.event.annotation.KeyExtractor}、
     * {@link io.github.JiangHu.jframe.event.annotation.InstanceProvider}、
     * {@link io.github.JiangHu.jframe.event.annotation.EventHandler} 方法，
     * 创建类级注册。实例在分发时通过工厂方法或默认缓存按需创建。
     * <p>
     * <b>必须声明至少一个 {@code @KeyExtractor}</b>，否则无法从事件中提取身份，注册将被拒绝。
     *
     * @param wrapperClass 包装类
     * @param <T>          包装类型
     */
    public <T> void register(Class<T> wrapperClass) {
        handlerRegistry.register(wrapperClass);
    }

    /**
     * 注销整个包装类的所有处理器，并清空其默认缓存。
     *
     * @param wrapperClass 要注销的包装类
     */
    public void unregister(Class<?> wrapperClass) {
        handlerRegistry.unregister(wrapperClass);
    }

    /**
     * 从默认缓存中驱逐特定身份的实例（如玩家下线时清理）。
     * <p>
     * 仅对使用默认缓存（无 {@code @InstanceProvider}）的包装类有效。
     *
     * @param wrapperClass 包装类
     * @param identity     要驱逐的身份标识
     */
    public void evict(Class<?> wrapperClass, Object identity) {
        handlerRegistry.evict(wrapperClass, identity);
    }

    /**
     * 包扫描注册（便捷方法）。
     * <p>
     * 扫描指定基础包（含子包）下所有标注了
     * {@link io.github.JiangHu.jframe.event.annotation.Wrapper @Wrapper} 的类，
     * 自动调用 {@link #register} 注册。类似 Spring 的 {@code @ComponentScan}。
     * <p>
     * 内部委托给 {@link WrapperScanner}（临时创建，无状态，不引入 Spring 循环依赖）。
     * 使用线程上下文类加载器（TCCL），因此调用方需确保 TCCL 指向插件类加载器
     * （在 Nukkit 插件 {@code onEnable} 中切换即可，参见示例）。
     *
     * <h3>使用示例</h3>
     * <pre>{@code
     * // 一行扫描注册整个 wrapper 包下的所有 @Wrapper 类
     * eventService.scan("io.github.JiangHu.jframe.example.wrapper");
     * }</pre>
     *
     * @param basePackages 要扫描的基础包名（可变参数）
     * @return 实际注册成功的包装类列表
     * @see WrapperScanner
     * @see io.github.JiangHu.jframe.event.annotation.Wrapper
     */
    public List<Class<?>> scan(String... basePackages) {
        return new WrapperScanner(this).scan(basePackages);
    }

    /**
     * 包扫描注册（指定类加载器）。
     * <p>
     * 与 {@link #scan(String...)} 相同，但显式指定类加载器，
     * 适用于需要精确控制类加载来源的场景。
     *
     * @param classLoader  类加载器
     * @param basePackages 要扫描的基础包名（可变参数）
     * @return 实际注册成功的包装类列表
     */
    public List<Class<?>> scan(ClassLoader classLoader, String... basePackages) {
        return new WrapperScanner(this).scan(classLoader, basePackages);
    }

    // ========== 订阅转发（→ EventEngine） ==========

    /**
     * 订阅指定事件类型。
     * <p>
     * 底层以固定 {@link EventEngine#REGISTER_PRIORITY} 向 Nukkit 注册。
     * 同一事件类型只向 Nukkit 注册一次，多个消费者共享同一注册。
     *
     * @param eventType 事件类型
     * @param consumer  事件消费者
     * @param <T>       事件泛型
     */
    public <T extends Event> void subscribe(Class<T> eventType, EventConsumer<T> consumer) {
        engine.subscribe(eventType, consumer);
    }

    /**
     * 注销指定事件类型下的某个消费者。
     *
     * @param eventType 事件类型
     * @param consumer  要注销的消费者
     * @param <T>       事件泛型
     */
    public <T extends Event> void unsubscribe(Class<T> eventType, EventConsumer<T> consumer) {
        engine.unsubscribe(eventType, consumer);
    }

    /**
     * 注销某个消费者在所有事件类型上的订阅。
     *
     * @param consumer 要注销的消费者
     */
    public void unsubscribeAll(EventConsumer<?> consumer) {
        engine.unsubscribeAll(consumer);
    }

    // ========== 插件绑定（转发给 EventEngine） ==========

    /**
     * 绑定关联插件实例，并刷新所有待注册的订阅。
     * <p>
     * 转发给 {@link EventEngine#bindPlugin(Plugin)}。
     * 当通过 {@code JFrameMain} 使用时，框架会自动扫描 {@code PluginAware} Bean
     * （即 {@link EventEngine}）并调用其 {@code bindPlugin}，无需手动调用。
     * <p>
     * 当脱离 {@code JFrameMain} 直接使用 Spring 时（如示例插件），需手动调用本方法。
     *
     * @param plugin 插件实例
     */
    public void bindPlugin(Plugin plugin) {
        engine.bindPlugin(plugin);
    }

    /**
     * 获取关联的插件实例。
     *
     * @return 插件实例，或 null（尚未绑定）
     */
    public Plugin getPlugin() {
        return engine.getPlugin();
    }
}
