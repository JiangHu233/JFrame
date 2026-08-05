package io.github.JiangHu.jframe.content_template.loader;

import java.util.ArrayList;
import java.util.List;

/**
 * 组合模板加载器——按注册顺序依次尝试多个 {@link TemplateLoader}，返回首个找到的模板。
 *
 * <p>典型用法：先查文件系统（用户自定义模板，支持热重载），再查 classpath（内置默认模板）。
 *
 * <pre>{@code
 * CompositeTemplateLoader loader = new CompositeTemplateLoader(
 *     new FileTemplateLoader(dataFolder, "templates/"),      // 优先：用户自定义
 *     new ClasspathTemplateLoader("templates/scoreboard/")   // 兜底：内置默认
 * );
 * }</pre>
 *
 * <p>{@link #lastModified} 返回实际命中的 loader 的修改时间。
 */
public class CompositeTemplateLoader implements TemplateLoader {

    private final List<TemplateLoader> loaders;

    /**
     * @param loaders 按优先级排列的加载器列表
     */
    public CompositeTemplateLoader(TemplateLoader... loaders) {
        this.loaders = new ArrayList<>();
        for (TemplateLoader loader : loaders) {
            if (loader != null) {
                this.loaders.add(loader);
            }
        }
    }

    /**
     * 追加一个加载器（优先级最低）。
     *
     * @param loader 要追加的加载器
     */
    public void addLoader(TemplateLoader loader) {
        if (loader != null) {
            loaders.add(loader);
        }
    }

    @Override
    public String load(String name) throws Exception {
        for (TemplateLoader loader : loaders) {
            String source = loader.load(name);
            if (source != null) {
                return source;
            }
        }
        return null;
    }

    @Override
    public long lastModified(String name) {
        for (TemplateLoader loader : loaders) {
            try {
                String source = loader.load(name);
                if (source != null) {
                    return loader.lastModified(name);
                }
            } catch (Exception e) {
                // 忽略，尝试下一个 loader
            }
        }
        return 0;
    }

    /** 已注册的加载器数量 */
    public int loaderCount() {
        return loaders.size();
    }
}
