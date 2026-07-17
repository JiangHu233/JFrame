package io.github.JiangHu.jframe.data;

import cn.nukkit.plugin.Plugin;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.JiangHu.jframe.core.module.PluginAware;
import io.github.JiangHu.jframe.data.core.MetadataCache;
import io.github.JiangHu.jframe.data.core.SaveFieldTypeAdapterFactory;
import io.github.JiangHu.jframe.data.exception.DataException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 数据保存器 — <b>面向用户的统一入口</b>。
 * <p>
 * 将标注了 {@link io.github.JiangHu.jframe.data.annotation.SaveField @SaveField}
 * 的类对象序列化为 JSON 文件，或从 JSON 文件反序列化回对象。
 * <p>
 * 内部持有配置好的 {@link Gson} 实例（注册了
 * {@link SaveFieldTypeAdapterFactory}），所有序列化/反序列化均通过 Gson 完成，
 * 自动递归处理容器（List/Set/Map/数组）和嵌套对象。
 *
 * <h3>根路径（rootDir）</h3>
 * <p>
 * 保存器持有一个可配置的根路径，所有相对路径的 save/load 都基于此路径解析。
 * 设置方式有两种：
 * <ul>
 *   <li>手动调用 {@link #setRootDir(File)}</li>
 *   <li>通过 {@link PluginAware} 机制自动绑定（{@code bindPlugin} 时设为插件数据目录）</li>
 * </ul>
 *
 * <h3>文件命名</h3>
 * <ul>
 *   <li>{@code save(obj, "players/steve")} → {@code rootDir/players/steve.json}（自动建父目录）</li>
 *   <li>{@code save(obj, file)} → 直接使用 file（绝对路径）</li>
 *   <li>{@code save(obj)} → {@code rootDir/<obj.saveKey()>.json}（需实现 {@link SaveIdentifiable}）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // Spring 注入或手动创建
 * DataSaver saver = ...;
 *
 * // 保存
 * saver.save(playerData, "players/steve");   // → rootDir/players/steve.json
 * saver.save(playerData);                     // → rootDir/<uuid>.json（需实现 SaveIdentifiable）
 *
 * // 加载
 * PlayerData loaded = saver.load(PlayerData.class, "players/steve");
 *
 * // 回填已有实例
 * saver.loadInto(existingData, "players/steve");
 *
 * // JSON 字符串互转
 * String json = saver.toJson(playerData);
 * PlayerData fromStr = saver.fromJson(json, PlayerData.class);
 * }</pre>
 *
 * @see io.github.JiangHu.jframe.data.annotation.SaveField
 * @see SaveIdentifiable
 * @see DataException
 */
public class DataSaver implements PluginAware {

    /** JSON 文件扩展名 */
    private static final String JSON_EXTENSION = ".json";

    /** 元数据缓存 */
    private final MetadataCache cache;

    /** Gson 实例（注册了注解驱动的类型适配器工厂） */
    private final Gson gson;

    /** 保存根路径 */
    private File rootDir;

    /**
     * 构造保存器（Spring 构造器注入）。
     *
     * @param cache 元数据缓存
     */
    public DataSaver(MetadataCache cache) {
        this.cache = cache;
        this.gson = new GsonBuilder()
                .registerTypeAdapterFactory(new SaveFieldTypeAdapterFactory(cache))
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
    }

    // ========== 根路径 ==========

    /**
     * 设置保存根路径。
     *
     * @param rootDir 根路径（会自动创建）
     */
    public void setRootDir(File rootDir) {
        this.rootDir = rootDir;
    }

    /**
     * @return 保存根路径，或 null（尚未设置）
     */
    public File getRootDir() {
        return rootDir;
    }

    // ========== 文件保存 ==========

    /**
     * 保存对象到根路径下的指定文件名。
     * <p>
     * 文件名相对于 {@link #getRootDir()}，自动追加 {@code .json} 扩展名。
     * 支持子路径（如 {@code "players/steve"}），自动创建父目录。
     *
     * @param obj      要保存的对象
     * @param fileName 文件名（相对根路径，不含扩展名）
     */
    public void save(Object obj, String fileName) {
        File file = resolveRelativeFile(fileName);
        save(obj, file);
    }

    /**
     * 保存对象到指定文件。
     *
     * @param obj  要保存的对象
     * @param file 目标文件（绝对路径）
     */
    public void save(Object obj, File file) {
        String json = toJson(obj);
        writeStringToFile(json, file);
    }

    /**
     * 保存对象到根路径下，文件名由 {@link SaveIdentifiable#saveKey()} 决定。
     *
     * @param obj 要保存的对象（必须实现 {@link SaveIdentifiable}）
     * @throws DataException 如果对象未实现 {@link SaveIdentifiable}
     */
    public void save(Object obj) {
        if (!(obj instanceof SaveIdentifiable identifiable)) {
            throw new DataException("对象 " + obj.getClass().getName() +
                    " 未实现 SaveIdentifiable，无法自动确定文件名。" +
                    "请使用 save(obj, fileName) 显式指定文件名，或实现 SaveIdentifiable 接口。");
        }
        String key = identifiable.saveKey();
        if (key == null || key.isEmpty()) {
            throw new DataException("saveKey() 返回了 null 或空字符串（类: " +
                    obj.getClass().getName() + "）");
        }
        save(obj, key);
    }

    // ========== 文件加载 ==========

