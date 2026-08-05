package io.github.JiangHu.jframe.content_template.render;

import io.github.JiangHu.jframe.content_template.TemplateConstants;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

/**
 * 渲染上下文——封装 SpEL 表达式求值和循环上下文变量（{@code this} / {@code index}）。
 *
 * <p>每次渲染时由 {@link TemplateRenderer} 创建，以 {@link io.github.JiangHu.jframe.core.data.reactive.DataContext}
 * 的数据为 root object。循环内通过 {@link #withLoopVariable} 创建子上下文，注入当前元素和序号。
 *
 * <p>SpEL 以 {@link #variables} Map 为 root object：
 * <ul>
 *   <li>{@code {{serverName}}} → variables.get("serverName")</li>
 *   <li>{@code {{player.name}}} → variables.get("player") → Map.get("name")</li>
 *   <li>{@code {{exp * 100 / maxExp}}} → SpEL 算术求值</li>
 *   <li>{@code {{this.name}}} → 循环内当前元素的 name 属性</li>
 * </ul>
 *
 * <h3>MapPropertyAccessor</h3>
 * <p>SpEL 默认的 {@code ReflectivePropertyAccessor} 不会对 Map root object 调用
 * {@code map.get(key)}——它尝试通过 getter/字段反射访问，而 Map 没有 {@code getName()} 方法。
 * 因此注册一个自定义的 {@link MapPropertyAccessor}，让 SpEL 遇到 Map 时自动用
 * {@code map.get()} 取值。这对嵌套 Map 路径（如 {@code player.name}）同样生效。
 */
public class RenderContext {

    private static final ExpressionParser DEFAULT_PARSER = new SpelExpressionParser();

    /** 单例 Map 属性访问器，注册到所有 StandardEvaluationContext */
    private static final MapPropertyAccessor MAP_ACCESSOR = new MapPropertyAccessor();

    private final ExpressionParser parser;
    private final Map<String, Object> variables;

    /**
     * 用默认 SpEL 解析器创建上下文。
     *
     * @param variables root 变量（DataContext 的数据快照）
     */
    public RenderContext(Map<String, Object> variables) {
        this(DEFAULT_PARSER, variables);
    }

    /**
     * 用指定 SpEL 解析器创建上下文。
     *
     * @param parser    SpEL 表达式解析器
     * @param variables root 变量
     */
    public RenderContext(ExpressionParser parser, Map<String, Object> variables) {
        this.parser = parser;
        this.variables = variables != null ? variables : Map.of();
    }

    /**
     * 创建 SpEL 求值上下文——以 variables Map 为 root object，注册 MapPropertyAccessor。
     *
     * @return 配置好的 StandardEvaluationContext
     */
    private StandardEvaluationContext createEvalContext() {
        StandardEvaluationContext ctx = new StandardEvaluationContext(variables);
        // 将 MapPropertyAccessor 插入到列表最前面，优先于 ReflectivePropertyAccessor
        ctx.getPropertyAccessors().add(0, MAP_ACCESSOR);
        return ctx;
    }

    /**
     * 求值 SpEL 表达式。
     *
     * @param expression SpEL 表达式（如 {@code "player.name"}、{@code "exp * 100 / maxExp"}）
     * @return 求值结果，异常时返回 {@code null}
     */
    public Object evaluate(String expression) {
        try {
            EvaluationContext ctx = createEvalContext();
            return parser.parseExpression(expression).getValue(ctx);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 求值表达式并转为字符串。
     *
     * @param expression SpEL 表达式
     * @return 求值结果的字符串表示，{@code null} 值返回空字符串
     */
    public String evaluateString(String expression) {
        Object value = evaluate(expression);
        return value != null ? value.toString() : "";
    }

    /**
     * 求值表达式并转为 boolean。
     * <p>支持 Boolean、Number（非 0 为真）、String（非空且非 "false" 为真）、非 null 对象为真。
     *
     * @param expression SpEL 条件表达式
     * @return 布尔结果
     */
    public boolean evaluateBoolean(String expression) {
        Object result = evaluate(expression);
        if (result instanceof Boolean b) {
            return b;
        }
        if (result instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (result instanceof String s) {
            return !s.isEmpty() && !"false".equalsIgnoreCase(s);
        }
        return result != null;
    }

    /**
     * 求值表达式并取列表。
     *
     * @param itemsKey 列表数据的键名（SpEL 表达式）
     * @return 可遍历的列表，非列表类型或异常返回空列表
     */
    public Iterable<?> evaluateIterable(String itemsKey) {
        Object result = evaluate(itemsKey);
        if (result instanceof Iterable<?> iterable) {
            return iterable;
        }
        return java.util.List.of();
    }

    /**
     * 创建循环子上下文——注入当前元素（{@code this}）和序号（{@code index}）。
     *
     * @param item  当前循环元素
     * @param index 当前序号（从 0 开始）
     * @return 新的渲染上下文（包含 this 和 index 变量）
     */
    public RenderContext withLoopVariable(Object item, int index) {
        Map<String, Object> newVars = new LinkedHashMap<>(variables);
        newVars.put(TemplateConstants.CTX_THIS, item);
        newVars.put(TemplateConstants.CTX_INDEX, index);
        return new RenderContext(parser, newVars);
    }

    // ==================== MapPropertyAccessor ====================

    /**
     * SpEL 属性访问器——让 SpEL 遇到 Map 时自动用 {@code map.get(key)} 取值。
     *
     * <p>注册到 {@link StandardEvaluationContext} 的 accessor 列表最前面，优先于
     * 默认的 {@code ReflectivePropertyAccessor}。这样表达式 {@code name} 在 root
     * object 为 {@code Map<String, Object>} 时会调用 {@code map.get("name")}
     * 而非尝试反射查找 {@code getName()} 方法。
     *
     * <p>对嵌套路径同样生效：{@code player.name} → 先取 {@code map.get("player")}
     * 得到内层 Map，再取 {@code innerMap.get("name")}。
     */
    private static class MapPropertyAccessor implements PropertyAccessor {

        @Override
        public Class<?>[] getSpecificTargetClasses() {
            return new Class<?>[] { Map.class };
        }

        @Override
        public boolean canRead(EvaluationContext context, Object target, String name) {
            return ((Map<?, ?>) target).containsKey(name);
        }

        @Override
        public TypedValue read(EvaluationContext context, Object target, String name) {
            return new TypedValue(((Map<?, ?>) target).get(name));
        }

        @Override
        public boolean canWrite(EvaluationContext context, Object target, String name) {
            return false;
        }

        @Override
        public void write(EvaluationContext context, Object target, String name, Object newValue) {
            // 只读访问器，不支持写入
            throw new UnsupportedOperationException("MapPropertyAccessor is read-only");
        }
    }
}
