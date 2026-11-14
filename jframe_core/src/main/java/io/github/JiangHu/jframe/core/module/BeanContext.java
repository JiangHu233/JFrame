package io.github.JiangHu.jframe.core.module;

import java.util.List;
import java.util.Properties;

/**
 * {@code @JInit} 二次初始化阶段上下文：bean 在 init 方法内经此解析同批 bean 引用
 * 与类型化配置值。
 * <p>
 * {@link #get(Class)} 与 {@link ModuleManager#get(Class)} 同语义（同批 bean 在
 * 二次初始化阶段均已构造注册）；配置值来自 boot 调用方传入的 {@link Properties}
 * 合并结果，缺键返回默认值，值解析失败抛 {@link IllegalArgumentException}（fail-fast）。
 *
 * @see ModuleManager#boot
 * @see io.github.JiangHu.jframe.core.module.annotation.JInit
 */
public interface BeanContext {

    /**
     * 获取同批已注册 bean。
     *
     * @param type 注册过的 bean 类型
     * @param <T>  类型
     * @return 单例实例
     * @throws IllegalStateException 类型未注册 / 容器已关闭
     */
    <T> T get(Class<T> type);

    /**
     * 获取同批引导中某类型（含接口/父类）的全部 bean（保持引导顺序）。
     * <p>
     * 承接原 Spring 集合注入语义（如 {@code List<Handler>} 注入全部实现）。
     *
     * @param type 目标类型
     * @param <T>  类型
     * @return 全部匹配实例（无匹配返回空列表）
     */
    <T> List<T> allOf(Class<T> type);

    /**
     * 读字符串配置，缺键返回默认值。
     *
     * @param key 配置键
     * @param def 缺省值
     * @return 配置值或默认值
     */
    String prop(String key, String def);

    /**
     * 读 int 配置，缺键返回默认值。
     *
     * @param key 配置键
     * @param def 缺省值
     * @return 配置值或默认值
     * @throws IllegalArgumentException 值不可解析为 int
     */
    int propInt(String key, int def);

    /**
     * 读 boolean 配置，缺键返回默认值。
     *
     * @param key 配置键
     * @param def 缺省值
     * @return 配置值或默认值
     * @throws IllegalArgumentException 值不可解析为 boolean
     */
    boolean propBoolean(String key, boolean def);

    /**
     * 读 double 配置，缺键返回默认值。
     *
     * @param key 配置键
     * @param def 缺省值
     * @return 配置值或默认值
     * @throws IllegalArgumentException 值不可解析为 double
     */
    double propDouble(String key, double def);

    /**
     * 读字符串数组配置（逗号分隔，逐项去空白、丢弃空项），缺键返回默认值。
     *
     * @param key 配置键
     * @param def 缺省值
     * @return 配置值或默认值
     */
    String[] propStrings(String key, String[] def);

    /**
     * boot 调用方便捷工具：按后者覆盖前者的顺序合并多份配置。
     *
     * @param layers 配置层（先低后高）
     * @return 合并结果
     */
    static Properties merge(Properties... layers) {
        Properties merged = new Properties();
        for (Properties layer : layers) {
            if (layer != null) {
                merged.putAll(layer);
            }
        }
        return merged;
    }
}
