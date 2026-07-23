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
import java.util.function.Supplier;

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
 * // 加载（文件不存在时返回 null，可用 null 区分"缺失"与"读取失败"）
 * PlayerData loaded = saver.load(PlayerData.class, "players/steve");
 *
 * // 判断文件是否存在
 * if (saver.exists("players/steve")) { ... }
 *
 * // 加载或新建（不存在则用默认值创建并落盘）
 * Config cfg = saver.loadOrSave(Config.class, "config", Config::new);
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

    /** 父级保存器（由 {@link #sub} 创建的子保存器持有，供 {@link #parent()} 向上导航） */
    private final DataSaver parent;

    /**
     * 构造保存器（Spring 构造器注入）。
     *
     * @param cache 元数据缓存
     */
    public DataSaver(MetadataCache cache) {
        this(cache, buildGson(cache), null);
    }

    /**
     * 内部构造器（由 {@link #sub} 创建子保存器时使用）。
     * <p>
     * 子保存器与父级共享 {@link MetadataCache} 与 {@link Gson} 实例，
     * 并持有父级引用以支持加载回退。
     *
     * @param cache  元数据缓存
     * @param gson   Gson 实例
     * @param parent 父级保存器（根保存器为 null）
     */
    private DataSaver(MetadataCache cache, Gson gson, DataSaver parent) {
        this.cache = cache;
        this.gson = gson;
        this.parent = parent;
    }

    /**
     * 构建 Gson 实例（注册注解驱动的类型适配器工厂）。
     */
    private static Gson buildGson(MetadataCache cache) {
        return new GsonBuilder()
                .registerTypeAdapterFactory(new SaveFieldTypeAdapterFactory(cache))
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
    }

    // ========== 根路径 ==========

    /**
     * 设置保存根路径（仅根保存器可调用）。
     * <p>
     * 根路径是所有 {@link #sub} 子路径的<b>固定基准点</b>。由 {@code sub()} 派生的
     * 子保存器其根路径已锁定为临时子路径，调用本方法将抛出 {@link DataException}，
     * 以保证 sub/parent 的作用范围始终限定在临时子路径内、不会篡改根路径。
     *
     * @param rootDir 根路径（会自动创建）
     * @throws DataException 若当前为子保存器（根路径已由 sub() 派生，不可修改）
     */
    public void setRootDir(File rootDir) {
        if (parent != null) {
            throw new DataException("子保存器的根路径由 sub() 派生，不可修改。" +
                    "sub/parent 仅作用于临时子路径，不能变化根路径；" +
                    "如需回到根路径，请调用 root()。");
        }
        this.rootDir = rootDir;
    }

    /**
     * @return 保存根路径，或 null（尚未设置）
     */
    public File getRootDir() {
        return rootDir;
    }

    // ========== 子路径与路径导航 ==========

    /**
     * 拼接子路径，创建一个<b>子保存器</b>（向下导航）。
     * <p>
     * 子保存器的根路径为 {@code 当前根路径 / first / more...}，所有相对路径的
     * save/load 都基于此子路径解析。子保存器持有当前保存器作为
     * {@link #parent() 父级}，可调用 {@code parent()} <b>返回上级</b>保存器，
     * 实现路径的自由上下导航。
     * <p>
     * 典型用途：按模块/玩家/世界划分数据目录，用 {@code sub} 下钻、
     * {@code parent()} 上溯，避免每次调用都手写完整相对路径。
     *
     * <pre>{@code
     * DataSaver players = saver.sub("players");        // rootDir/players
     * DataSaver vip     = players.sub("vip");          // rootDir/players/vip
     * vip.save(data, "steve");                         // → rootDir/players/vip/steve.json
     * vip.parent().load(C.class, "global");            // 回到 players 目录加载
     * vip.root().load(C.class, "config");              // 一步回到根目录加载
     * }</pre>
     *
     * @param first 第一级子目录名（不能为空）
     * @param more  后续多级子目录名（可选）
     * @return 子保存器（共享 cache/gson，可通过 parent() 返回上级）
     * @throws DataException 若根路径尚未设置
     */
    public DataSaver sub(String first, String... more) {
        if (rootDir == null) {
            throw new DataException("保存根路径（rootDir）尚未设置，无法拼接子路径。" +
                    "请调用 setRootDir() 或通过 PluginAware 绑定插件。");
        }
        File childRoot = new File(rootDir, first);
        for (String segment : more) {
            childRoot = new File(childRoot, segment);
        }
        DataSaver child = new DataSaver(cache, gson, this);
        child.rootDir = childRoot;
        return child;
    }

    /**
     * 返回上级（父级）保存器，实现路径的<b>向上导航</b>。
     * <p>
     * 由 {@link #sub} 创建的子保存器会返回其父级；根保存器返回 {@code null}。
     * 可链式调用逐级上溯，例如 {@code saver.sub("a").sub("b").parent().parent()}
     * 将回到根保存器。
     *
     * @return 父级保存器，若当前为根保存器则返回 null
     */
    public DataSaver parent() {
        return parent;
    }

    /**
     * 判断当前保存器是否为根保存器（无父级）。
     *
     * @return 若无父级返回 true
     */
    public boolean isRoot() {
        return parent == null;
    }

    /**
     * 返回根保存器 — <b>清空所有临时子路径，一步回到根</b>。
     * <p>
     * 无论当前处于多深的子路径，本方法都会沿父级链向上回到根保存器
     * （{@link #isRoot()} 为 true）。若当前已是根保存器，返回自身。
     * <p>
     * 典型用途：在深层子路径完成临时操作后，快速回到根路径继续其他工作，
     * 无需手动逐级 {@code parent()}。
     *
     * <pre>{@code
     * DataSaver vip = saver.sub("players").sub("vip");
     * vip.save(data, "steve");                  // 临时子路径操作
     * vip.root().save(global, "config");        // 清空子路径，回到根操作
     * assert vip.root() == saver;               // 根保存器即最初的 saver
     * }</pre>
     *
     * @return 根保存器
     */
    public DataSaver root() {
        DataSaver cur = this;
        while (cur.parent != null) {
            cur = cur.parent;
        }
        return cur;
    }

    /**
     * 为指定插件创建一个<b>独立的根保存器</b>，根路径为该插件的数据目录。
     * <p>
     * 适用于"工具插件"场景：JFrame 作为前置插件提供本工具，业务插件调用本方法
     * 得到以<b>自身数据目录</b>（{@code plugins/<业务插件名>/}）为根的保存器，
     * 各插件数据互不干扰，不会写入 JFrame 的文件夹。
     * <p>
     * 返回的是全新的根保存器（{@link #isRoot()} 为 true），与当前保存器相互独立，
     * 共享同一份 {@link MetadataCache} 与 {@link Gson}，可独立使用 sub/parent/root 导航。
     *
     * <pre>{@code
     * // 在业务插件的 onEnable 中
     * DataSaver mySaver = JFrameMain.getInstance().getDataSaver().forPlugin(this);
     * mySaver.save(data, "config");   // → plugins/<本插件>/config.json
     * }</pre>
     *
     * @param plugin 业务插件实例（不能为 null）
     * @return 以该插件数据目录为根的独立保存器
     * @throws DataException 若 plugin 为 null
     */
    public DataSaver forPlugin(Plugin plugin) {
        if (plugin == null) {
            throw new DataException("forPlugin(plugin) 的 plugin 参数不能为 null");
        }
        DataSaver saver = new DataSaver(cache, gson, null);
        saver.rootDir = plugin.getDataFolder();
        return saver;
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
        save(obj, resolveSaveKey(obj));
    }

    // ========== 文件加载 ==========

    /**
     * 从根路径下的指定文件名加载对象。
     * <p>
     * 若文件<b>不存在</b>，返回 {@code null}（不抛异常）。加载前可先用
     * {@link #exists(String)} 探测，或直接用 {@code null} 判断区分"数据缺失"
     * 与"读取失败"两种情况。
     *
     * @param clazz    目标类
     * @param fileName 文件名（相对根路径，不含扩展名）
     * @param <T>      目标类型
     * @return 反序列化的对象；文件不存在时返回 {@code null}
     */
    public <T> T load(Class<T> clazz, String fileName) {
        File file = resolveRelativeFile(fileName);
        return load(clazz, file);
    }

    /**
     * 从指定文件加载对象。
     * <p>
     * 若文件<b>不存在</b>，返回 {@code null}（不抛异常），以便调用方用
     * {@code null} 区分"数据缺失/首次加载"与"读取失败"两种情况，提升鲁棒性。
     * 若需在缺失时自动创建默认值并落盘，请改用
     * {@link #loadOrSave(Class, File, java.util.function.Supplier) loadOrSave}。
     *
     * @param clazz 目标类
     * @param file  源文件（绝对路径）
     * @param <T>   目标类型
     * @return 反序列化的对象；文件不存在时返回 {@code null}
     */
    public <T> T load(Class<T> clazz, File file) {
        if (!file.exists()) {
            return null;
        }
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

    // ========== 文件存在性判断 ==========

    /**
     * 判断根路径下的指定文件是否存在（自动追加 {@code .json} 扩展名）。
     * <p>
     * 用于在 {@link #load(Class, String)} 之前探测目标文件是否已存在，
     * 避免文件缺失时抛出 {@link DataException}。
     *
     * @param fileName 文件名（相对根路径，不含扩展名）
     * @return 文件存在返回 true，否则 false
     */
    public boolean exists(String fileName) {
        return resolveRelativeFile(fileName).exists();
    }

    /**
     * 判断指定文件是否存在。
     *
     * @param file 目标文件（绝对路径）
     * @return 文件存在返回 true，否则 false（{@code file} 为 null 时返回 false）
     */
    public boolean exists(File file) {
        return file != null && file.exists();
    }

    /**
     * 判断 {@link SaveIdentifiable} 对象的<b>序列化目标文件</b>是否存在。
     * <p>
     * 目标文件名由 {@link SaveIdentifiable#saveKey()} 决定，等价于
     * {@code exists(obj.saveKey())}。用于在保存前探测某条数据是否已落盘，
     * 或判断某条数据是否首次写入。
     *
     * @param obj 目标对象（必须实现 {@link SaveIdentifiable}）
     * @return 目标文件存在返回 true，否则 false
     * @throws DataException 若对象未实现 {@link SaveIdentifiable} 或 saveKey() 非法
     * @see #save(Object)
     */
    public boolean exists(Object obj) {
        return exists(resolveSaveKey(obj));
    }

    /**
     * 解析 {@link SaveIdentifiable} 对象对应的存储文件（绝对路径）。
     * <p>
     * 文件名由对象的 {@link SaveIdentifiable#saveKey()} 决定，返回的文件正是
     * {@link #save(Object) save(obj)} 会写入、{@link #exists(Object) exists(obj)}
     * 会探测、{@link #loadOrSave(Class, java.util.function.Supplier) loadOrSave(clazz, supplier)}
     * 会读写的那个文件（文件可能尚不存在）。免去外部手动拼接
     * {@code rootDir + saveKey + ".json"}。
     *
     * <pre>{@code
     * PlayerData data = new PlayerData(uuid, "Steve", 1);
     * File file = saver.fileOf(data);   // 直接拿到 data 对应的存储文件
     * }</pre>
     *
     * @param obj 须实现 {@link SaveIdentifiable} 且 saveKey() 合法
     * @return 对应的存储文件（绝对路径，可能尚不存在）
     * @throws DataException 若 obj 非 {@link SaveIdentifiable} 或 saveKey() 非法
     * @see #save(Object)
     * @see #exists(Object)
     * @see #loadOrSave(Class, java.util.function.Supplier)
     */
    public File fileOf(Object obj) {
        return resolveRelativeFile(resolveSaveKey(obj));
    }

    // ========== 加载或新建 ==========

    /**
     * 加载对象；若文件<b>不存在</b>，则用 {@code defaultSupplier} 创建默认值，
     * <b>保存到文件</b>后返回。
     * <p>
     * 典型用途：加载配置文件，首次运行（文件缺失）时自动生成默认配置并落盘，
     * 后续运行直接读取。等价于：
     * <pre>{@code
     * if (saver.exists(fileName)) {
     *     return saver.load(clazz, fileName);
     * } else {
     *     T def = defaultSupplier.get();
     *     saver.save(def, fileName);
     *     return def;
     * }
     * }</pre>
     *
     * <pre>{@code
     * // 存在则读取，不存在则用无参构造创建并落盘
     * Config cfg = saver.loadOrSave(Config.class, "config", Config::new);
     * }</pre>
     *
     * @param clazz           目标类
     * @param fileName        文件名（相对根路径，不含扩展名）
     * @param defaultSupplier 默认值提供器（仅在文件不存在时调用，不能返回 null）
     * @param <T>             目标类型
     * @return 加载到的对象，或新建并保存的默认对象
     * @throws DataException 若 defaultSupplier 为 null 或返回 null
     */
    public <T> T loadOrSave(Class<T> clazz, String fileName, Supplier<T> defaultSupplier) {
        return loadOrSave(clazz, resolveRelativeFile(fileName), defaultSupplier);
    }

    /**
     * 加载对象；若文件<b>不存在</b>，则用 {@code defaultSupplier} 创建默认值，
     * <b>保存到文件</b>后返回。
     *
     * @param clazz           目标类
     * @param file            目标文件（绝对路径）
     * @param defaultSupplier 默认值提供器（仅在文件不存在时调用，不能返回 null）
     * @param <T>             目标类型
     * @return 加载到的对象，或新建并保存的默认对象
     * @throws DataException 若 defaultSupplier 为 null 或返回 null
     * @see #load(Class, File)
     * @see #save(Object, File)
     */
    public <T> T loadOrSave(Class<T> clazz, File file, Supplier<T> defaultSupplier) {
        if (file.exists()) {
            return load(clazz, file);
        }
        if (defaultSupplier == null) {
            throw new DataException("defaultSupplier 不能为 null（文件: " +
                    file.getAbsolutePath() + "）");
        }
        T defaultValue = defaultSupplier.get();
        if (defaultValue == null) {
            throw new DataException("defaultSupplier 返回了 null，无法保存（文件: " +
                    file.getAbsolutePath() + "）");
        }
        save(defaultValue, file);
        return defaultValue;
    }

    /**
     * 加载对象；文件名由默认对象的 {@link SaveIdentifiable#saveKey()} 自动决定。
     * <p>
     * 适用于默认对象实现 {@link SaveIdentifiable} 的场景：无需显式指定文件名，
     * 保存器用 {@code defaultObj} 的 {@code saveKey()} 作为文件名。文件存在则读取，
     * 不存在则把 {@code defaultObj} 落盘后返回。
     *
     * <pre>{@code
     * // 玩家数据按 UUID 自动命名：有则读取，无则用默认值创建并落盘
     * PlayerData data = saver.loadOrSave(new PlayerData(uuid, "Steve", 1));
     * }</pre>
     *
     * <p><b>设计说明（为何直接传对象而非 Supplier）</b>：本重载的文件名依赖
     * {@link SaveIdentifiable#saveKey()}，必须先拿到对象才能确定文件位置，
     * 因此对象无论如何都会被构造——Supplier 的"惰性"在此毫无意义，
     * 直接传对象更简洁、语义更诚实。若需要惰性求值（文件存在时不构造默认对象），
     * 请改用 {@link #loadOrSave(Class, String, Supplier) 显式文件名重载}。
     *
     * @param defaultObj 默认对象（不能为 null，须有合法 saveKey）
     * @param <T>        目标类型（须实现 {@link SaveIdentifiable}）
     * @return 加载到的对象，或新建并保存的默认对象
     * @throws DataException 若 defaultObj 为 null 或 saveKey() 非法
     * @see #loadOrSave(Class, String, Supplier)
     * @see SaveIdentifiable
     */
    @SuppressWarnings("unchecked")
    public <T extends SaveIdentifiable> T loadOrSave(T defaultObj) {
        if (defaultObj == null) {
            throw new DataException("defaultObj 不能为 null");
        }
        // 文件名由默认对象的 saveKey() 决定
        File file = resolveRelativeFile(resolveSaveKey(defaultObj));
        if (file.exists()) {
            return load((Class<T>) defaultObj.getClass(), file);
        }
        save(defaultObj, file);
        return defaultObj;
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
     * 将相对文件名解析为当前根路径下的 File 对象（自动追加 .json）。
     * <p>
     * 始终基于当前保存器的根路径解析，save 与 load 行为一致。
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
     * 解析 {@link SaveIdentifiable} 对象的 saveKey 作为文件名。
     * <p>
     * 供 {@link #save(Object)} 与 {@link #exists(Object)} 共用，统一校验：
     * 未实现 {@link SaveIdentifiable}、saveKey 为 null 或空串时抛出 {@link DataException}。
     *
     * @param obj 目标对象
     * @return saveKey（非空）
     */
    private String resolveSaveKey(Object obj) {
        if (!(obj instanceof SaveIdentifiable identifiable)) {
            throw new DataException("对象 " + obj.getClass().getName() +
                    " 未实现 SaveIdentifiable，无法自动确定文件名。" +
                    "请使用显式文件名重载，或实现 SaveIdentifiable 接口。");
        }
        String key = identifiable.saveKey();
        if (key == null || key.isEmpty()) {
            throw new DataException("saveKey() 返回了 null 或空字符串（类: " +
                    obj.getClass().getName() + "）");
        }
        return key;
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
