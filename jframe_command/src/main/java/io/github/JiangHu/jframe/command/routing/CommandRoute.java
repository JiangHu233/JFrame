package io.github.JiangHu.jframe.command.routing;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import io.github.JiangHu.jframe.command.annotation.CommandMapping;
import io.github.JiangHu.jframe.command.annotation.CommandParam;
import io.github.JiangHu.jframe.command.annotation.PathVariable;
import io.github.JiangHu.jframe.command.annotation.RawArgs;
import io.github.JiangHu.jframe.command.annotation.Sender;
import io.github.JiangHu.jframe.command.resolve.ArgumentConversionException;
import io.github.JiangHu.jframe.command.resolve.ArgumentResolver;
import io.github.JiangHu.jframe.command.resolve.CommandContext;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 命令路由 — 封装一个被 {@link CommandMapping @CommandMapping} 标注的方法。
 * <p>
 * 在注册时<b>一次性预解析</b>方法参数的绑定策略（{@link ParamBinding}），
 * 分发时按策略从 {@link CommandContext} 取值、做类型转换、反射调用方法。
 * 合并了「模板元数据」与「参数适配器」两层职责（命令场景无需事件模块的实例工厂复杂度）。
 *
 * <h3>参数绑定优先级（每个参数独立判定）</h3>
 * <ol>
 *   <li>{@link Sender @Sender} → 注入发送者（Player 类型强制仅玩家可用）</li>
 *   <li>{@link RawArgs @RawArgs} → 注入原始参数数组</li>
 *   <li>{@link PathVariable @PathVariable} → 路径变量（贪婪变量可绑 String[]/String）</li>
 *   <li>{@link CommandParam @CommandParam} → 命名参数（required/defaultValue）</li>
 *   <li>无注解 → 位置参数（按序消费剩余 token，自动类型转换）</li>
 * </ol>
 *
 * <h3>不可变性</h3>
 * 构造后绑定策略不再变化，线程安全。同一 CommandRoute 可被多线程并发调用。
 *
 * @see PathPattern
 * @see CommandRegistry
 */
public class CommandRoute {

    /** 控制器单例（注册时创建一次） */
    private final Object controller;

    /** 要调用的方法（已设置 accessible） */
    private final Method method;

    /** 路径模式 */
    private final PathPattern pattern;

    /** @CommandMapping 元数据 */
    private final CommandMapping mapping;

    /** 预解析的参数绑定策略（与方法参数一一对应） */
    private final ParamBinding[] bindings;

    /**
     * 构造命令路由。
     *
     * @param controller  控制器实例
     * @param method      标注了 @CommandMapping 的方法
     * @param pattern     解析后的路径模式
     * @param mapping     @CommandMapping 注解实例
     */
    public CommandRoute(Object controller, Method method, PathPattern pattern, CommandMapping mapping) {
        this.controller = controller;
        this.method = method;
        this.method.setAccessible(true);
        this.pattern = pattern;
        this.mapping = mapping;
        this.bindings = parseBindings(method);
    }

    /**
     * 预解析方法参数的绑定策略。
     */
    private ParamBinding[] parseBindings(Method m) {
        Parameter[] params = m.getParameters();
        ParamBinding[] result = new ParamBinding[params.length];
        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];
            Class<?> type = p.getType();

