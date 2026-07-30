package io.github.JiangHu.jframe.command.resolve;

import cn.nukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 命令调用上下文 — 封装一次命令执行的全部已解析数据。
 * <p>
 * 由 {@link ArgumentResolver} 在分发前构建，包含：
 * <ul>
 *   <li>{@link #sender} — 命令发送者</li>
 *   <li>{@link #rootCommand} — 根命令名（路径第一段）</li>
 *   <li>{@link #positional} — 位置参数（去除命名参数后的 token，按出现顺序）</li>
 *   <li>{@link #named} — 命名参数（{@code --key value} / {@code -k value} 解析结果）</li>
 *   <li>{@link #pathVars} — 路径变量（由 {@link io.github.JiangHu.jframe.command.routing.PathPattern} 捕获）</li>
 *   <li>{@link #rawArgs} — 路径匹配后的剩余原始参数（不含已用于路由匹配的子命令段）</li>
 * </ul>
 *
 * <h3>不可变快照</h3>
 * 本类在构建后即不可变（内部集合以不可变视图暴露），可在多线程间安全传递。
 *
 * @see ArgumentResolver
 */
public class CommandContext {

    private final CommandSender sender;
    private final String rootCommand;
    private final List<String> positional;
    private final Map<String, String> named;
    private final Map<String, Object> pathVars;
    private final String[] rawArgs;

    /**
     * 构建命令上下文。
     *
     * @param sender       命令发送者
     * @param rootCommand  根命令名
     * @param positional   位置参数列表（已去除命名参数）
     * @param named        命名参数映射
     * @param pathVars     路径变量映射（贪婪变量值为 String[]）
     * @param rawArgs      路径匹配后的剩余原始参数（不含已用于路由匹配的子命令段）
     */
    public CommandContext(CommandSender sender, String rootCommand,
                          List<String> positional, Map<String, String> named,
                          Map<String, Object> pathVars, String[] rawArgs) {
        this.sender = sender;
        this.rootCommand = rootCommand;
        this.positional = positional;
        this.named = named;
        this.pathVars = pathVars;
        this.rawArgs = rawArgs;
    }

    /** 命令发送者 */
    public CommandSender sender() {
        return sender;
    }

    /** 根命令名（路径第一段） */
    public String rootCommand() {
        return rootCommand;
    }

    /** 位置参数列表（不可变） */
    public List<String> positional() {
        return Collections.unmodifiableList(positional);
    }

    /** 命名参数映射（不可变） */
    public Map<String, String> named() {
        return Collections.unmodifiableMap(named);
    }

    /** 路径变量映射（不可变；贪婪变量值为 String[]） */
    public Map<String, Object> pathVars() {
        return Collections.unmodifiableMap(pathVars);
    }

    /** 路径匹配后的剩余原始参数（不含已用于路由匹配的子命令段） */
    public String[] rawArgs() {
        return rawArgs;
    }
}
