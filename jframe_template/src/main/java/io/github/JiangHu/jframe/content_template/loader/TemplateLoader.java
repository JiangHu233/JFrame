package io.github.JiangHu.jframe.content_template.loader;

/**
 * 模板加载器接口——负责从不同来源（classpath、文件系统等）加载模板源码。
 *
 * <p>实现类：
 * <ul>
 *   <li>{@link ClasspathTemplateLoader}：从 classpath 加载（打包在 jar 内的模板）</li>
 *   <li>{@link FileTemplateLoader}：从文件系统加载（支持热重载）</li>
 *   <li>{@link CompositeTemplateLoader}：组合多个 loader，按优先级查找</li>
 * </ul>
 *
 * <p>加载器由 {@code TemplateEngine} 持有，通过 {@code registerLoader} 注册。
 */
public interface TemplateLoader {

    /**
     * 加载模板源码。
     *
     * @param name 模板名称（不含扩展名，如 {@code "main"}、{@code "lobby/info"}）
     * @return 模板源码字符串，找不到返回 {@code null}
     * @throws Exception 加载失败（IO 错误等）
     */
    String load(String name) throws Exception;

    /**
     * 获取模板的最后修改时间戳（毫秒），用于热重载检测。
     *
     * @param name 模板名称
     * @return 最后修改时间戳，不支持热重载或找不到返回 {@code 0}
     */
    long lastModified(String name);
}