            if (p.isAnnotationPresent(Sender.class)) {
                result[i] = new ParamBinding(Kind.SENDER, null, type, false, null);
            } else if (p.isAnnotationPresent(RawArgs.class)) {
                if (type != String[].class) {
                    throw new IllegalArgumentException(
                            "@RawArgs 参数必须是 String[] 类型: " + m.getName());
                }
                result[i] = new ParamBinding(Kind.RAW_ARGS, null, type, false, null);
            } else if (p.isAnnotationPresent(PathVariable.class)) {
                PathVariable pv = p.getAnnotation(PathVariable.class);
                String name = pv.value().isEmpty() ? p.getName() : pv.value();
                result[i] = new ParamBinding(Kind.PATH_VARIABLE, name, type, false, null);
            } else if (p.isAnnotationPresent(CommandParam.class)) {
                CommandParam cp = p.getAnnotation(CommandParam.class);
                String name = cp.value().isEmpty() ? p.getName() : cp.value();
                result[i] = new ParamBinding(Kind.COMMAND_PARAM, name, type, cp.required(), cp.defaultValue());
            } else {
                // 无注解：先尝试按名匹配路径变量（-parameters 时生效），否则位置参数
                result[i] = new ParamBinding(Kind.POSITIONAL, p.getName(), type, false, null);
            }
        }
        return result;
    }

    /**
     * 执行命令：绑定参数并反射调用方法。
     *
     * @param ctx 命令上下文（已含路径变量、位置参数、命名参数）
     * @return 执行结果（成功/失败 + 错误消息）
     */
    public InvokeResult invoke(CommandContext ctx) {
        Object[] args = new Object[bindings.length];
        int positionalIndex = 0;
        List<String> positional = ctx.positional();

        for (int i = 0; i < bindings.length; i++) {
            ParamBinding b = bindings[i];
            try {
                switch (b.kind) {
                    case SENDER -> {
                        if (b.type == Player.class) {
                            if (!(ctx.sender() instanceof Player player)) {
                                return InvokeResult.failure("§c该命令只能由玩家在游戏内执行");
                            }
                            args[i] = player;
                        } else {
                            args[i] = ctx.sender();
                        }
                    }
                    case RAW_ARGS -> args[i] = ctx.rawArgs();
                    case PATH_VARIABLE -> {
                        Object resolved = resolvePathVariable(b, ctx);
                        if (resolved instanceof BindingFailure f) return InvokeResult.failure(f.message);
                        args[i] = resolved;
                    }
                    case COMMAND_PARAM -> {
                        Object resolved = resolveCommandParam(b, ctx);
                        if (resolved instanceof BindingFailure f) return InvokeResult.failure(f.message);
                        args[i] = resolved;
                    }
                    case POSITIONAL -> {
                        // 先尝试按名匹配路径变量（便捷自动绑定）
                        Object pathVar = ctx.pathVars().get(b.name);
                        if (pathVar != null && isCompatiblePathVar(pathVar, b.type)) {
                            args[i] = adaptPathVariable(pathVar, b.type, ctx);
                            continue;
                        }
                        // 位置消费
                        if (positionalIndex < positional.size()) {
                            args[i] = ArgumentResolver.convert(positional.get(positionalIndex++), b.type, ctx.sender());
                        } else {
                            args[i] = defaultForType(b.type);
                        }
                    }
                }
            } catch (ArgumentConversionException e) {
                return InvokeResult.failure("§c参数错误: " + e.getMessage());
            }
        }

        try {
            method.invoke(controller, args);
            return InvokeResult.ok();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return InvokeResult.failure("§c命令执行异常: " + cause.getMessage());
        }
    }

    /**
     * 解析路径变量绑定。
     * <p>
     * 贪婪变量（String[]）可绑到 String[] 或 String（空格连接）；单值变量正常转换。
     */
    private Object resolvePathVariable(ParamBinding b, CommandContext ctx) {
        Object raw = ctx.pathVars().get(b.name);
        if (raw == null) {
            return new BindingFailure("§c缺少路径变量: " + b.name);
        }
        return adaptPathVariable(raw, b.type, ctx);
    }

    /**
     * 将路径变量原始值适配到目标类型。
     */
    private Object adaptPathVariable(Object raw, Class<?> type, CommandContext ctx) {
        if (raw instanceof String[] arr) {
            // 贪婪变量
            if (type == String[].class) return arr;
            if (type == String.class) return String.join(" ", arr);
            if (arr.length == 0) return defaultForType(type);
            return ArgumentResolver.convert(arr[0], type, ctx.sender());
        }
        // 单值变量
        if (type == String.class) return raw.toString();
        return ArgumentResolver.convert(raw.toString(), type, ctx.sender());
    }

    private boolean isCompatiblePathVar(Object raw, Class<?> type) {
        return true; // 适配逻辑会处理类型差异
    }

    /**
     * 解析命名参数绑定（required/defaultValue）。
     * <p>
     * 与 Spring MVC 一致：当声明了 {@code defaultValue} 时，参数<b>隐式变为可选</b>
     * （即使 {@code required=true} 也不会因缺失而失败），缺失时使用默认值。
     */
    private Object resolveCommandParam(ParamBinding b, CommandContext ctx) {
        Map<String, String> named = ctx.named();
        if (named.containsKey(b.name)) {
            return ArgumentResolver.convert(named.get(b.name), b.type, ctx.sender());
        }
        // 缺失：有默认值则用默认值（defaultValue 隐式使参数可选，与 Spring 语义一致）
        if (b.defaultValue != null && !b.defaultValue.isEmpty()) {
            return ArgumentResolver.convert(b.defaultValue, b.type, ctx.sender());
        }
        // 无默认值：required 校验
        if (b.required) {
            return new BindingFailure("§c缺少必需参数: --" + b.name);
        }
        // 可选且无默认值：类型默认值
        return defaultForType(b.type);
    }

    /**
     * 返回类型的默认值（基本类型给零值，对象给 null）。
     */
    private Object defaultForType(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == boolean.class) return false;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        return null;
    }

    // ========== Getter ==========

    public PathPattern getPattern() {
        return pattern;
    }

    public CommandMapping getMapping() {
        return mapping;
    }

    public Method getMethod() {
        return method;
    }

    public Object getController() {
        return controller;
    }

    // ========== 内部类型 ==========

    /** 参数绑定策略 */
    private record ParamBinding(Kind kind, String name, Class<?> type, boolean required, String defaultValue) {}

    /** 绑定种类 */
    private enum Kind {
        SENDER, RAW_ARGS, PATH_VARIABLE, COMMAND_PARAM, POSITIONAL
    }

    /** 绑定失败标记（携带错误消息） */
    private record BindingFailure(String message) {}

    /**
     * 执行结果。
     *
     * @param success      是否成功
     * @param errorMessage 错误消息（失败时非空，用于提示发送者）
     */
    public record InvokeResult(boolean success, String errorMessage) {
        static InvokeResult ok() {
            return new InvokeResult(true, null);
        }

        static InvokeResult failure(String message) {
            return new InvokeResult(false, message);
        }
    }
}
