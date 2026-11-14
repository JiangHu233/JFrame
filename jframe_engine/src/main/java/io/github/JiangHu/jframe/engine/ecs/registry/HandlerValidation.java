package io.github.JiangHu.jframe.engine.ecs.registry;

import cn.nukkit.event.Event;
import io.github.JiangHu.jframe.engine.ecs.annotation.OnEcsEvent;
import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * {@code @OnEcsEvent} 处理器注册期校验共享工具 — 组件处理器（{@link ComponentRegistry}）
 * 与系统处理器（{@link SystemEventRegistry}）共用的签名校验。
 */
final class HandlerValidation {

    private HandlerValidation() {
    }

    /**
     * 校验处理器方法签名：public 非静态、恰好 1 个 {@code cn.nukkit.event.Event} 子类参数。
     *
     * @param method 处理方法
     * @param where  出处描述（异常消息用，如 {@code MyComp#onHit}）
     * @return 监听的事件类型（方法参数类型）
     * @throws EcsConfigException 签名非法
     */
    static Class<? extends Event> requireHandlerSignature(Method method, String where) {
        int mods = method.getModifiers();
        if (!Modifier.isPublic(mods) || Modifier.isStatic(mods)) {
            throw new EcsConfigException("@OnEcsEvent 方法必须为 public 非静态: " + where);
        }
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length != 1 || !Event.class.isAssignableFrom(paramTypes[0])) {
            throw new EcsConfigException("@OnEcsEvent 方法须恰好 1 个 cn.nukkit.event.Event 子类参数: " + where);
        }
        return paramTypes[0].asSubclass(Event.class);
    }
}
