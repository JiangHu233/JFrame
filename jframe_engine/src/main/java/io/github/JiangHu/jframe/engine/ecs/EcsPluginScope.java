package io.github.JiangHu.jframe.engine.ecs;

import io.github.JiangHu.jframe.core.scan.AnnotatedClassScanner;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsComponent;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsDespawner;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsResolver;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsSelector;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsSystemAnno;
import io.github.JiangHu.jframe.engine.ecs.despawner.Despawner;
import io.github.JiangHu.jframe.engine.ecs.engine.EcsLog;
import io.github.JiangHu.jframe.engine.ecs.entity.Component;
import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;
import io.github.JiangHu.jframe.engine.ecs.gameobject.GameObjectDefinition;
import io.github.JiangHu.jframe.engine.ecs.resolver.EcsEventResolver;
import io.github.JiangHu.jframe.engine.ecs.selector.EntitySelector;
import io.github.JiangHu.jframe.engine.ecs.spawner.Spawner;
import io.github.JiangHu.jframe.engine.ecs.system.EcsSystem;
import java.util.List;

/**
 * ECS 插件作用域 — 绑定指定插件 ClassLoader 的注册代理（模式同 EventPluginScope / ScoreboardPluginScope）。
 *
 * <h3>能力</h3>
 * <ul>
 *   <li>{@link #scan(String...)} — 扫描插件 jar 内六类注解（组件/选择器/生成器/回收器/解析器/系统）并注册</li>
 *   <li>{@link #loadGameObjects(String)} — 扫描插件 jar resources 下指定前缀的 {@code *.yml} 定义</li>
 * </ul>
 */
public class EcsPluginScope {

    /** ECS API（注册入口） */
    private final EcsAPI api;

    /** 绑定的插件类加载器 */
    private final ClassLoader classLoader;

    /**
     * 构造作用域（由 {@link EcsAPI#forPlugin} 创建）。
     *
     * @param api         ECS API
     * @param classLoader 插件类加载器
     */
    EcsPluginScope(EcsAPI api, ClassLoader classLoader) {
        this.api = api;
        this.classLoader = classLoader;
    }

    /**
     * 扫描指定包（含子包）下的六类注解类并注册到共享注册表。
     * <p>单个类注册失败只记录日志，不影响其余类。
     *
     * @param basePackages 基础包名
     * @return 本作用域（链式）
     */
    public EcsPluginScope scan(String... basePackages) {
        registerComponents(basePackages);
        registerSelectors(basePackages);
        registerSpawners(basePackages);
        registerDespawners(basePackages);
        registerResolvers(basePackages);
        registerSystems(basePackages);
        return this;
    }

    /**
     * 扫描插件 jar classpath 指定前缀下的全部 {@code *.yml} 定义并加载（回溯挂载）。
     *
     * @param prefix classpath 前缀（如 {@code "gameobjects"}）
     * @return 加载的定义列表
     */
    public List<GameObjectDefinition> loadGameObjects(String prefix) {
        return api.loadGameObjectsFromClasspath(prefix, classLoader);
    }

    /**
     * 扫描并注册组件类。
     */
    private void registerComponents(String... packages) {
        for (Class<?> clazz : new AnnotatedClassScanner(EcsComponent.class).scan(classLoader, packages)) {
            if (!Component.class.isAssignableFrom(clazz)) {
                EcsLog.error("跳过未实现 Component 的 @EcsComponent 类: " + clazz.getName(), null);
                continue;
            }
            EcsComponent anno = clazz.getAnnotation(EcsComponent.class);
            String id = anno.value().isBlank() ? clazz.getSimpleName() : anno.value();
            try {
                api.registerComponent(id, clazz.asSubclass(Component.class));
            } catch (Exception e) {
                EcsLog.error("注册组件失败: " + clazz.getName(), e);
            }
        }
    }

