package io.github.JiangHu.jframe.command.routing;

import cn.nukkit.Server;
import cn.nukkit.command.CommandSender;
import cn.nukkit.utils.MainLogger;
import io.github.JiangHu.jframe.command.annotation.CommandController;
import io.github.JiangHu.jframe.command.annotation.CommandMapping;
import io.github.JiangHu.jframe.command.resolve.ArgumentResolver;
import io.github.JiangHu.jframe.command.resolve.CommandContext;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 命令路由注册表 — 核心路由引擎：注册控制器、模式匹配、特异性排序、分发执行。
 * <p>
 * 对应 Spring MVC 的 {@code RequestMappingHandlerMapping}（路由映射）+
 * {@code RequestMappingHandlerAdapter}（参数适配/调用）的合体。
 *
 * <h3>架构定位（单向 DAG）</h3>
 * <pre>
 *   CommandRegistry（本类，无依赖）
 *       ↑
 *   CommandEngine（依赖本类：分发委托 + 根命令同步）
 *       ↑
 *   CommandAPI（公开门面）
 * </pre>
 * 本类不依赖引擎，注册时即可建立全部路由表；引擎负责对接 Nukkit。
 *
 * <h3>分发流程</h3>
 * <pre>
 *   Nukkit 触发命令 (root, args)
 *     ↓
 *   ① 解析 args → 位置参数 + 命名参数
 *   ② 构建完整路径 = [root] + 位置参数
 *   ③ 遍历所有 CommandRoute，pattern.match(完整路径)
 *   ④ 收集所有匹配项，按特异性排序，取最优
 *   ⑤ 权限校验 → 构建 CommandContext → 反射调用
 * </pre>
 *
 * <h3>线程安全</h3>
 * 路由表使用 {@link CopyOnWriteArrayList}（读多写少），匹配/分发无锁并发安全。
 *
 * @see CommandRoute
 * @see PathPattern
 */
public class CommandRegistry {

    /** 已注册的路由列表（读多写少） */
    private final List<CommandRoute> routes = new CopyOnWriteArrayList<>();

    /** 控制器类 → 其路由列表（用于注销） */
    private final Map<Class<?>, List<CommandRoute>> classToRoutes = new ConcurrentHashMap<>();

    /** 根命令 → 别名数组（用于 Nukkit 注册） */
    private final Map<String, List<String>> rootAliases = new ConcurrentHashMap<>();

    /** 别名 → 根命令（用于分发时别名解析） */
    private final Map<String, String> aliasToRoot = new ConcurrentHashMap<>();

    /**
     * 注册命令控制器。
     * <p>
     * 扫描类中的 {@link CommandMapping @CommandMapping} 方法，为每个方法构建
     * {@link CommandRoute}（含路径模式与参数绑定策略）。控制器实例在注册时创建一次并缓存。
     *
     * @param controllerClass 控制器类（建议标注 {@link CommandController}）
     * @param <T>             控制器类型
     */
    public <T> void register(Class<T> controllerClass) {
        // 基础路径：@CommandController 的 value，无注解则为空
        String basePath = "";
        CommandController cc = controllerClass.getAnnotation(CommandController.class);
        if (cc != null) {
            basePath = cc.value() == null ? "" : cc.value().trim();
        }

        // 创建控制器单例
        Object instance;
        try {
            instance = createInstance(controllerClass);
        } catch (Exception e) {
            logError(
                    "[CommandRegistry] 无法实例化控制器: " + controllerClass.getName()
                            + "（需要无参构造函数）", e);
            return;
        }

        // 扫描 @CommandMapping 方法
        List<CommandRoute> created = new ArrayList<>();
        for (Method method : controllerClass.getDeclaredMethods()) {
            CommandMapping mapping = method.getAnnotation(CommandMapping.class);
            if (mapping == null) continue;

            String fullPath = joinPath(basePath, mapping.value());
            PathPattern pattern = new PathPattern(fullPath);

            String root = pattern.rootCommand();
            if (root == null) {
                logWarn(
                        "[CommandRegistry] 跳过路由（首段非静态根命令）: "
                                + controllerClass.getName() + "." + method.getName()
                                + " 路径=\"" + fullPath + "\"。请以静态根命令开头。");
                continue;
            }

            try {
                CommandRoute route = new CommandRoute(instance, method, pattern, mapping);
                created.add(route);
                // 收集别名
                registerAliases(root, mapping.aliases());
            } catch (Exception e) {
                logError(
                        "[CommandRegistry] 注册路由失败: " + controllerClass.getName()
                                + "." + method.getName(), e);
            }
        }

        if (created.isEmpty()) {
            logWarn(
                    "[CommandRegistry] 控制器 " + controllerClass.getName() + " 无有效 @CommandMapping 方法。");
            return;
        }

        classToRoutes.put(controllerClass, created);
        routes.addAll(created);
        logInfo(
                "[CommandRegistry] 已注册控制器 " + controllerClass.getName()
                        + "，共 " + created.size() + " 个路由。");
    }

    /**
     * 注销整个控制器的所有路由。
     *
     * @param controllerClass 控制器类
     */
    public void unregister(Class<?> controllerClass) {
        List<CommandRoute> removed = classToRoutes.remove(controllerClass);
        if (removed == null) return;
        routes.removeAll(removed);
    }

