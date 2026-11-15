package io.github.JiangHu.jframe.transform;

import io.github.JiangHu.jframe.transform.bridge.MethodHook;
import io.github.JiangHu.jframe.transform.bridge.ReplaceHook;
import io.github.JiangHu.jframe.transform.registry.HookRegistry;
import io.github.JiangHu.jframe.transform.scan.HookAnnotationScanner;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件绑定注册作用域——命令式注册 / 注解扫描 / 撤销的入口（API 风格契约见方案附录 A）。
 * <p>
 * 目标为<b>任意非 bootstrap/JDK 类</b>（Nukkit 服务端类为主，服务端 classpath 库与
 * 其他插件类均可）；类名/方法名支持 {@code *} 简单通配。线程：注册可在任意线程，
 * 分发可能来自任意执行目标方法的线程。
 */
public final class TransformPluginScope {

    private final HookRegistry registry;
    private final String pluginName;
    private final ClassLoader pluginLoader;

    /**
     * 构造作用域（{@code TransformAPI.forPlugin} 创建）。
     *
     * @param registry     注册表
     * @param pluginName   归属插件名
     * @param pluginLoader 插件类加载器（scan 用）
     */
    public TransformPluginScope(HookRegistry registry, String pluginName, ClassLoader pluginLoader) {
        this.registry = registry;
        this.pluginName = pluginName;
        this.pluginLoader = pluginLoader;
    }

    // ==================== 命令式：hook / replace ====================

    /**
     * 注册方法钩子（最常用简式；before/after 空相位自动省略，注册时 needsArgs=false）。
     *
     * @param className  目标类名（支持 {@code *} 通配）
     * @param methodName 目标方法名（支持 {@code *} 通配）
     * @param hook       钩子实例
     * @return 句柄
     */
    public HookHandle hook(String className, String methodName, MethodHook hook) {
        return hook(className, methodName, null, hook, null, false);
    }

    /**
     * 注册方法钩子（指定参数类型消除重载歧义）。
     *
     * @param className  目标类名
     * @param methodName 目标方法名
     * @param paramTypes 参数类型（null = 任意重载）
     * @param hook       钩子实例
     * @return 句柄
     */
    public HookHandle hook(String className, String methodName, Class<?>[] paramTypes, MethodHook hook) {
        return hook(className, methodName, paramTypes, hook, null, false);
    }

    /**
     * 注册方法钩子（全参形态）。
     *
     * @param className  目标类名
     * @param methodName 目标方法名
     * @param paramTypes 参数类型（null = 任意重载）
     * @param hook       钩子实例
     * @param priority   优先级（null = NORMAL）
     * @param needsArgs  before 阶段是否需要参数数组（免参数形态免装箱）
     * @return 句柄
     */
    public HookHandle hook(String className, String methodName, Class<?>[] paramTypes,
                           MethodHook hook, HookPriority priority, boolean needsArgs) {
        return registry.register(pluginName, className, methodName, toNames(paramTypes),
                hook, null, null, priority, needsArgs);
    }

    /**
     * 整方法接管（REPLACE 简式，needsArgs=true）：原方法体不执行，
     * {@code replace(ctx)} 返回值即方法返回值、抛出即方法异常。
     *
     * @param className   目标类名
     * @param methodName  目标方法名
     * @param replacement 替换实现
     * @return 句柄
     */
    public HookHandle replace(String className, String methodName, ReplaceHook replacement) {
        return replace(className, methodName, null, replacement, null, true);
    }

    /**
     * 整方法接管（全参形态）。
     *
     * @param className   目标类名
     * @param methodName  目标方法名
     * @param paramTypes  参数类型（null = 任意重载）
     * @param replacement 替换实现
     * @param priority    优先级（null = NORMAL）
     * @param needsArgs   是否传参数数组（默认建议 true）
     * @return 句柄
     */
    public HookHandle replace(String className, String methodName, Class<?>[] paramTypes,
                              ReplaceHook replacement, HookPriority priority, boolean needsArgs) {
        return registry.register(pluginName, className, methodName, toNames(paramTypes),
                null, replacement, null, priority, needsArgs);
    }

    // ==================== 注解声明式 ====================

    /**
     * 扫描本插件包内全部 {@code @HookOn} 注解方法并注册（AOP 手感，推荐主路径）。
     * <p>
     * 宿主类为普通类（无需接口）；含非 static 注解方法的宿主类以无参构造实例化一次
     * （fail-fast）；HEAD/RETURN 方法签名必须 {@code (HookContext) → void}；
     * REPLACE 为签名镜像（同参同返回值，首参 = 目标实例，static 目标无实例参）。
     *
     * @param basePackage 基础包（含子包）
     */
    public void scan(String basePackage) {
        new HookAnnotationScanner(registry).scan(pluginLoader, pluginName, basePackage);
    }

    // ==================== 撤销 ====================

    /**
     * 撤销本插件全部注册（插件 disable 时框架自动执行，正常无需手调）。
     */
    public void unhookAll() {
        registry.unhookPlugin(pluginName);
    }

    private static List<String> toNames(Class<?>[] paramTypes) {
        if (paramTypes == null) {
            return null;
        }
        List<String> names = new ArrayList<>(paramTypes.length);
        for (Class<?> type : paramTypes) {
            names.add(type.getName());
        }
        return names;
    }
}