    /**
     * 扫描并注册选择器类。
     */
    private void registerSelectors(String... packages) {
        for (Class<?> clazz : new AnnotatedClassScanner(EcsSelector.class).scan(classLoader, packages)) {
            if (!EntitySelector.class.isAssignableFrom(clazz)) {
                EcsLog.error("跳过未实现 EntitySelector 的 @EcsSelector 类: " + clazz.getName(), null);
                continue;
            }
            EcsSelector anno = clazz.getAnnotation(EcsSelector.class);
            String name = anno.value().isBlank() ? clazz.getSimpleName() : anno.value();
            try {
                api.registerSelector(name, clazz.asSubclass(EntitySelector.class));
            } catch (Exception e) {
                EcsLog.error("注册选择器失败: " + clazz.getName(), e);
            }
        }
    }

    /**
     * 扫描并注册生成器类。
     */
    private void registerSpawners(String... packages) {
        Class<io.github.JiangHu.jframe.engine.ecs.annotation.EcsSpawner> annoType =
                io.github.JiangHu.jframe.engine.ecs.annotation.EcsSpawner.class;
        for (Class<?> clazz : new AnnotatedClassScanner(annoType).scan(classLoader, packages)) {
            if (!Spawner.class.isAssignableFrom(clazz)) {
                EcsLog.error("跳过未实现 Spawner 的 @EcsSpawner 类: " + clazz.getName(), null);
                continue;
            }
            io.github.JiangHu.jframe.engine.ecs.annotation.EcsSpawner anno = clazz.getAnnotation(annoType);
            String name = anno.value().isBlank() ? clazz.getSimpleName() : anno.value();
            try {
                api.registerSpawner(name, clazz.asSubclass(Spawner.class));
            } catch (Exception e) {
                EcsLog.error("注册生成器失败: " + clazz.getName(), e);
            }
        }
    }

    /**
     * 扫描并注册回收器类。
     */
    private void registerDespawners(String... packages) {
        for (Class<?> clazz : new AnnotatedClassScanner(EcsDespawner.class).scan(classLoader, packages)) {
            if (!Despawner.class.isAssignableFrom(clazz)) {
                EcsLog.error("跳过未实现 Despawner 的 @EcsDespawner 类: " + clazz.getName(), null);
                continue;
            }
            EcsDespawner anno = clazz.getAnnotation(EcsDespawner.class);
            String name = anno.value().isBlank() ? clazz.getSimpleName() : anno.value();
            try {
                api.registerDespawner(name, clazz.asSubclass(Despawner.class));
            } catch (Exception e) {
                EcsLog.error("注册回收器失败: " + clazz.getName(), e);
            }
        }
    }

    /**
     * 扫描并注册宿主解析器类。
     */
    private void registerResolvers(String... packages) {
        for (Class<?> clazz : new AnnotatedClassScanner(EcsResolver.class).scan(classLoader, packages)) {
            if (!EcsEventResolver.class.isAssignableFrom(clazz)) {
                EcsLog.error("跳过未实现 EcsEventResolver 的 @EcsResolver 类: " + clazz.getName(), null);
                continue;
            }
            EcsResolver anno = clazz.getAnnotation(EcsResolver.class);
            String name = anno.value().isBlank() ? clazz.getSimpleName() : anno.value();
            try {
                api.registerResolver(name, clazz.asSubclass(EcsEventResolver.class));
            } catch (Exception e) {
                EcsLog.error("注册解析器失败: " + clazz.getName(), e);
            }
        }
    }

    /**
     * 扫描并注册系统类。
     */
    private void registerSystems(String... packages) {
        for (Class<?> clazz : new AnnotatedClassScanner(EcsSystemAnno.class).scan(classLoader, packages)) {
            if (!EcsSystem.class.isAssignableFrom(clazz)) {
                EcsLog.error("跳过未实现 EcsSystem 的 @EcsSystemAnno 类: " + clazz.getName(), null);
                continue;
            }
            try {
                api.registerSystem(clazz.asSubclass(EcsSystem.class));
            } catch (Exception e) {
                EcsLog.error("注册系统失败: " + clazz.getName(), e);
            }
        }
    }
}
