package io.github.JiangHu.jframe.core.classloader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 聚合类加载器——将多个 {@link ClassLoader} 组合成一个虚拟的统一加载器，
 * {@code getResource} / {@code getResourceAsStream} / {@code loadClass} 时
 * 按顺序遍历所有委托加载器，返回首个命中者。
 *
 * <h3>为什么需要它</h3>
 * <p>Nukkit 服务端中每个插件拥有独立的 {@code PluginClassLoader}，互相隔离。
 * 若想从「其他插件的 jar」内加载资源（如模板、配置），单个类加载器只能看到
 * 自己的 jar 内容。{@code CompositeClassLoader} 把多个插件的类加载器聚合起来，
 * 让调用方像使用单个类加载器一样跨插件加载资源。
 *
 * <h3>查找顺序</h3>
 * <ol>
 *   <li>按构造顺序遍历所有委托加载器（{@code delegate}），返回首个命中者</li>
 *   <li>全部未命中时，回退到父加载器（系统类加载器）</li>
 * </ol>
 * <p>即<b>委托优先、父加载器兜底</b>。这与标准「双亲委派」相反——故意如此，
 * 因为跨插件场景下我们希望优先从插件 jar 查找，而非服务端 classpath。
 *
 * <h3>线程安全</h3>
 * <p>委托列表在构造后不可变，{@code CompositeClassLoader} 本身无状态，
 * 可安全并发使用（底层 {@link ClassLoader} 的资源查找方法本身线程安全）。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 聚合两个插件的类加载器
 * CompositeClassLoader loader = new CompositeClassLoader(
 *     PluginClassLoaderFactory.getClassLoader("JFrame"),
 *     PluginClassLoaderFactory.getClassLoader("MyAddon")
 * );
 *
 * // 从任一插件 jar 内加载资源
 * InputStream is = loader.getResourceAsStream("templates/main.xml");
 *
 * // 配合模板引擎使用
 * engine.registerLoader(new ClasspathTemplateLoader("templates/", loader));
 * }</pre>
 *
 * @see PluginClassLoaderFactory 从 Nukkit 插件获取类加载器
 */
public class CompositeClassLoader extends ClassLoader {

    /** 委托加载器列表（构造后不可变，已去重） */
    private final List<ClassLoader> delegates;

    /**
     * 用一组委托加载器构造。
     *
     * @param delegates 委托加载器（{@code null} 元素会被忽略，重复元素会去重）
     */
    public CompositeClassLoader(ClassLoader... delegates) {
        super(getSystemClassLoader());
        List<ClassLoader> list = new ArrayList<>();
        if (delegates != null) {
            for (ClassLoader d : delegates) {
                if (d != null && !list.contains(d)) {
                    list.add(d);
                }
            }
        }
        this.delegates = List.copyOf(list);
    }

    /**
     * 按顺序遍历委托加载器查找资源，返回首个命中者；全部未命中回退父加载器。
     */
    @Override
    public URL getResource(String name) {
        for (ClassLoader d : delegates) {
            URL url = d.getResource(name);
            if (url != null) {
                return url;
            }
        }
        return super.getResource(name);
    }

    /**
     * 聚合所有委托加载器（及父加载器）能找到的同名资源。
     * <p>用于需要枚举所有来源（如多插件都存在同名模板）的场景。
     */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> all = new ArrayList<>();
        for (ClassLoader d : delegates) {
            Enumeration<URL> e = d.getResources(name);
            while (e.hasMoreElements()) {
                all.add(e.nextElement());
            }
        }
        // 合并父加载器的结果
        Enumeration<URL> parent = super.getResources(name);
        while (parent.hasMoreElements()) {
            all.add(parent.nextElement());
        }
        return Collections.enumeration(all);
    }

    /**
     * 按顺序遍历委托加载器查找资源流，返回首个命中者；全部未命中回退父加载器。
     * <p>这是资源加载最常用的入口（{@code ClassPathResource} 内部即调用此方法）。
     */
    @Override
    public InputStream getResourceAsStream(String name) {
        for (ClassLoader d : delegates) {
            InputStream is = d.getResourceAsStream(name);
            if (is != null) {
                return is;
            }
        }
        return super.getResourceAsStream(name);
    }

    /**
     * 按顺序遍历委托加载器加载类，返回首个命中者；全部未命中回退父加载器。
     * <p><b>注意</b>：此实现不遵循标准双亲委派（委托优先于父加载器），
     * 适用于需要加载其他插件特有类的场景。若不同插件存在同名类，会加载到
     * 委托列表中靠前的那一个。
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        for (ClassLoader d : delegates) {
            try {
                Class<?> c = d.loadClass(name);
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            } catch (ClassNotFoundException ignored) {
                // 继续尝试下一个委托加载器
            }
        }
        return super.loadClass(name, resolve);
    }

    /** 已注册的委托加载器数量 */
    public int delegateCount() {
        return delegates.size();
    }

    /** 委托加载器列表（不可变副本） */
    public List<ClassLoader> getDelegates() {
        return delegates;
    }
}
