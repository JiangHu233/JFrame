package io.github.JiangHu.jframe.command.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 命名参数绑定注解（参数级别）— 类似 Spring MVC 的 {@code @RequestParam}。
 * <p>
 * 标注在方法参数上，声明该参数从命令的<b>命名参数</b>中取值。
 * 命名参数以 {@code --key value} / {@code --key=value}（长选项）或
 * {@code -k value} / {@code -k=value}（短选项）形式出现在命令尾部。
 *
 * <h3>命名参数解析规则</h3>
 * <pre>
 *   命令: /home set myhouse --desc "我的家" --public
 *                   └位置参数┘  └命名参数 desc="我的家"┘  └命名参数 public=true┘
 * </pre>
 * <ul>
 *   <li>{@code --key value} / {@code --key=value}：{@code key} 绑定为字符串 {@code value}
 *       （内联格式 {@code =} 还可传递以 {@code -} 开头的值，如 {@code --reason=-1}）</li>
 *   <li>{@code --key}（后跟另一个选项或位于末尾）：{@code key} 绑定为 {@code "true"}（布尔标记）</li>
 *   <li>{@code -k value} / {@code -k=value}：短选项，等价于 {@code --key value} / {@code --key=value}</li>
 *   <li>不以此两种形式开头的 token 视为<b>位置参数</b>（供无注解参数按位置绑定）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @CommandMapping("set")
 * public void onSet(
 *         @Sender Player player,
 *         @CommandParam("name") String name,              // 必需：--name <值>
 *         @CommandParam(value = "desc", defaultValue = "无") String desc,  // 可选，带默认值
 *         @CommandParam(value = "public", required = false) boolean isPublic  // 布尔标记
 * ) {
 *     player.sendMessage("家 " + name + " : " + desc);
 * }
 * }</pre>
 *
 * <h3>与位置参数的区别</h3>
 * <ul>
 *   <li>{@code @CommandParam}：按<b>名称</b>查找，顺序无关，适合可选/可乱序的参数</li>
 *   <li>无注解参数：按<b>位置</b>依次绑定，适合固定顺序的位置参数</li>
 * </ul>
 *
 * @see CommandMapping
 * @see Sender
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CommandParam {

    /**
     * 参数名称。
     * <p>
     * 对应命令中 {@code --name} 的 {@code name}。为空时回退到编译参数名
     * （需 {@code -parameters} 编译选项；不可靠时建议显式指定）。
     *
     * @return 参数名称
     */
    String value() default "";

    /**
     * 是否必需。
     * <p>
     * 为 {@code true} 时，若命令中未提供该命名参数（且未声明 {@link #defaultValue()}），
     * 将向发送者返回用法提示并中止执行。
     * <p>
     * <b>注意</b>：一旦声明了非空的 {@link #defaultValue()}，参数即<b>隐式变为可选</b>
     * （与 Spring MVC {@code @RequestParam} 语义一致），此时 {@code required} 不再生效。
     *
     * @return 是否必需，默认 true
     */
    boolean required() default true;

    /**
     * 默认值。
     * <p>
     * 当命令中未提供该命名参数时使用。会经过与正常参数相同的类型转换。
     * <p>
     * <b>声明非空默认值会使参数隐式可选</b>（无论 {@link #required()} 取值），
     * 缺失时回退到默认值而非报错 —— 与 Spring MVC {@code @RequestParam} 一致。
     *
     * @return 默认值，默认空字符串（表示 {@code null} 语义由目标类型决定）
     */
    String defaultValue() default "";
}
