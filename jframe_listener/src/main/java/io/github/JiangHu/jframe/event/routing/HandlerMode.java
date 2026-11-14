package io.github.JiangHu.jframe.event.routing;

/**
 * 包装类处理器的工作模式，由 {@link HandlerRegistry} 在注册时自动判定。
 *
 * <h3>三种模式</h3>
 * <ul>
 *   <li>{@link #OBJECT} — 有 {@code @KeyExtractor}，按事件身份提取实例，每身份独立状态</li>
 *   <li>{@link #SINGLETON} — 无 {@code @KeyExtractor}，含实例方法，框架创建唯一单例</li>
 *   <li>{@link #STATIC} — 无 {@code @KeyExtractor}，全 static 方法，走 EventEngine 兜底链</li>
 * </ul>
 *
 * <h3>判定规则（handler 级别分流，非类级别）</h3>
 * <pre>
 *   static handler                  → STATIC（始终走兜底链）
 *   实例 handler + 有 @KeyExtractor   → OBJECT
 *   实例 handler + 无 @KeyExtractor   → SINGLETON
 * </pre>
 * 一个类可同时产生 STATIC +（OBJECT 或 SINGLETON）注册：「实例 handler + static handler」
 * 混合写法时，static 部分兜底最后执行，实例部分按身份/单例正常参与优先级排序。
 *
 * @see HandlerRegistry
 */
public enum HandlerMode {

    /**
     * 对象模式：每个事件身份对应独立实例（如每玩家一个 wrapper）。
     * <p>
     * 判定条件：实例 handler 所属类声明了至少一个 {@code @KeyExtractor}。
     * <p>
     * 分发时按 {@code @KeyExtractor} 从事件提取身份，获取/创建对应实例，
     * 每个 handler 绑定到该实例调用。去重 key = 实例引用（instance）。
     */
    OBJECT,

    /**
     * 单例模式：整个类共享一个实例，适合有状态但全局唯一的处理器。
     * <p>
     * 判定条件：实例 handler 所属类无 {@code @KeyExtractor}（static handler 已分流到 STATIC）。
     * <p>
     * 注册时框架通过无参构造（或无参 {@code @InstanceProvider}）创建唯一实例，
     * 所有 handler 绑定到该单例。去重 key = 注册项引用（reg），
     * 避免多个 SINGLETON 共享同一外部单例时被吞。
     */
    SINGLETON,

    /**
     * 静态模式：所有 handler 均为 static，无实例，走 EventEngine 兜底链保证最后执行。
     * <p>
     * 判定条件：handler 为 static（与类是否有 {@code @KeyExtractor} 无关，始终走兜底链）。
     * <p>
     * STATIC <b>不参与</b>优先级排序和独占逻辑，始终在 OBJECT/SINGLETON 之后执行（兜底语义）。
     * 这符合 static 方法的 OOP 语义：static 方法不持有实例状态，作为全局兜底处理。
     * <p>
     * 若 STATIC handler 标注了 {@code priority} 或 {@code exclusive}，注册时会打 INFO 日志提示属性被忽略。
     */
    STATIC
}
