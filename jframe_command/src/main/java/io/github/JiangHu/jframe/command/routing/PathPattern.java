package io.github.JiangHu.jframe.command.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 命令路径模式 — 解析、匹配、特异性排序的核心。
 * <p>
 * 将形如 {@code "home set {name}"} 的路径字符串解析为有序的<b>路径段</b>序列，
 * 每个段为以下三种之一：
 * <ul>
 *   <li>{@link Segment.Kind#STATIC 静态段} — 字面量，必须精确匹配</li>
 *   <li>{@link Segment.Kind#VARIABLE 变量段} {@code {name}} — 捕获单个 token</li>
 *   <li>{@link Segment.Kind#GREEDY 贪婪段} {@code {*name}} — 捕获剩余所有 token</li>
 * </ul>
 *
 * <h3>匹配语义（前缀匹配）</h3>
 * <p>
 * 模式匹配输入 token 序列的<b>前缀</b>。模式消耗掉的 token 数 = 段数（贪婪段除外，
 * 它消耗到末尾）。未被模式消耗的剩余 token 作为位置参数交给后续绑定。
 * <pre>
 *   模式: "home set {name}"   输入: [home, set, tower, foo]
 *         └─3 段消耗─┘            剩余 [foo] → 位置参数
 * </pre>
 *
 * <h3>大小写不敏感（对齐 Nukkit）</h3>
 * <p>
 * 静态段（根命令与子命令字面量）在解析时<b>归一化为小写</b>，匹配时采用
 * {@link String#equalsIgnoreCase} 比较，与 Nukkit 命令系统的大小写不敏感特性对齐：
 * <ul>
 *   <li>Nukkit 注册命令后内部恒以小写存储，运行时 {@code command.getName()} 回传小写；</li>
 *   <li>本类将静态段统一小写，使「注册名 / 日志 / Nukkit 回传名」三者一致；</li>
 *   <li>玩家输入 {@code /GameItem}、{@code /gameitem}、{@code /GAMEITEM} 均命中同一路由。</li>
 * </ul>
 * 注意：仅静态段大小写不敏感，<b>变量段捕获的值保留玩家输入的原样大小写</b>（那是数据，不是命令名）。
 *
 * <h3>特异性排序</h3>
 * <p>
 * 当多条模式都能匹配同一输入时，按 {@link #SPECIFICITY_COMPARATOR} 选择最优：
 * 静态段多 > 模式长 > 变量少 > 贪婪少。与 Spring 的
 * {@code RequestMappingHandlerMapping} 特异性规则一致。
 *
 * <h3>不可变与线程安全</h3>
 * 本类解析后字段不再变化，线程安全。同一 PathPattern 可被多线程并发匹配。
 *
 * @see CommandRoute
 * @see CommandRegistry
 */
public class PathPattern {

    /** 变量段正则：匹配 {name} 与 {*name}，兼容 #{name} / #{*name} 写法 */
    private static final Pattern VAR_PATTERN =
            Pattern.compile("#?\\{(\\*?)(\\w+)\\}");

    /** 解析后的路径段（有序） */
    private final List<Segment> segments;

    /** 是否包含贪婪段（用于快速判断） */
    private final boolean greedy;

    /**
     * 解析路径字符串为 PathPattern。
     *
     * @param path 路径字符串（如 {@code "home set {name}"}），以空格分段
     */
    public PathPattern(String path) {
        this.segments = parse(path);
        this.greedy = segments.stream().anyMatch(s -> s.kind == Segment.Kind.GREEDY);
    }

    /**
     * 解析路径字符串为段列表。
     * <p>
     * 以空格分段。空路径产生空段列表（匹配根命令，仅当输入也为空时命中）。
     * 每段若整体匹配 {@link #VAR_PATTERN} 则为变量/贪婪段，否则为静态段。
     */
    private static List<Segment> parse(String path) {
        List<Segment> result = new ArrayList<>();
        if (path == null || path.isBlank()) {
            return result;
        }
        for (String raw : path.trim().split("\\s+")) {
            Matcher m = VAR_PATTERN.matcher(raw);
            if (m.matches()) {
                boolean isGreedy = !m.group(1).isEmpty();
                String name = m.group(2);
                result.add(new Segment(
                        isGreedy ? Segment.Kind.GREEDY : Segment.Kind.VARIABLE,
                        name, name));
            } else {
                // 静态段归一化为小写：对齐 Nukkit 命令名小写规范，
                // 使注册名、日志、Nukkit 回传名（恒为小写）三者一致。
                result.add(new Segment(Segment.Kind.STATIC, raw.toLowerCase(Locale.ROOT), null));
            }
        }
        return result;
    }

    /**
     * 匹配输入 token 序列（前缀匹配）。
     *
     * @param tokens 输入 token（根命令 + 参数，已去除命名参数）
     * @return 匹配结果；不匹配返回 null
     */
    public MatchResult match(List<String> tokens) {
        Map<String, Object> pathVars = new java.util.LinkedHashMap<>();
        int consumed = 0;
        int tokenCount = tokens.size();

        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);
            switch (seg.kind) {
                case STATIC -> {
                    // 大小写不敏感匹配：对齐 Nukkit 命令大小写不敏感特性，
                    // 玩家输入 /GameItem、/gameitem、/GAMEITEM 均可命中同一路由。
                    if (consumed >= tokenCount || !tokens.get(consumed).equalsIgnoreCase(seg.literal)) {
                        return null; // 静态段不匹配或 token 不足
                    }
                    consumed++;
                }
                case VARIABLE -> {
                    if (consumed >= tokenCount) {
                        return null; // 变量段需要至少一个 token
                    }
                    pathVars.put(seg.varName, tokens.get(consumed));
                    consumed++;
                }
                case GREEDY -> {
                    // 贪婪：吃掉剩余所有 token（0 个或多个）
                    String[] rest = new String[tokenCount - consumed];
                    for (int j = 0; j < rest.length; j++) {
                        rest[j] = tokens.get(consumed + j);
                    }
                    pathVars.put(seg.varName, rest);
                    consumed = tokenCount; // 贪婪段后无剩余
                    return new MatchResult(consumed, pathVars);
                }
            }
        }
        return new MatchResult(consumed, pathVars);
    }

    /**
     * 返回根命令（第一段，若为静态段），已归一化为<b>小写</b>。
     * <p>
     * 用于向 Nukkit 注册根命令。若第一段是变量/贪婪，返回 null（无法注册稳定根命令）。
     * 返回值恒为小写（静态段在解析时已 {@code toLowerCase}），与 Nukkit 命令名小写规范一致，
     * 确保 {@code command.getName()} 回传的小写名能正确匹配。
     *
     * @return 根命令名（小写），或 null
     */
    public String rootCommand() {
        if (segments.isEmpty() || segments.get(0).kind != Segment.Kind.STATIC) {
            return null;
        }
        return segments.get(0).literal;
    }

    /**
     * 返回完整路径的规范化字符串（用于日志/去重）。
     */
    public String patternString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) sb.append(' ');
            Segment s = segments.get(i);
            sb.append(s.kind == Segment.Kind.STATIC ? s.literal
                    : (s.kind == Segment.Kind.GREEDY ? "{*" + s.varName + "}" : "{" + s.varName + "}"));
        }
        return sb.toString();
    }

    public boolean isGreedy() {
        return greedy;
    }

    public List<Segment> getSegments() {
        return segments;
    }

    /**
     * 特异性比较器：值越大越优先。
     * <p>
     * 排序规则（降序优先）：
     * <ol>
     *   <li>静态段数多者优先</li>
     *   <li>模式总段数多者优先（捕获更多 token）</li>
     *   <li>变量段数少者优先</li>
     *   <li>贪婪段数少者优先</li>
     * </ol>
     */
    public static final java.util.Comparator<PathPattern> SPECIFICITY_COMPARATOR = (a, b) -> {
        int cmp = Integer.compare(b.staticCount(), a.staticCount());
        if (cmp != 0) return cmp;
        cmp = Integer.compare(b.segments.size(), a.segments.size());
        if (cmp != 0) return cmp;
        cmp = Integer.compare(a.variableCount(), b.variableCount());
        if (cmp != 0) return cmp;
        return Integer.compare(a.greedyCount(), b.greedyCount());
    };

    private int staticCount() {
        int c = 0;
        for (Segment s : segments) if (s.kind == Segment.Kind.STATIC) c++;
        return c;
    }

    private int variableCount() {
        int c = 0;
        for (Segment s : segments) if (s.kind == Segment.Kind.VARIABLE) c++;
        return c;
    }

    private int greedyCount() {
        int c = 0;
        for (Segment s : segments) if (s.kind == Segment.Kind.GREEDY) c++;
        return c;
    }

    /**
     * 路径段。
     *
     * @param kind     段类型
     * @param literal  静态段的字面量（变量/贪婪段为变量名，便于调试）
     * @param varName  变量/贪婪段的变量名（静态段为 null）
     */
    public record Segment(Kind kind, String literal, String varName) {
        /** 段类型 */
        public enum Kind {
            /** 静态字面量段，精确匹配 */
            STATIC,
            /** 变量段 {@code {name}}，捕获单个 token */
            VARIABLE,
            /** 贪婪段 {@code {*name}}，捕获剩余所有 token */
            GREEDY
        }
    }

    /**
     * 匹配结果。
     *
     * @param consumed  模式消耗的 token 数
     * @param pathVars  捕获的路径变量（变量名 → 值；贪婪变量值为 String[]）
     */
    public record MatchResult(int consumed, Map<String, Object> pathVars) {}
}
