package io.github.JiangHu.jframe.engine.ecs.gameobject;

import io.github.JiangHu.jframe.engine.ecs.despawner.Despawner;
import io.github.JiangHu.jframe.engine.ecs.entity.Component;
import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;
import io.github.JiangHu.jframe.engine.ecs.registry.ComponentRegistry;
import io.github.JiangHu.jframe.engine.ecs.registry.DespawnerRegistry;
import io.github.JiangHu.jframe.engine.ecs.registry.ParamBinder;
import io.github.JiangHu.jframe.engine.ecs.registry.SelectorRegistry;
import io.github.JiangHu.jframe.engine.ecs.registry.SpawnerRegistry;
import io.github.JiangHu.jframe.engine.ecs.selector.EntitySelector;
import io.github.JiangHu.jframe.engine.ecs.spawner.Spawner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GameObject 定义加载器 — YAML → {@link GameObjectDefinition}，支持三类来源：
 *
 * <ul>
 *   <li>{@link #load(String, String)} — YAML 源码字符串</li>
 *   <li>{@link #load(File)} — 单文件或目录（目录递归收集 {@code *.yml}）</li>
 *   <li>{@link #loadClasspath(String, ClassLoader)} — classpath 前缀递归扫描
 *       前缀目录下全部 {@code .yml} 资源（含 jar 内资源，插件 resources 主推路径）</li>
 * </ul>
 *
 * <h3>加载期校验（fail fast，全部抛 {@link EcsConfigException}）</h3>
 * <ul>
 *   <li>根结构必须是 名称 → 定义的 map</li>
 *   <li>selector/spawn/despawn 引用的注册名必须已注册（内置或自定义）</li>
 *   <li>selector/spawn/despawn 的 params 未知字段名、类型不可强转 → 拒绝</li>
 *   <li>components[].id 必须已注册，其 params 对组件类做静态字段校验</li>
 * </ul>
 */
public class GameObjectLoader {

    /** 组件注册表（id 校验 + 参数静态校验） */
    private final ComponentRegistry componentRegistry;

    /** 选择器注册表（use 解析） */
    private final SelectorRegistry selectorRegistry;

    /** 生成器注册表（use 解析） */
    private final SpawnerRegistry spawnerRegistry;

    /** 回收器注册表（use 解析） */
    private final DespawnerRegistry despawnerRegistry;

    /**
     * 构造加载器。
     *
     * @param componentRegistry 组件注册表
     * @param selectorRegistry  选择器注册表
     * @param spawnerRegistry   生成器注册表
     * @param despawnerRegistry 回收器注册表
     */
    public GameObjectLoader(ComponentRegistry componentRegistry,
                            SelectorRegistry selectorRegistry,
                            SpawnerRegistry spawnerRegistry,
                            DespawnerRegistry despawnerRegistry) {
        this.componentRegistry = componentRegistry;
        this.selectorRegistry = selectorRegistry;
        this.spawnerRegistry = spawnerRegistry;
        this.despawnerRegistry = despawnerRegistry;
    }

    /**
     * 从 YAML 源码字符串解析定义。
     *
     * @param yaml       YAML 源码（根为 名称 → 定义 的 map）
     * @param sourceDesc 来源描述（异常消息用，如文件路径）
     * @return 定义列表（保持 YAML 声明顺序）
     * @throws EcsConfigException 结构或校验错误
     */
    public List<GameObjectDefinition> load(String yaml, String sourceDesc) {
        Map<String, Object> root;
        try {
            root = new Yaml().load(yaml);
        } catch (Exception e) {
            throw new EcsConfigException("YAML 解析失败: " + sourceDesc, e);
        }
        if (root == null || root.isEmpty()) {
            return List.of();
        }
        List<GameObjectDefinition> result = new ArrayList<>();
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            result.add(parseDefinition(entry.getKey(), entry.getValue(), sourceDesc));
        }
        return result;
    }

    /**
     * 从文件或目录加载（目录递归收集 {@code *.yml}）。
     *
     * @param file 文件或目录
     * @return 定义列表
     * @throws EcsConfigException 读取或校验失败
     */
    public List<GameObjectDefinition> load(File file) {
        List<GameObjectDefinition> result = new ArrayList<>();
        for (File yml : collectYamlFiles(file)) {
            String content;
            try {
                content = Files.readString(yml.toPath(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new EcsConfigException("读取定义文件失败: " + yml, e);
            }
            result.addAll(load(content, yml.getPath()));
        }
        return result;
    }

    /**
     * 从 classpath 前缀递归扫描加载（含 jar 内资源）。
     *
     * <p>以前缀目录为根递归收集全部 {@code .yml} 资源并按 URL 去重，
     * 覆盖插件 jar {@code src/main/resources/&lt;prefix&gt;/} 下的全部定义文件
     * （与模板模块 ClasspathTemplateLoader 同思路）。
     *
     * @param prefix      classpath 前缀（如 {@code "gameobjects"}，不以 / 开头）
     * @param classLoader 插件类加载器（通常来自 {@code plugin.getClass().getClassLoader()}）
     * @return 定义列表
     * @throws EcsConfigException 扫描或校验失败
     */
    public List<GameObjectDefinition> loadClasspath(String prefix, ClassLoader classLoader) {
        String normalized = prefix == null ? "" : prefix.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            throw new EcsConfigException("classpath 前缀不得为空");
        }
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(classLoader);
        Map<String, byte[]> contents = new LinkedHashMap<>();
        collectResources(resolver, "classpath*:" + normalized + "/**/*.yml", contents);
        collectResources(resolver, "classpath*:" + normalized + "/*.yml", contents);

        List<GameObjectDefinition> result = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
            String yaml = new String(entry.getValue(), StandardCharsets.UTF_8);
            result.addAll(load(yaml, entry.getKey()));
        }
        return result;
    }

    /**
     * 解析单个定义。
     */
    @SuppressWarnings("unchecked")
    private GameObjectDefinition parseDefinition(String name, Object raw, String sourceDesc) {
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new EcsConfigException("定义 '" + name + "' 必须是 map（" + sourceDesc + "）");
        }
        Map<String, Object> node = (Map<String, Object>) rawMap;

        EntitySelector selector = parseRef(node.get("selector"), "selector", name, sourceDesc,
                selectorRegistry::create);
        Spawner spawner = parseRef(node.get("spawn"), "spawn", name, sourceDesc,
                spawnerRegistry::create);
        Despawner despawner = parseRef(node.get("despawn"), "despawn", name, sourceDesc,
                despawnerRegistry::create);

        Set<String> tags = parseTags(node.get("tags"), name, sourceDesc);
        List<GameObjectDefinition.ComponentSpec> components =
                parseComponents(node.get("components"), name, sourceDesc);

        return new GameObjectDefinition(name, selector, tags, components, spawner, despawner);
    }

    /**
     * 解析 selector/spawn 引用节点（{ use: 注册名, params: {...} }）。
     *
     * @param rawNode    原始节点（null 返回 null）
     * @param kind       节点种类（异常消息用）
     * @param defName    定义名
     * @param sourceDesc 来源描述
     * @param factory    注册表实例化工厂（name, params → 实例）
     * @return 实例；节点为 null 时 null
     */
    private <T> T parseRef(Object rawNode, String kind, String defName, String sourceDesc,
                           RefFactory<T> factory) {
        if (rawNode == null) {
            return null;
        }
        if (!(rawNode instanceof Map<?, ?> rawMap) || !(rawMap.get("use") instanceof String use)) {
            throw new EcsConfigException("定义 '" + defName + "' 的 " + kind
                    + " 须为 { use: 注册名, params: {...} }（" + sourceDesc + "）");
        }
        Map<String, Object> params = rawMap.get("params") instanceof Map<?, ?> p
                ? (Map<String, Object>) p : Map.of();
        try {
            return factory.create(use, params);
        } catch (EcsConfigException e) {
            throw new EcsConfigException("定义 '" + defName + "' 的 " + kind + " 无效: " + e.getMessage()
                    + "（" + sourceDesc + "）", e);
        }
    }

    /**
     * 实例化工厂（选择器/生成器注册表的 create 方法引用）。
     *
     * @param <T> 实例类型
     */
    @FunctionalInterface
    private interface RefFactory<T> {

        /**
         * 按注册名实例化并注入参数。
         *
         * @param name   注册名
         * @param params 参数表
         * @return 实例
         */
        T create(String name, Map<String, Object> params);
    }

    /**
     * 解析 tags 节点（字符串列表）。
     */
    private Set<String> parseTags(Object rawTags, String defName, String sourceDesc) {
        if (rawTags == null) {
            return Set.of();
        }
        if (!(rawTags instanceof List<?> list)) {
            throw new EcsConfigException("定义 '" + defName + "' 的 tags 须为字符串列表（" + sourceDesc + "）");
        }
        Set<String> tags = new LinkedHashSet<>();
        for (Object tag : list) {
            if (!(tag instanceof String s) || s.isBlank()) {
                throw new EcsConfigException("定义 '" + defName + "' 的标签必须为非空字符串（" + sourceDesc + "）");
            }
            tags.add(s);
        }
        return Set.copyOf(tags);
    }

    /**
     * 解析 components 节点并做注册名存在性 + 参数静态校验。
     */
    private List<GameObjectDefinition.ComponentSpec> parseComponents(
            Object rawComponents, String defName, String sourceDesc) {
        if (rawComponents == null) {
            return List.of();
        }
        if (!(rawComponents instanceof List<?> list)) {
            throw new EcsConfigException("定义 '" + defName + "' 的 components 须为列表（" + sourceDesc + "）");
        }
        List<GameObjectDefinition.ComponentSpec> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rawMap) || !(rawMap.get("id") instanceof String id)) {
                throw new EcsConfigException("定义 '" + defName + "' 的组件项须为 { id: 组件id, params: {...} }（"
                        + sourceDesc + "）");
            }
            Map<String, Object> params = rawMap.get("params") instanceof Map<?, ?> p
                    ? (Map<String, Object>) p : Map.of();
            Class<? extends Component> type;
            try {
                type = componentRegistry.require(id);
                ParamBinder.validate(type, params);
            } catch (EcsConfigException e) {
                throw new EcsConfigException("定义 '" + defName + "' 组件 '" + id + "' 无效: "
                        + e.getMessage() + "（" + sourceDesc + "）", e);
            }
            result.add(new GameObjectDefinition.ComponentSpec(id, params));
        }
        return List.copyOf(result);
    }

    /**
     * 收集文件/目录下的 YAML 文件（目录递归）。
     */
    private static List<File> collectYamlFiles(File file) {
        List<File> result = new ArrayList<>();
        if (file == null || !file.exists()) {
            return result;
        }
        if (file.isFile()) {
            result.add(file);
            return result;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return result;
        }
        for (File child : children) {
            if (child.isFile() && child.getName().toLowerCase(Locale.ROOT).endsWith(".yml")) {
                result.add(child);
            } else if (child.isDirectory()) {
                result.addAll(collectYamlFiles(child));
            }
        }
        return result;
    }

    /**
     * 按模式收集资源内容（按 URL 去重）。
     */
    private static void collectResources(PathMatchingResourcePatternResolver resolver,
                                         String pattern, Map<String, byte[]> contents) {
        try {
            for (Resource resource : resolver.getResources(pattern)) {
                if (!resource.isReadable()) {
                    continue;
                }
                String key = resource.getURL().toExternalForm();
                contents.computeIfAbsent(key, k -> {
                    try (InputStream in = resource.getInputStream()) {
                        return in.readAllBytes();
                    } catch (IOException e) {
                        throw new EcsConfigException("读取 classpath 定义失败: " + k, e);
                    }
                });
            }
        } catch (IOException e) {
            throw new EcsConfigException("classpath 定义扫描失败: " + pattern, e);
        }
    }
}
