package io.github.JiangHu.jframe.engine.ecs.exception;

import io.github.JiangHu.jframe.core.JFrameException;

/**
 * ECS 模块配置/注册异常 — 定义文件解析、注册名校验、参数注入等 fail-fast 场景统一抛出本异常。
 *
 * <h3>典型触发场景</h3>
 * <ul>
 *   <li>GameObject 定义文件中引用了未注册的组件 id / 选择器名 / 生成器名</li>
 *   <li>同一来源出现重名 GameObject / 注册表出现重名注册</li>
 *   <li>{@code params} 中出现目标类上不存在的字段名，或类型强转失败</li>
 *   <li>类名解析失败（简单名在 {@code cn.nukkit.entity[*]} / {@code cn.nukkit.blockentity.*} 各前缀下均找不到）</li>
 *   <li>组件类缺少可访问无参构造、{@code @OnEcsEvent} 方法签名非法</li>
 * </ul>
 *
 * @see JFrameException
 */
public class EcsConfigException extends JFrameException {

    /**
     * 构造带详细消息的异常。
     *
     * @param message 详细消息
     */
    public EcsConfigException(String message) {
        super(message);
    }

    /**
     * 构造带详细消息和原因的异常。
     *
     * @param message 详细消息
     * @param cause   根本原因
     */
    public EcsConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
