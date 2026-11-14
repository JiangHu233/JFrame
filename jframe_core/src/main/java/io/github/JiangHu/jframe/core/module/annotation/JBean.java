package io.github.JiangHu.jframe.core.module.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 容器管理 Bean 声明注解（配合 {@link ModuleManager} 的包扫描使用）。
 * <p>
 * 两种标注位置：
 * <ul>
 *   <li><b>产品类</b>（与 {@link JFactoryClass} 互斥）：声明该类由容器管理。
 *       默认等价 {@code register(该类.class)}（首次 get 时无参 new）；
 *       若类内另有 {@link JFactory @JFactory} static 无参方法，则改用工厂方法构造。</li>
 *   <li><b>{@link JFactoryClass @JFactoryClass} 工厂类的方法</b>：声明一个产品方法，
 *       返回类型即注册类型；方法无参或仅有一个 {@code ModuleContext} 参数
 *       （对象间引用在方法体内 {@code ctx.get(A.class)} 显式解决）。</li>
 * </ul>
 * 扫描只登记元数据，不实例化任何对象（保持懒加载）。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 形态 A：产品类 + 可选工厂方法
 * @JBean
 * public class Sword {
 *     @JFactory
 *     public static Sword create() { return new Sword(loadConfig()); }
 * }
 *
 * // 形态 B：工厂类 + 多产品方法（B 持有 A 在工厂内解决）
 * @JFactoryClass
 * public class EconomyFactory {
 *     @JBean public Economy economy() { return new Economy(); }
 *     @JBean public Bank bank(ModuleContext ctx) { return new Bank(ctx.get(Economy.class)); }
 * }
 *
 * manager.forPlugin(this).scan("com.myplugin.beans");
 * }</pre>
 *
 * @see JFactory
 * @see JFactoryClass
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface JBean {

    /**
     * 是否以宿主插件名义注册到 Nukkit ServiceManager（供其他插件发现）。
     * <p>
     * 默认 {@code false}（私有 Bean，仅本容器可查）；显式置 {@code true} 的产物
     * 在创建后注册服务。
     *
     * @return 服务注册开关
     */
    boolean service() default false;
}
