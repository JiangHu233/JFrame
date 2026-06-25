package io.github.JiangHu.jframe.command;

import cn.nukkit.Server;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandExecutor;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.PluginCommand;
import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.command.routing.CommandRegistry;
import io.github.JiangHu.jframe.command.routing.CommandRoute;
import io.github.JiangHu.jframe.core.module.PluginAware;
import lombok.Getter;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命令引擎（<b>内部模块</b>）：对接 Nukkit 底层命令系统，注册根命令并转发分发。
 * <p>
 * 本类是命令系统的 Nukkit 桥接层，只负责「向 CommandMap 注册根命令」和
 * 「把 Nukkit 命令调用转发给 {@link CommandRegistry}」，<b>不包含</b>路由匹配逻辑。
 * 用户应通过 {@link CommandAPI} 间接使用本类。
 *
 * <h3>架构定位（单向 DAG）</h3>
 * <pre>
 *   CommandRegistry（无依赖）
 *       ↑
 *   CommandEngine（本类，依赖 Registry，实现 PluginAware + CommandExecutor）
 *       ↑
 *   CommandAPI（公开门面）
 * </pre>
 *
 * <h3>根命令注册时机</h3>
 * <p>
 * {@link CommandAPI#register} 每次注册控制器后会调用 {@link #syncRoots()}，
 * 将 Registry 中新出现的根命令注册到 Nukkit。若此时插件尚未绑定（{@link #plugin} 为 null），
 * 注册请求会暂存于 {@link #pendingRoots}，待 {@link #bindPlugin} 调用后统一刷新
 * （与 {@code EventEngine} 的待注册队列机制一致）。
 *
 * @see CommandAPI
 * @see CommandRegistry
 */
public class CommandEngine implements PluginAware, CommandExecutor {

    /** 路由注册表（构造器注入） */
    private final CommandRegistry registry;

    /** 关联的插件实例，用于创建 PluginCommand 并注册到 CommandMap */
    @Getter
    private Plugin plugin;

    /** 已向 Nukkit 注册的根命令集合，避免重复注册 */
    private final Set<String> registeredRoots = ConcurrentHashMap.newKeySet();

    /** 待注册队列：plugin 绑定前暂存根命令 */
    private final Set<String> pendingRoots = ConcurrentHashMap.newKeySet();

    /**
     * 构造命令引擎。
     *
     * @param registry 路由注册表
     */
    public CommandEngine(CommandRegistry registry) {
        this.registry = registry;
    }

    /**
     * 同步根命令：将 Registry 中尚未注册的根命令注册到 Nukkit。
     * <p>
     * 由 {@link CommandAPI#register} 在每次注册控制器后调用。
     * 插件未绑定时暂存到待注册队列。
     */
    public void syncRoots() {
        for (String root : registry.getRootCommands()) {
            if (!registeredRoots.contains(root) && !pendingRoots.contains(root)) {
                if (plugin == null) {
                    pendingRoots.add(root);
                } else {
                    doRegister(root);
                }
            }
        }
    }

    /**
     * 绑定关联插件实例，并刷新所有待注册的根命令。
     * <p>
     * 实现自 {@link PluginAware}。{@code JFrameMain} 在导入命令模块（或插件启用）后自动调用。
     *
     * @param plugin 插件实例
     */
    @Override
    public void bindPlugin(Plugin plugin) {
        if (this.plugin != null) {
            return; // 已绑定
        }
        this.plugin = plugin;
        if (plugin != null) {
            // 刷新待注册队列
            for (String root : pendingRoots) {
                doRegister(root);
            }
            pendingRoots.clear();
            // 防御性同步：注册此后新增的根命令
            syncRoots();
        }
    }

    /**
     * 向 Nukkit CommandMap 注册一个根命令。
     * <p>
     * 创建 {@link PluginCommand}，设置描述/用法/权限/别名，并以本引擎为执行器，
     * 注册到 {@code Server.getCommandMap()}。
     *
     * @param root 根命令名
     */
    private void doRegister(String root) {
        if (!registeredRoots.add(root)) {
            return; // 已注册
        }
        try {
            PluginCommand<Plugin> cmd = new PluginCommand<>(root, plugin);
            cmd.setExecutor(this);

            // 从 Registry 聚合元数据
            CommandMeta meta = collectMeta(root);
            if (meta.description != null && !meta.description.isEmpty()) {
                cmd.setDescription(meta.description);
            }
            if (meta.usage != null && !meta.usage.isEmpty()) {
                cmd.setUsage(meta.usage);
            }

            // 别名
            Map<String, String[]> aliasMap = registry.getRootAliases();
            String[] aliases = aliasMap.get(root);
            if (aliases != null && aliases.length > 0) {
                cmd.setAliases(aliases);
            }

            String prefix = plugin.getName().toLowerCase();
            Server.getInstance().getCommandMap().register(prefix, cmd);
            Server.getInstance().getLogger().info(
                    "[CommandEngine] 已注册根命令: /" + root
                            + (aliases != null && aliases.length > 0
                            ? " (别名: " + String.join(", ", aliases) + ")" : ""));
        } catch (Exception e) {
            Server.getInstance().getLogger().error(
                    "[CommandEngine] 注册根命令失败: " + root, e);
            registeredRoots.remove(root); // 回滚，允许重试
        }
    }

    /**
     * 聚合某根命令下所有路由的元数据（描述/用法）。
     */
    private CommandMeta collectMeta(String root) {
        String description = null;
        Set<String> usages = new HashSet<>();
        for (CommandRoute route : registry.getRoutes()) {
            if (root.equals(route.getPattern().rootCommand())) {
                String desc = route.getMapping().desc();
                if (description == null && desc != null && !desc.isEmpty()) {
                    description = desc;
                }
                String usage = route.getMapping().usage();
                usages.add(usage != null && !usage.isEmpty() ? usage
                        : "/" + route.getPattern().patternString());
            }
        }
        String usage = usages.isEmpty() ? null : String.join("\n", usages);
        return new CommandMeta(description, usage);
    }

    /**
     * Nukkit 命令执行回调：委托给 {@link CommandRegistry#dispatch}。
     * <p>
     * 执行失败时向发送者返回错误消息。
     *
     * @param sender  命令发送者
     * @param command 命令对象
     * @param label   使用的标签（可能是别名）
     * @param args    参数
     * @return 是否已处理（始终 true，避免 Nukkit 回显默认用法）
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        CommandRoute.InvokeResult result;
        try {
            result = registry.dispatch(sender, command.getName(), args);
        } catch (Exception e) {
            Server.getInstance().getLogger().error(
                    "[CommandEngine] 命令分发异常: /" + command.getName(), e);
            sender.sendMessage("§c命令执行时发生内部错误。");
            return true;
        }

        if (!result.success() && result.errorMessage() != null) {
            sender.sendMessage(result.errorMessage());
        }
        return true;
    }

    /** 命令元数据聚合结果 */
    private record CommandMeta(String description, String usage) {}
}