    /**
     * 分发命令：匹配最优路由并执行。
     *
     * @param sender       命令发送者
     * @param rootCommand  根命令名（可能是别名）
     * @param args         原始参数
     * @return 执行结果（成功/失败 + 消息）
     */
    public CommandRoute.InvokeResult dispatch(CommandSender sender, String rootCommand, String[] args) {
        // ① 别名解析
        String canonicalRoot = aliasToRoot.getOrDefault(rootCommand, rootCommand);

        // ② 解析参数
        ArgumentResolver.ParsedArgs parsed = ArgumentResolver.parseArgs(args);

        // ③ 构建完整路径 = [canonicalRoot] + 位置参数
        List<String> fullPath = new ArrayList<>();
        fullPath.add(canonicalRoot);
        fullPath.addAll(parsed.positional());

        // ④ 匹配所有路由，收集命中项
        List<MatchedRoute> matched = new ArrayList<>();
        for (CommandRoute route : routes) {
            PathPattern.MatchResult mr = route.getPattern().match(fullPath);
            if (mr != null) {
                matched.add(new MatchedRoute(route, mr));
            }
        }

        if (matched.isEmpty()) {
            return CommandRoute.InvokeResult.failure(
                    "§c未知的子命令。输入 /" + canonicalRoot + " 查看用法。");
        }

        // ⑤ 特异性排序，取最优
        matched.sort((a, b) -> PathPattern.SPECIFICITY_COMPARATOR.compare(
                a.route.getPattern(), b.route.getPattern()));
        MatchedRoute best = matched.get(0);

        // ⑥ 权限校验
        String permission = best.route.getMapping().permission();
        if (permission != null && !permission.isEmpty() && !sender.hasPermission(permission)) {
            return CommandRoute.InvokeResult.failure("§c你没有权限执行此命令。");
        }

        // ⑦ 构建上下文（剩余位置参数 = 完整路径中模式未消耗的部分）
        int consumed = best.match.consumed();
        List<String> remainder = consumed < fullPath.size()
                ? new ArrayList<>(fullPath.subList(consumed, fullPath.size()))
                : Collections.emptyList();

        CommandContext ctx = new CommandContext(
                sender, canonicalRoot,
                remainder, parsed.named(),
                best.match.pathVars(), args);

        // ⑧ 执行
        return best.route.invoke(ctx);
    }

    /**
     * 返回所有已注册的根命令（静态首段）。
     */
    public Set<String> getRootCommands() {
        Set<String> roots = ConcurrentHashMap.newKeySet();
        for (CommandRoute route : routes) {
            String root = route.getPattern().rootCommand();
            if (root != null) roots.add(root);
        }
        return roots;
    }

    /**
     * 返回根命令 → 别名数组映射（用于 Nukkit 注册）。
     */
    public Map<String, String[]> getRootAliases() {
        Map<String, String[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : rootAliases.entrySet()) {
            result.put(e.getKey(), e.getValue().toArray(new String[0]));
        }
        return result;
    }

    public List<CommandRoute> getRoutes() {
        return Collections.unmodifiableList(routes);
    }

    // ========== 内部工具 ==========

    /**
     * 拼接基础路径与方法路径。
     * <p>
     * 两者都为空 → 空（根命令本身）。
     * 一方为空 → 取另一方。
     * 都非空 → 以空格连接。
     */
    private static String joinPath(String base, String methodPath) {
        String b = base == null ? "" : base.trim();
        String m = methodPath == null ? "" : methodPath.trim();
        if (b.isEmpty()) return m;
        if (m.isEmpty()) return b;
        return b + " " + m;
    }

    /**
     * 通过无参构造函数创建控制器实例。
     */
    private static <T> T createInstance(Class<T> clazz) throws Exception {
        return clazz.getDeclaredConstructor().newInstance();
    }

    /**
     * 注册根命令的别名。
     */
    private void registerAliases(String root, String[] aliases) {
        if (aliases == null) return;
        List<String> list = rootAliases.computeIfAbsent(root, k -> new CopyOnWriteArrayList<>());
        for (String alias : aliases) {
            if (alias == null || alias.isBlank()) continue;
            String a = alias.trim();
            if (!list.contains(a)) list.add(a);
            aliasToRoot.putIfAbsent(a, root);
        }
    }

    // ========== 日志（空安全：测试环境无 Server 实例时回退到控制台） ==========

    /**
     * 获取 Nukkit 日志器；当 {@link Server} 实例不存在（如单元测试环境）时返回 null。
     */
    private static MainLogger nukkitLogger() {
        try {
            Server server = Server.getInstance();
            return server == null ? null : server.getLogger();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void logInfo(String msg) {
        MainLogger log = nukkitLogger();
        if (log != null) {
            log.info(msg);
        } else {
            System.out.println("[CommandRegistry][INFO] " + msg);
        }
    }

    private static void logWarn(String msg) {
        MainLogger log = nukkitLogger();
        if (log != null) {
            log.warning(msg);
        } else {
            System.out.println("[CommandRegistry][WARN] " + msg);
        }
    }

    private static void logError(String msg, Throwable t) {
        MainLogger log = nukkitLogger();
        if (log != null) {
            log.error(msg, t);
        } else {
            System.err.println("[CommandRegistry][ERROR] " + msg);
            if (t != null) {
                t.printStackTrace();
            }
        }
    }

    /**
     * 已匹配的路由 + 匹配结果。
     */
    private record MatchedRoute(CommandRoute route, PathPattern.MatchResult match) {}
}
