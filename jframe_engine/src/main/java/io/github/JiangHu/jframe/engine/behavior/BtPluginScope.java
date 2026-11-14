package io.github.JiangHu.jframe.engine.behavior;

import io.github.JiangHu.jframe.core.scan.AnnotatedClassScanner;
import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;

/**
 * BT 插件作用域 — 绑定插件 ClassLoader 的词表扫描注册代理（模式同 EcsPluginScope）。
 */
public final class BtPluginScope {

    /** BT API */
    private final BtAPI api;

    /** 插件类加载器 */
    private final ClassLoader classLoader;

    /**
     * 构造作用域（由 {@link BtAPI#forPlugin} 创建）。
     *
     * @param api         BT API
     * @param classLoader 插件类加载器
     */
    BtPluginScope(BtAPI api, ClassLoader classLoader) {
        this.api = api;
        this.classLoader = classLoader;
    }

    /**
     * 扫描指定包（含子包）下的 @{@link BtActionWord}/@{@link BtConditionWord} 类并注册。
     * 单类失败只记日志不影响其余（对齐 ECS scan 容错）。
     *
     * @param basePackages 基础包名
     * @return 本作用域（链式）
     */
    public BtPluginScope scan(String... basePackages) {
        for (Class<?> clazz : new AnnotatedClassScanner(BtActionWord.class).scan(classLoader, basePackages)) {
            if (!BtAction.class.isAssignableFrom(clazz)) {
                continue;
            }
            try {
                api.registerAction(clazz.getAnnotation(BtActionWord.class).value(),
                        clazz.asSubclass(BtAction.class));
            } catch (Exception e) {
                throw new EcsConfigException("注册 BT 行为词失败: " + clazz.getName(), e);
            }
        }
        for (Class<?> clazz : new AnnotatedClassScanner(BtConditionWord.class).scan(classLoader, basePackages)) {
            if (!BtCondition.class.isAssignableFrom(clazz)) {
                continue;
            }
            try {
                api.registerCondition(clazz.getAnnotation(BtConditionWord.class).value(),
                        clazz.asSubclass(BtCondition.class));
            } catch (Exception e) {
                throw new EcsConfigException("注册 BT 条件词失败: " + clazz.getName(), e);
            }
        }
        return this;
    }
}