    /**
     * 从根路径下的指定文件名加载对象。
     *
     * @param clazz    目标类
     * @param fileName 文件名（相对根路径，不含扩展名）
     * @param <T>      目标类型
     * @return 反序列化的对象
     */
    public <T> T load(Class<T> clazz, String fileName) {
        File file = resolveRelativeFile(fileName);
        return load(clazz, file);
    }

    /**
     * 从指定文件加载对象。
     *
     * @param clazz 目标类
     * @param file  源文件（绝对路径）
     * @param <T>   目标类型
     * @return 反序列化的对象
     */
    public <T> T load(Class<T> clazz, File file) {
        String json = readStringFromFile(file);
        return fromJson(json, clazz);
    }

    // ========== 回填已有实例 ==========

    /**
     * 从根路径下的指定文件名加载并回填到已有实例。
     * <p>
     * 与 {@link #load(Class, String)} 不同，本方法不创建新实例，
     * 而是将 JSON 数据填充到传入的 {@code target} 对象中。
     * 适用于对象已被其他系统持有、无法替换引用的场景。
     *
     * @param target   目标实例
     * @param fileName 文件名（相对根路径，不含扩展名）
     */
    public void loadInto(Object target, String fileName) {
        File file = resolveRelativeFile(fileName);
        loadInto(target, file);
    }

    /**
     * 从指定文件加载并回填到已有实例。
     *
     * @param target 目标实例
     * @param file   源文件（绝对路径）
     */
    public void loadInto(Object target, File file) {
        String json = readStringFromFile(file);
        fromJsonInto(target, json);
    }

    // ========== JSON 字符串转换 ==========

    /**
     * 将对象序列化为 JSON 字符串。
     *
     * @param obj 要序列化的对象
     * @return JSON 字符串（pretty printing）
     */
    public String toJson(Object obj) {
        try {
            return gson.toJson(obj);
        } catch (Exception e) {
            if (e instanceof DataException) {
                throw e;
            }
            throw new DataException("序列化对象失败（类: " +
                    (obj != null ? obj.getClass().getName() : "null") + "）", e);
        }
    }

    /**
     * 从 JSON 字符串反序列化为对象。
     *
     * @param json  JSON 字符串
     * @param clazz 目标类
     * @param <T>   目标类型
     * @return 反序列化的对象
     */
    public <T> T fromJson(String json, Class<T> clazz) {
        try {
            return gson.fromJson(json, clazz);
        } catch (Exception e) {
            if (e instanceof DataException) {
                throw e;
            }
            throw new DataException("反序列化 JSON 失败（目标类: " + clazz.getName() + "）", e);
        }
    }

    /**
     * 从 JSON 字符串反序列化并回填到已有实例。
     *
     * @param target 目标实例
     * @param json   JSON 字符串
     */
    public void fromJsonInto(Object target, String json) {
        // 利用 Gson 的 JsonReader 回填：先解析为 JsonObject，再用适配器逐字段 set
        // 这里复用 Gson 的反序列化，然后通过元数据手动回填
        try {
            // 使用 Gson 反序列化为新对象，再通过元数据复制字段
            Object parsed = gson.fromJson(json, target.getClass());
            if (parsed == null) {
                return;
            }
            cache.get(target.getClass()).ifPresent(metadata -> {
                for (var fm : metadata.fields()) {
                    try {
                        Object value = fm.field().get(parsed);
                        fm.field().set(target, value);
                    } catch (IllegalAccessException ex) {
                        throw new DataException("回填字段失败: " + fm.field().getName() +
                                "（类: " + target.getClass().getName() + "）", ex);
                    }
                }
            });
        } catch (Exception e) {
            if (e instanceof DataException) {
                throw e;
            }
            throw new DataException("回填 JSON 到实例失败（类: " +
                    target.getClass().getName() + "）", e);
        }
    }

    // ========== PluginAware ==========

    /**
     * 绑定插件实例，自动设置根路径为插件数据目录。
     * <p>
     * 由 {@code JFrameMain} 在模块导入或插件启用时自动调用。
     * 若已通过 {@link #setRootDir(File)} 手动设置，则不覆盖。
     *
     * @param plugin 当前 Nukkit 插件实例
     */
    @Override
    public void bindPlugin(Plugin plugin) {
        if (this.rootDir == null && plugin != null) {
            this.rootDir = plugin.getDataFolder();
        }
    }

    // ========== 内部工具方法 ==========

    /**
     * 将相对文件名解析为根路径下的 File 对象（自动追加 .json）。
     */
    private File resolveRelativeFile(String fileName) {
        if (rootDir == null) {
            throw new DataException("保存根路径（rootDir）尚未设置。" +
                    "请调用 setRootDir() 或通过 PluginAware 绑定插件。");
        }
        String name = fileName.endsWith(JSON_EXTENSION) ? fileName : fileName + JSON_EXTENSION;
        return new File(rootDir, name);
    }

    /**
     * 将字符串写入文件（自动创建父目录）。
     */
    private void writeStringToFile(String content, File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                Files.createDirectories(parent.toPath());
            }
            Path path = file.toPath();
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DataException("写入文件失败: " + file.getAbsolutePath(), e);
        }
    }

    /**
     * 从文件读取字符串。
     */
    private String readStringFromFile(File file) {
        if (!file.exists()) {
            throw new DataException("文件不存在: " + file.getAbsolutePath());
        }
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DataException("读取文件失败: " + file.getAbsolutePath(), e);
        }
    }
}
