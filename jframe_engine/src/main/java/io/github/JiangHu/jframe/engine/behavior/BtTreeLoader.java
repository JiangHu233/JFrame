package io.github.JiangHu.jframe.engine.behavior;

import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行为树 YAML 加载器 — 数据驱动树定义（对齐 UE/Unity"数据资产树+代码词表"标配）。
 *
 * <h3>文件格式（一文件多树，顶层 key = 树名）</h3>
 * <pre>{@code
 * scav:                          # 树名
 *   root:
 *     select:                     # 组合：select（优先级左高）/ sequence（顺序）
 *       - sequence:
 *           - {cond: ecs:has-component, params: {component: fapeft-ai-cap-melee}}
 *           - {act: ai:melee-strike}
 *       - {act: ai:patrol}        # 行为叶：词名+params（公有字段=参数）
 * }</pre>
 *
 * <p>节点形态（单键 map 或 单键+兄弟 params 键）：
 * <ul>
 *   <li>{@code select: [节点...]} / {@code sequence: [节点...]} — 组合</li>
 *   <li>{@code invert: 节点} — 取反装饰</li>
 *   <li>{@code cooldown: {ticks: N, child: 节点}} — 冷却装饰</li>
 *   <li>{@code cond: 词名, params: {...}} — 条件叶</li>
 *   <li>{@code act: 词名, params: {...}} — 行为叶</li>
 * </ul>
 * 未注册词名/参数键拼错/结构非法 → {@link EcsConfigException} 加载期 fail fast。
 */
public final class BtTreeLoader {

    /** 词表注册表 */
    private final BtRegistry registry;

    /**
     * 构造加载器。
     *
     * @param registry 词表注册表
     */
    public BtTreeLoader(BtRegistry registry) {
        this.registry = registry;
    }

    /**
     * 从文件或目录加载（目录递归 *.yml；同名树冲突即报错）。
     *
     * @param file 文件或目录
     * @return 加载的树列表
     */
    public List<BtTree> load(File file) {
        List<BtTree> result = new ArrayList<>();
        collect(file, result);
        return result;
    }

    /**
     * 从 YAML 源码加载。
     *
     * @param yaml       YAML 源码
     * @param sourceDesc 来源描述（报错用）
     * @return 树列表
     */
    @SuppressWarnings("unchecked")
    public List<BtTree> load(String yaml, String sourceDesc) {
        Object loaded = new Yaml().load(yaml);
        if (!(loaded instanceof Map)) {
            throw new EcsConfigException("BT 树定义非映射结构: " + sourceDesc);
        }
        List<BtTree> result = new ArrayList<>();
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) loaded).entrySet()) {
            result.add(parseTree(entry.getKey(), entry.getValue(), sourceDesc));
        }
        return result;
    }

    private void collect(File file, List<BtTree> result) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    collect(child, result);
                }
            }
            return;
        }
        if (!file.getName().endsWith(".yml") && !file.getName().endsWith(".yaml")) {
            return;
        }
        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            result.addAll(load(content, file.getPath()));
        } catch (IOException e) {
            throw new EcsConfigException("读取 BT 树定义失败: " + file.getPath(), e);
        }
    }

    private BtTree parseTree(String name, Object body, String source) {
        if (!(body instanceof Map<?, ?> map) || !(map.get("root") instanceof Map<?, ?> rootNode)) {
            throw new EcsConfigException("BT 树 '" + name + "' 缺少 root 节点: " + source);
        }
        return new BtTree(name, parseNode(cast(rootNode), name, source));
    }

    /**
     * 递归解析节点（单键 map：组合/装饰/cond/act）。
     */
    @SuppressWarnings("unchecked")
    private BtNode parseNode(Map<String, Object> node, String treeName, String source) {
        if (node.containsKey("select")) {
            List<BtNode> children = parseChildren(node.get("select"), treeName, source, "select");
            return named(new BtSelector(children), "select");
        }
        if (node.containsKey("sequence")) {
            List<BtNode> children = parseChildren(node.get("sequence"), treeName, source, "sequence");
            return named(new BtSequence(children), "sequence");
        }
        if (node.containsKey("invert")) {
            Object child = node.get("invert");
            if (!(child instanceof Map)) {
                throw new EcsConfigException("BT 树 '" + treeName + "' invert 子节点须为映射: " + source);
            }
            return named(new BtInverter(parseNode(cast(child), treeName, source)), "invert");
        }
        if (node.containsKey("cooldown")) {
            Object spec = node.get("cooldown");
            if (!(spec instanceof Map<?, ?> specMap) || !(specMap.get("child") instanceof Map)) {
                throw new EcsConfigException("BT 树 '" + treeName
                        + "' cooldown 须为 {ticks: N, child: 节点}: " + source);
            }
            Map<String, Object> specMap2 = cast(spec);
            long ticks = specMap2.get("ticks") instanceof Number n ? n.longValue() : 20;
            BtNode child = parseNode(cast(specMap2.get("child")), treeName, source);
            return named(new BtCooldown(ticks, child), "cooldown[" + ticks + "t]");
        }
        if (node.containsKey("cond")) {
            String word = String.valueOf(node.get("cond"));
            BtCondition condition = registry.newCondition(word, paramsOf(node));
            return named(condition, "cond:" + word);
        }
        if (node.containsKey("act")) {
            String word = String.valueOf(node.get("act"));
            BtAction action = registry.newAction(word, paramsOf(node));
            return named(action, "act:" + word);
        }
        throw new EcsConfigException("BT 树 '" + treeName + "' 无法识别的节点形态（键: "
                + node.keySet() + "；支持 select/sequence/invert/cooldown/cond/act）: " + source);
    }

    private List<BtNode> parseChildren(Object children, String treeName, String source, String kind) {
        if (!(children instanceof List<?> list) || list.isEmpty()) {
            throw new EcsConfigException("BT 树 '" + treeName + "' " + kind + " 子节点须为非空列表: " + source);
        }
        List<BtNode> nodes = new ArrayList<>(list.size());
        for (Object child : list) {
            if (!(child instanceof Map)) {
                throw new EcsConfigException("BT 树 '" + treeName + "' " + kind + " 子项须为映射: " + source);
            }
            nodes.add(parseNode(cast(child), treeName, source));
        }
        return nodes;
    }

    /** 取节点兄弟 params 键（cond/act 用）；缺省空表 */
    private static Map<String, Object> paramsOf(Map<String, Object> node) {
        if (node.get("params") instanceof Map<?, ?> params) {
            return new LinkedHashMap<>(cast(params));
        }
        return Map.of();
    }

    private static BtNode named(BtNode node, String name) {
        if (node instanceof BtNode real) {
            setName(real, name);
        }
        return node;
    }

    private static void setName(BtNode node, String name) {
        try {
            var method = BtNode.class.getDeclaredMethod("setName", String.class);
            method.setAccessible(true);
            method.invoke(node, name);
        } catch (Exception ignored) {
            // 命名失败不影响语义
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object map) {
        return (Map<String, Object>) map;
    }
}
