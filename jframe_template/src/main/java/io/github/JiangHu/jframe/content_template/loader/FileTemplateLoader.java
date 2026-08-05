package io.github.JiangHu.jframe.content_template.loader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/**
 * 文件系统模板加载器——从磁盘加载模板文件，支持热重载。
 *
 * <p>适用于服务器管理员自定义的模板（放在插件数据目录下，可随时修改）。
 * {@link #lastModified} 返回文件的实际修改时间，{@code TemplateEngine} 据此检测是否需要重新编译。
 *
 * <p>模板路径规则：{@code baseDir + "/" + prefix + name + suffix}
 * <ul>
 *   <li>baseDir：基准目录（如插件数据目录 {@code ./plugins/JFrame/scoreboard/}）</li>
 *   <li>prefix：子目录前缀（如 {@code "templates/"}）</li>
 *   <li>name：模板名称</li>
 *   <li>suffix：文件扩展名，默认 {@code ".xml"}</li>
 * </ul>
 */
public class FileTemplateLoader implements TemplateLoader {

    private final File baseDir;
    private final String prefix;
    private final String suffix;

    /**
     * 用默认扩展名 {@code .xml} 创建。
     *
     * @param baseDir 基准目录
     * @param prefix  子目录前缀（可为 null 或空）
     */
    public FileTemplateLoader(File baseDir, String prefix) {
        this(baseDir, prefix, ".xml");
    }

    /**
     * @param baseDir 基准目录
     * @param prefix  子目录前缀（可为 null 或空）
     * @param suffix  文件扩展名（如 {@code ".xml"}）
     */
    public FileTemplateLoader(File baseDir, String prefix, String suffix) {
        this.baseDir = baseDir;
        this.prefix = normalizePrefix(prefix);
        this.suffix = suffix != null ? suffix : "";
    }

    @Override
    public String load(String name) throws Exception {
        File file = resolveFile(name);
        if (!file.exists() || !file.isFile()) {
            return null;
        }
        Resource resource = new FileSystemResource(file);
        try (InputStream is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public long lastModified(String name) {
        File file = resolveFile(name);
        if (file.exists() && file.isFile()) {
            return file.lastModified();
        }
        return 0;
    }

    /**
     * 解析模板名称为文件对象。
     * <p>防止路径穿越：确保解析后的文件在 baseDir 内。
     */
    private File resolveFile(String name) {
        File file = new File(baseDir, prefix + name + suffix);
        try {
            String canonicalBase = baseDir.getCanonicalPath();
            String canonicalFile = file.getCanonicalPath();
            if (!canonicalFile.startsWith(canonicalBase)) {
                throw new SecurityException("模板路径超出基准目录: " + name);
            }
        } catch (IOException e) {
            // 忽略 canonical 路径检查失败，返回文件让上层处理
        }
        return file;
    }

    private String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }
}
