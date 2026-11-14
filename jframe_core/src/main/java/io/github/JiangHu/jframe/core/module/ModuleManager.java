package io.github.JiangHu.jframe.core.module;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.plugin.service.ServicePriority;
import io.github.JiangHu.jframe.core.module.annotation.JBean;
import io.github.JiangHu.jframe.core.module.annotation.JFactory;
import io.github.JiangHu.jframe.core.module.annotation.JFactoryClass;
import io.github.JiangHu.jframe.core.module.annotation.JInit;
import io.github.JiangHu.jframe.core.scan.AnnotatedClassScanner;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 自研模块容器 — Spring IoC/DI 容器的一对一替代，每插件一个实例。
 * <p>
 * 一个 {@code ModuleManager} 实例 = 一份<b>独立注册表</b>：类型查找表、懒加载状态、
 * ServiceManager 注册名义全部按实例隔离。{@code JFrameMain} 持有的实例只是第一个用户，
 * 业务插件 {@code new ModuleManager(this)} 即可得到自己的容器，与 JFrame 的注册表互不相通。
 *
 * <h3>注册形式（全部懒加载单例）</h3>
 * <ul>
 *   <li><b>无工厂</b>：{@code register(A.class)} —— 首次 get 时直接 {@code new}
 *       （注册期校验 public 无参构造）；私有 Bean</li>
 *   <li><b>单类型工厂函数</b>：{@code register(A.class, A::create)} ——
 *       无容器参数的函数式工厂（方法引用 / lambda）；私有 Bean</li>
 *   <li><b>工厂类</b>：{@code register(JFrameModule)} / 轻量重载
 *       {@code register(id, provides, loader)} —— 一次产出多个初始化完毕的对象，
 *       对象间引用在工厂内用普通 Java 解决；产物经 {@link ModuleContext#register}
 *       登记为<b>公开服务</b>（类型表 + ServiceManager + PluginAware）</li>
 *   <li><b>现成实例</b>：{@code register(C.class, instance)} —— 直接登记；私有 Bean</li>
 * </ul>
 *
 * <h3>注解 + 包扫描</h3>
 * <pre>{@code
 * mgr.scan(classLoader, "com.myplugin.beans");   // 零实例化，只登记元数据
 * mgr.forPlugin(this).scan("com.myplugin.beans"); // 跨插件姿势（绑定目标插件 ClassLoader）
 * }</pre>
 * <p>
 * 扫描发现 {@link JBean @JBean}（类，可选 {@link JFactory @JFactory} 工厂方法）与
 * {@link JFactoryClass @JFactoryClass}（工厂类，其 {@code @JBean} 方法无参或仅一个
 * {@link ModuleContext} 参数——B 持有 A 通过 {@code ctx.get(A.class)} 显式解决）。
 *
 * <h3>生命周期</h3>
 * <ul>
 *   <li>构造：宿主插件已启用则立即进入绑定态；否则等待 {@link #bindPlugin(Plugin)}</li>
 *   <li>{@link #bindPlugin(Plugin)}：刷新暂存的 PluginAware 绑定与 ServiceManager 注册</li>
 *   <li>{@link #shutdown()}：仅对已加载模块按 {@link JFrameModule#shutdownOrder()} 升序清理</li>
 * </ul>
 *
 * <h3>fail-fast 语义</h3>
 * 未注册类型 get 抛 {@link IllegalStateException}（列出可用类型）；同类型重复注册抛
 * {@link IllegalArgumentException}；工厂产出 null 抛异常；同线程递归创建同一类型
 * （循环依赖）抛异常；模块 provides 与实际登记不符在 load 期抛出并标记 FAILED。
 *
 * <h3>线程约定</h3>
 * 约定主线程调用；内部以创建锁 + 线程内创建栈防御并发与循环依赖。
 */
public class ModuleManager implements ForPlugin<ModulePluginScope> {

    /** 宿主插件（单元测试等无插件环境可为 null：跳过 ServiceManager 与日志） */
    private final Plugin owner;

    /** 全局创建锁：懒加载/登记/绑定/关闭的互斥（创建是低频操作） */
    private final ReentrantLock creationLock = new ReentrantLock();

    /** 类型 → 提供者（路由表；实例创建后仍保留用于诊断） */
    private final ConcurrentHashMap<Class<?>, Provider> providers = new ConcurrentHashMap<>();

    /** 类型 → 已创建单例 */
    private final ConcurrentHashMap<Class<?>, Object> instances = new ConcurrentHashMap<>();

    /** 模块 id → 模块 */
    private final ConcurrentHashMap<String, JFrameModule> modules = new ConcurrentHashMap<>();

    /** 模块 id → 状态 */
    private final ConcurrentHashMap<String, ModuleState> moduleStates = new ConcurrentHashMap<>();

    /** 线程内创建栈：循环依赖检测 */
    private final ThreadLocal<Set<Class<?>>> creating = ThreadLocal.withInitial(LinkedHashSet::new);

    /** 绑定前暂存的 PluginAware 绑定队列 */
    private final List<PluginAware> pendingAware = new ArrayList<>();

    /** 绑定前暂存的 ServiceManager 注册队列 */
    private final List<PendingService> pendingServices = new ArrayList<>();

    /** 绑定到的插件（bindPlugin 后非 null） */
    private volatile Plugin boundPlugin;

    /** 是否已绑定（bindPlugin 调用过，或构造时宿主已启用） */
    private volatile boolean bound;

    /** 是否已关闭 */
    private volatile boolean closed;

    /**
     * 构造模块容器。
     *
     * @param owner 宿主插件；{@code null} 表示无插件环境（跳过 ServiceManager 注册与日志，便于单元测试）
     */
    public ModuleManager(Plugin owner) {
        this.owner = owner;
        if (owner != null && owner.isEnabled()) {
            this.bound = true;
            this.boundPlugin = owner;
        }
    }

    // ==================== 注册形式 ====================

    /**
     * 形式 1（无工厂）：注册类型，首次 {@link #get} 时以 public 无参构造实例化。
     * <p>
     * 注册期即校验无参构造存在且 public（fail-fast）；私有 Bean，不注册 ServiceManager。
     *
     * @param type 目标类型
     * @param <T>  类型
     * @throws IllegalArgumentException 类型重复注册或缺 public 无参构造
     */
    public <T> void register(Class<T> type) {
        ensureOpen();
        registerInternal(type, Provider.reflection(type), false);
    }

    /**
     * 形式 1b（单类型工厂函数）：注册类型与无容器参数的工厂
     * （方法引用 / lambda），首次 {@link #get} 时调用工厂取得实例。
     * <p>
     * 私有 Bean；也是注解层 {@link JFactory @JFactory} 的实现底座。
     * <p>
     * 注意：此方法独立命名为 {@code registerFactory} 而非重载 {@code register}，
     * 避免与 {@link #register(Class, Object)} 在 lambda 实参上产生重载歧义。
     *
     * @param type    目标类型
     * @param factory 实例工厂（不能为 {@code null}，产出不能为 {@code null}）
     * @param <T>     类型
     * @throws IllegalArgumentException 类型重复注册
     */
    public <T> void registerFactory(Class<T> type, Supplier<T> factory) {
        ensureOpen();
        Objects.requireNonNull(factory, "factory");
        registerInternal(type, Provider.factory(() -> requireProduct(type, factory.get())), false);
    }

    /**
     * 形式 2（工厂类）：注册 {@link JFrameModule}。
     * <p>
     * 登记 {@link JFrameModule#id()} 与 {@link JFrameModule#provides()}（懒加载路由键），
     * 此时不创建任何对象；首次 get 其 provides 类型时执行 load。
     *
     * @param module 模块描述符
     * @throws IllegalArgumentException 模块 id 重复，或 provides 类型与其他注册重叠
     */
    public void register(JFrameModule module) {
        Objects.requireNonNull(module, "module");
        ensureOpen();
        String id = requireId(module);
        if (modules.putIfAbsent(id, module) != null) {
            throw new IllegalArgumentException("模块 id 重复: " + id);
        }
        List<Class<?>> claimed = new ArrayList<>();
        for (Class<?> type : module.provides()) {
            if (providers.putIfAbsent(type, Provider.module(module)) == null) {
                claimed.add(type);
            } else {
                for (Class<?> back : claimed) {
                    providers.remove(back);
                }
                modules.remove(id);
                throw new IllegalArgumentException(
                        "类型 " + type.getName() + " 已被注册，模块 " + id + " 注册失败");
            }
        }
        moduleStates.put(id, ModuleState.REGISTERED);
    }

    /**
     * 形式 2 轻量重载：以 lambda 声明一个无生命周期回调的工厂类。
     *
     * @param id       模块 id
     * @param provides 产出类型集合
     * @param loader   加载逻辑（在其中的 ctx.register 登记全部产物）
     */
    public void register(String id, Set<Class<?>> provides, Consumer<ModuleContext> loader) {
        Objects.requireNonNull(loader, "loader");
        register(new LambdaModule(id, provides, loader));
    }

    /**
     * 形式 3（现成实例）：登记已构造完毕的对象（天然急切，其余形式均为懒加载）。
     * <p>
     * 私有 Bean；实例为 {@link PluginAware} 时绑定宿主插件。
     *
     * @param type     目标类型
     * @param instance 实例（不能为 {@code null}）
     * @param <T>      类型
     * @throws IllegalArgumentException 类型重复注册
     */
    public <T> void register(Class<T> type, T instance) {
        Objects.requireNonNull(instance, "instance");
        creationLock.lock();
        try {
            ensureOpen();
            claimType(type, Provider.instance(instance), "register(Class, Object)");
            instances.put(type, instance);
            afterProduced(type, instance, false);
        } finally {
            creationLock.unlock();
        }
    }

    // ==================== 获取 ====================

    /**
     * 按类型获取懒加载单例。
     *
     * @param type 注册过的类型
     * @param <T>  类型
     * @return 单例实例
     * @throws IllegalStateException    类型未注册 / 容器已关闭 / 产出 null / 循环依赖
     * @throws IllegalArgumentException 模块 provides 与实际登记不符（经模块加载传播）
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) {
        Objects.requireNonNull(type, "type");
        ensureOpen();
        Object cached = instances.get(type);
        if (cached != null) {
            return (T) cached;
        }
        creationLock.lock();
        try {
            cached = instances.get(type);
            if (cached != null) {
                return (T) cached;
            }
            Provider provider = providers.get(type);
            if (provider == null) {
                throw new IllegalStateException(
                        "类型未注册: " + type.getName() + "（可用类型: " + providers.keySet() + "）");
            }
            Set<Class<?>> stack = creating.get();
            if (!stack.add(type)) {
                throw new IllegalStateException(
                        "循环依赖: 正在创建 " + type.getName() + " 时又请求了它（路径: " + stack + "）");
            }
            try {
                Object instance = provider.create(type, this);
                instances.put(type, instance);
                afterProduced(type, instance, provider.service);
                return (T) instance;
            } finally {
                stack.remove(type);
            }
        } finally {
            creationLock.unlock();
        }
    }

    /**
     * 类型是否已注册（不触发创建）。
     *
     * @param type 目标类型
     * @return 已注册返回 true
     */
    public boolean isRegistered(Class<?> type) {
        return providers.containsKey(type) || instances.containsKey(type);
    }

    /**
     * 已注册的全部类型（快照，不触发创建）。
     *
     * @return 类型集合
     */
    public Set<Class<?>> registeredTypes() {
        Set<Class<?>> all = new LinkedHashSet<>(providers.keySet());
        all.addAll(instances.keySet());
        return all;
    }

    // ==================== 预加载 / 生命周期 ====================

    /**
     * 立即加载指定 id 的模块（等价于 get 其任一 provides 类型）。
     *
     * @param moduleIds 模块 id
     * @throws IllegalArgumentException id 不存在
     * @throws IllegalStateException    模块加载失败
     */
    public void preload(String... moduleIds) {
        Objects.requireNonNull(moduleIds, "moduleIds");
        creationLock.lock();
        try {
            ensureOpen();
            for (String id : moduleIds) {
                JFrameModule module = modules.get(id);
                if (module == null) {
                    throw new IllegalArgumentException("模块不存在: " + id + "（可用: " + modules.keySet() + "）");
                }
                loadModule(module);
            }
        } finally {
            creationLock.unlock();
        }
    }

    /**
     * 模块是否已成功加载。
     *
     * @param moduleId 模块 id
     * @return 已加载返回 true
     */
    public boolean isLoaded(String moduleId) {
        return moduleStates.get(moduleId) == ModuleState.LOADED;
    }

    /**
     * 绑定插件：进入绑定态并刷新暂存的 {@link PluginAware} 绑定与 ServiceManager 注册。
     * <p>
     * 宿主插件在 {@code onEnable} 中调用；此后登记/创建的对象立即绑定与注册。
     * 构造时宿主已启用（业务插件在 onEnable 里 new 容器）则自动进入绑定态，无需调用。
     *
     * @param plugin 绑定的插件实例
     */
    public void bindPlugin(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        creationLock.lock();
        try {
            bound = true;
            boundPlugin = plugin;
            for (PluginAware aware : pendingAware) {
                tryBind(aware);
            }
            pendingAware.clear();
            for (PendingService pending : pendingServices) {
                registerServiceNow(pending.type, pending.instance);
            }
            pendingServices.clear();
        } finally {
            creationLock.unlock();
        }
    }

    /**
     * 关服清理：仅对已加载模块按 {@link JFrameModule#shutdownOrder()} 升序（同序按 id）
     * 调用 {@link JFrameModule#shutdown(ModuleContext)}；单个模块异常被记录不中断。
     * 幂等；关闭后 get 与全部 register 拒绝服务。
     */
    public void shutdown() {
        creationLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            List<JFrameModule> loaded = modules.values().stream()
                    .filter(m -> moduleStates.get(m.id()) == ModuleState.LOADED)
                    .sorted(Comparator.comparingInt(JFrameModule::shutdownOrder)
                            .thenComparing(JFrameModule::id))
                    .toList();
            ModuleContext ctx = new ContextImpl(null);
            for (JFrameModule module : loaded) {
                try {
                    module.shutdown(ctx);
                } catch (Exception e) {
                    error("模块 " + module.id() + " 关闭失败", e);
                }
            }
        } finally {
            creationLock.unlock();
        }
    }

    // ==================== 注解扫描 ====================

    /**
     * 包扫描注册（零实例化，只登记元数据，保持懒加载语义）。
     * <p>
     * 发现 {@link JBean @JBean} 产品类（可选 {@link JFactory @JFactory} static 无参工厂方法，
     * 无则等价 {@link #register(Class)}，有则等价 {@link #register(Class, Supplier)}）与
     * {@link JFactoryClass @JFactoryClass} 工厂类（其 {@code @JBean} 方法无参或仅一个
     * {@link ModuleContext} 参数；工厂类本身在首次需要其产物时才实例化）。
     * {@code @JBean(service = true)} 的产物以宿主插件名义注册 ServiceManager，默认私有。
     *
     * @param classLoader  类加载器（业务插件传自身 {@code getClass().getClassLoader()}）
     * @param basePackages 基础包（含子包）
     * @return 登记成功的类集合
     * @throws IllegalArgumentException 扫描目标的声明歧义（多 @JFactory / 带 @JFactory 参数 /
     *                                  @JBean 方法 void 或参数不合法 / @JFactoryClass 无 @JBean 方法等）
     */
    public Set<Class<?>> scan(ClassLoader classLoader, String... basePackages) {
        Objects.requireNonNull(classLoader, "classLoader");
        ensureOpen();
        Set<Class<?>> registered = new LinkedHashSet<>();
        for (Class<?> cls : new AnnotatedClassScanner(JBean.class).scan(classLoader, basePackages)) {
            if (cls.isAnnotationPresent(JFactoryClass.class)) {
                throw new IllegalArgumentException(cls.getName() + " 同时标注了 @JBean 与 @JFactoryClass");
            }
            registerScannedProduct(cls);
            registered.add(cls);
        }
        for (Class<?> cls : new AnnotatedClassScanner(JFactoryClass.class).scan(classLoader, basePackages)) {
            registerScannedFactoryClass(cls);
            registered.add(cls);
        }
        return registered;
    }

    // ==================== 注解批量引导（急切 + 二次初始化） ====================

    /**
     * 注解层批量引导：包扫描 + 急切构造 + 二次初始化，三阶段执行。
     * <ol>
     *   <li><b>扫描登记</b>：等价 {@link #scan(ClassLoader, String...)}（含全部声明校验）；</li>
     *   <li><b>急切构造</b>：按扫描顺序逐个 get，全部实例构造并注册后才进入下一阶段
     *       （构造期零依赖假设由此保证：任何 bean 构造完成时，其余 bean 至少可按需创建）；</li>
     *   <li><b>二次初始化</b>：按扫描顺序调用各 bean 的
     *       {@link io.github.JiangHu.jframe.core.module.annotation.JInit @JInit} 方法
     *       （此时全部实例已存在，方法内 {@code ctx.get(X.class)} 绑定引用、
     *       {@code ctx.propXxx(...)} 读配置——字段间循环引用天然免疫）。</li>
     * </ol>
     * 与 {@link #scan} 的懒加载语义并存：boot 是"启动即全量"的便捷路径，
     * 适合启动期即需全部装配完成的插件（对齐原 Spring refresh 语义）。
     *
     * @param classLoader  类加载器（业务插件传自身 {@code getClass().getClassLoader()}）
     * @param properties   二次初始化阶段的配置来源（可为 null 表示无配置层）
     * @param basePackages 基础包（含子包）
     * @return 引导结果（类型与实例快照）
     * @throws IllegalArgumentException 扫描声明歧义或 @JInit 方法形态非法
     * @throws IllegalStateException    构造或二次初始化失败（fail-fast，插件启用应中止）
     */
    public BeanBootResult boot(ClassLoader classLoader, Properties properties, String... basePackages) {
        Objects.requireNonNull(classLoader, "classLoader");
        ensureOpen();
        Set<Class<?>> before = registeredTypes();
        scan(classLoader, basePackages);
        Set<Class<?>> booted = new LinkedHashSet<>(registeredTypes());
        booted.removeAll(before);

        // 声明校验前置于任何实例化：非法 @JInit 在构造发生前 fail-fast
        for (Class<?> type : booted) {
            requireValidInitMethod(type);
        }

        Map<Class<?>, Object> created = new LinkedHashMap<>();
        for (Class<?> type : booted) {
            created.put(type, get(type));
        }

        BeanContextImpl context = new BeanContextImpl(
                properties == null ? new Properties() : properties, created);
        for (Object bean : created.values()) {
            Method init = requireValidInitMethod(bean.getClass());
            if (init == null) {
                continue;
            }
            invokeInit(bean, init, context);
        }
        return new BeanBootResult(created);
    }

    /**
     * 查找并校验类的 @JInit 方法。
     *
     * @return 合法的 init 方法；无 @JInit 时返回 null
     * @throws IllegalArgumentException 形态非法（多方法 / 非 public / static / 有返回值 / 参数不合法）
     */
    private static Method requireValidInitMethod(Class<?> beanClass) {
        Method found = null;
        for (Method method : beanClass.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(JInit.class)) {
                continue;
            }
            if (found != null) {
                throw new IllegalArgumentException("类 " + beanClass.getName()
                        + " 声明了多个 @JInit 方法");
            }
            if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
                throw new IllegalArgumentException("@JInit 方法必须为 public 非 static: "
                        + beanClass.getName() + "." + method.getName());
            }
            if (method.getReturnType() != void.class) {
                throw new IllegalArgumentException("@JInit 方法必须为 void: "
                        + beanClass.getName() + "." + method.getName());
            }
            int params = method.getParameterCount();
            if (params != 0 && !(params == 1 && method.getParameterTypes()[0] == BeanContext.class)) {
                throw new IllegalArgumentException("@JInit 方法只能无参或仅有一个 BeanContext 参数: "
                        + beanClass.getName() + "." + method.getName());
            }
            tryAccessible(method);
            found = method;
        }
        return found;
    }

    private static void invokeInit(Object bean, Method init, BeanContext context) {
        try {
            if (init.getParameterCount() == 1) {
                init.invoke(bean, context);
            } else {
                init.invoke(bean);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("@JInit 方法不可访问: "
                    + bean.getClass().getName() + "." + init.getName(), e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("@JInit 方法抛出异常: "
                    + bean.getClass().getName() + "." + init.getName(), e.getCause());
        }
    }

    /**
     * 二次初始化阶段上下文实现：get 走容器，prop 走引导时传入的配置层，
     * allOf 限定本次引导范围。
     */
    private final class BeanContextImpl implements BeanContext {

        private final Properties properties;

        private final Map<Class<?>, Object> scope;

        BeanContextImpl(Properties properties, Map<Class<?>, Object> scope) {
            this.properties = properties;
            this.scope = scope;
        }

        @Override
        public <T> T get(Class<T> type) {
            return ModuleManager.this.get(type);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> allOf(Class<T> type) {
            java.util.List<T> matched = new java.util.ArrayList<>();
            for (Object instance : scope.values()) {
                if (type.isInstance(instance)) {
                    matched.add((T) instance);
                }
            }
            return matched;
        }

        @Override
        public String prop(String key, String def) {
            String value = properties.getProperty(key);
            return value == null ? def : value.trim();
        }

        @Override
        public int propInt(String key, int def) {
            String value = properties.getProperty(key);
            if (value == null) {
                return def;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("配置 " + key + "=" + value + " 不是合法 int");
            }
        }

        @Override
        public boolean propBoolean(String key, boolean def) {
            String value = properties.getProperty(key);
            return value == null ? def : Boolean.parseBoolean(value.trim());
        }

        @Override
        public double propDouble(String key, double def) {
            String value = properties.getProperty(key);
            if (value == null) {
                return def;
            }
            try {
                return Double.parseDouble(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("配置 " + key + "=" + value + " 不是合法 double");
            }
        }

        @Override
        public String[] propStrings(String key, String[] def) {
            String value = properties.getProperty(key);
            if (value == null) {
                return def;
            }
            return java.util.Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
        }
    }

    @Override
    public ModulePluginScope forPlugin(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return new ModulePluginScope(this, plugin.getClass().getClassLoader());
    }

    @Override
    public ModulePluginScope forPlugin(String pluginName) {
        Objects.requireNonNull(pluginName, "pluginName");
        Server server = currentServer();
        if (server == null) {
            throw new IllegalStateException("Server 未初始化，无法按名解析插件: " + pluginName);
        }
        Plugin plugin = server.getPluginManager().getPlugin(pluginName);
        if (plugin == null) {
            throw new IllegalArgumentException("插件不存在: " + pluginName);
        }
        return new ModulePluginScope(this, plugin.getClass().getClassLoader());
    }

    // ==================== 内部：扫描登记 ====================

    private void registerScannedProduct(Class<?> cls) {
        JBean annotation = cls.getAnnotation(JBean.class);
        Method factory = null;
        for (Method method : cls.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(JFactory.class)) {
                continue;
            }
            if (factory != null) {
                throw new IllegalArgumentException("类 " + cls.getName() + " 声明了多个 @JFactory 方法");
            }
            if (!Modifier.isStatic(method.getModifiers())) {
                throw new IllegalArgumentException("@JFactory 方法必须为 static: " + cls.getName()
                        + "." + method.getName());
            }
            if (method.getParameterCount() != 0) {
                throw new IllegalArgumentException("@JFactory 方法必须无参: " + cls.getName()
                        + "." + method.getName());
            }
            if (!cls.isAssignableFrom(method.getReturnType())) {
                throw new IllegalArgumentException("@JFactory 方法返回类型与产品类不兼容: " + cls.getName()
                        + "." + method.getName() + " -> " + method.getReturnType().getName());
            }
            tryAccessible(method);
            factory = method;
        }
        if (factory == null) {
            registerInternal(cls, Provider.reflection(cls), annotation.service());
        } else {
            Method f = factory;
            registerInternal(cls, Provider.factory(() -> {
                try {
                    return requireProduct(cls, f.invoke(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("@JFactory 方法不可访问: " + cls.getName()
                            + "." + f.getName(), e);
                } catch (InvocationTargetException e) {
                    throw new IllegalStateException("@JFactory 工厂方法抛出异常: " + cls.getName()
                            + "." + f.getName(), e.getCause());
                }
            }), annotation.service());
        }
    }

    private void registerScannedFactoryClass(Class<?> cls) {
        Constructor<?> constructor = requireNoArgConstructor(cls, "@JFactoryClass 工厂类");
        List<FactoryMethod> methods = new ArrayList<>();
        for (Method method : cls.getDeclaredMethods()) {
            JBean annotation = method.getAnnotation(JBean.class);
            if (annotation == null) {
                continue;
            }
            if (!Modifier.isPublic(method.getModifiers())) {
                throw new IllegalArgumentException("@JBean 方法必须为 public: " + cls.getName()
                        + "." + method.getName());
            }
            if (method.getReturnType() == void.class || method.getReturnType() == Void.class) {
                throw new IllegalArgumentException("@JBean 方法不能为 void: " + cls.getName()
                        + "." + method.getName());
            }
            int params = method.getParameterCount();
            boolean wantsContext = params == 1 && method.getParameterTypes()[0] == ModuleContext.class;
            if (params != 0 && !wantsContext) {
                throw new IllegalArgumentException("@JBean 方法只能无参或仅有一个 ModuleContext 参数: "
                        + cls.getName() + "." + method.getName());
            }
            tryAccessible(method);
            methods.add(new FactoryMethod(method, annotation.service(), wantsContext));
        }
        if (methods.isEmpty()) {
            throw new IllegalArgumentException("@JFactoryClass " + cls.getName() + " 没有任何 @JBean 方法");
        }
        Set<Class<?>> types = new LinkedHashSet<>();
        for (FactoryMethod m : methods) {
            if (!types.add(m.method().getReturnType())) {
                throw new IllegalArgumentException("@JFactoryClass " + cls.getName()
                        + " 的多个 @JBean 方法产出同一类型: " + m.method().getReturnType().getName());
            }
        }
        // 每个 @JBean 方法注册为独立懒加载 provider（工厂类实例惰性共享单例）。
        // 独立懒加载让同工厂内 B 方法通过 ctx.get(A.class) 拉取 A 方法产物时不会
        // 触发"整模块一次性 load"的自锁——这是注解层与手写工厂类（一次性产出整个
        // 对象图）的关键差异。
        LazyFactoryInstance factory = new LazyFactoryInstance(cls, constructor);
        ModuleContext context = new ContextImpl(null);
        for (FactoryMethod m : methods) {
            Class<?> type = m.method().getReturnType();
            registerInternal(type, Provider.factory(() -> invokeBeanMethod(cls, m, factory, context)),
                    m.service());
        }
    }

    /**
     * 调用工厂类的一个 @JBean 方法产出实例。
     */
    private static Object invokeBeanMethod(Class<?> factoryClass, FactoryMethod m,
                                           LazyFactoryInstance factory, ModuleContext context) {
        Object receiver;
        if (Modifier.isStatic(m.method().getModifiers())) {
            receiver = null;
        } else {
            receiver = factory.instance();
        }
        try {
            Object product = m.wantsContext()
                    ? m.method().invoke(receiver, context)
                    : m.method().invoke(receiver);
            if (product == null) {
                throw new IllegalStateException("@JBean 方法返回 null: " + factoryClass.getName()
                        + "." + m.method().getName());
            }
            return product;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("@JBean 方法不可访问: " + factoryClass.getName()
                    + "." + m.method().getName(), e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("@JBean 方法抛出异常: " + factoryClass.getName()
                    + "." + m.method().getName(), e.getCause());
        }
    }

    /**
     * 工厂类的惰性单例持有者：首次任一非 static @JBean 方法执行时实例化一次。
     */
    private static final class LazyFactoryInstance {
        private final Class<?> factoryClass;
        private final Constructor<?> constructor;
        private volatile Object instance;

        LazyFactoryInstance(Class<?> factoryClass, Constructor<?> constructor) {
            this.factoryClass = factoryClass;
            this.constructor = constructor;
        }

        Object instance() {
            Object result = instance;
            if (result == null) {
                synchronized (this) {
                    result = instance;
                    if (result == null) {
                        try {
                            result = constructor.newInstance();
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException(
                                    "实例化工厂类失败: " + factoryClass.getName(), e);
                        }
                        instance = result;
                    }
                }
            }
            return result;
        }
    }

    private record FactoryMethod(Method method, boolean service, boolean wantsContext) {
    }

    // ==================== 内部：提供者与创建 ====================

    private static final class Provider {
        enum Kind { REFLECTION, FACTORY, MODULE }

        final Kind kind;
        final Class<?> target;
        final Supplier<Object> creator;
        final JFrameModule module;
        final boolean service;

        private Provider(Kind kind, Class<?> target, Supplier<Object> creator,
                         JFrameModule module, boolean service) {
            this.kind = kind;
            this.target = target;
            this.creator = creator;
            this.module = module;
            this.service = service;
        }

        static Provider reflection(Class<?> type) {
            Constructor<?> constructor = requireNoArgConstructor(type, "类型 " + type.getName());
            return new Provider(Kind.REFLECTION, type, () -> {
                try {
                    return constructor.newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("实例化失败: " + type.getName(), e);
                }
            }, null, false);
        }

        static Provider factory(Supplier<Object> creator) {
            return new Provider(Kind.FACTORY, null, creator, null, false);
        }

        static Provider instance(Object obj) {
            return new Provider(Kind.FACTORY, null, () -> obj, null, false);
        }

        static Provider module(JFrameModule module) {
            return new Provider(Kind.MODULE, null, null, module, false);
        }

        Object create(Class<?> type, ModuleManager manager) {
            if (kind == Kind.MODULE) {
                manager.loadModule(module);
                Object instance = manager.instances.get(type);
                if (instance == null) {
                    throw new IllegalStateException("模块 " + module.id()
                            + " 加载完成但未产出声明类型 " + type.getName());
                }
                return instance;
            }
            return creator.get();
        }
    }

    private void loadModule(JFrameModule module) {
        String id = module.id();
        ModuleState state = moduleStates.get(id);
        if (state == ModuleState.LOADED) {
            return;
        }
        if (state == ModuleState.LOADING) {
            throw new IllegalStateException("模块循环加载: " + id);
        }
        if (state == ModuleState.FAILED) {
            throw new IllegalStateException("模块 " + id + " 此前加载失败，拒绝再次加载");
        }
        moduleStates.put(id, ModuleState.LOADING);
        try {
            for (String required : module.requires()) {
                JFrameModule dependency = modules.get(required);
                if (dependency == null) {
                    throw new IllegalArgumentException("依赖模块不存在: " + required
                            + "（模块 " + id + " 的 requires）");
                }
                loadModule(dependency);
            }
            module.load(new ContextImpl(module));
            for (Class<?> type : module.provides()) {
                if (!instances.containsKey(type)) {
                    throw new IllegalStateException("模块 " + id + " 未产出声明的类型 " + type.getName());
                }
            }
            moduleStates.put(id, ModuleState.LOADED);
            info("模块已加载: " + id);
        } catch (Exception e) {
            moduleStates.put(id, ModuleState.FAILED);
            throw new IllegalStateException("模块加载失败: " + id, e);
        }
    }

    private final class ContextImpl implements ModuleContext {
        private final JFrameModule module;

        ContextImpl(JFrameModule module) {
            this.module = module;
        }

        @Override
        public Plugin plugin() {
            return owner;
        }

        @Override
        public <T> T get(Class<T> type) {
            return ModuleManager.this.get(type);
        }

        @Override
        public <T> T register(Class<T> apiType, T instance) {
            registerFromModule(apiType, instance, true);
            return instance;
        }

        @Override
        public <T> T registerPrivate(Class<T> type, T instance) {
            registerFromModule(type, instance, false);
            return instance;
        }

        @Override
        public void bind(Object bean) {
            if (bean instanceof PluginAware aware) {
                bindOrDefer(aware);
            }
        }

        private void registerFromModule(Class<?> type, Object instance, boolean service) {
            if (instance == null) {
                throw new IllegalStateException("模块 " + (module != null ? module.id() : "?")
                        + " 登记了 null 实例: " + type.getName());
            }
            Provider provider = providers.get(type);
            if (instances.containsKey(type)
                    || provider == null || provider.module != module) {
                throw new IllegalArgumentException("模块 " + (module != null ? module.id() : "?")
                        + " 登记的类型 " + type.getName()
                        + " 不在其 provides() 声明中，或已被其他注册占用");
            }
            instances.put(type, instance);
            afterProduced(type, instance, service);
        }
    }

    private static final class LambdaModule implements JFrameModule {
        private final String id;
        private final Set<Class<?>> provides;
        private final Consumer<ModuleContext> loader;

        LambdaModule(String id, Set<Class<?>> provides, Consumer<ModuleContext> loader) {
            this.id = requireNonBlank(id, "模块 id 不能为空");
            this.provides = Set.copyOf(Objects.requireNonNull(provides, "provides"));
            if (this.provides.isEmpty()) {
                throw new IllegalArgumentException("模块 " + id + " 的 provides 不能为空");
            }
            this.loader = loader;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Set<Class<?>> provides() {
            return provides;
        }

        @Override
        public void load(ModuleContext ctx) {
            loader.accept(ctx);
        }
    }

    private record PendingService(Class<?> type, Object instance) {
    }

    private enum ModuleState { REGISTERED, LOADING, LOADED, FAILED }

    // ==================== 内部：注册与产物公共路径 ====================

    private void registerInternal(Class<?> type, Provider provider, boolean service) {
        creationLock.lock();
        try {
            ensureOpen();
            claimType(type, provider, "register");
            providers.put(type, withService(provider, service));
        } finally {
            creationLock.unlock();
        }
    }

    private void claimType(Class<?> type, Provider provider, String source) {
        if (providers.putIfAbsent(type, provider) != null) {
            throw new IllegalArgumentException("类型重复注册: " + type.getName() + "（来源: " + source + "）");
        }
    }

    private static Provider withService(Provider provider, boolean service) {
        if (!service || provider.service) {
            return provider;
        }
        return new Provider(provider.kind, provider.target, provider.creator, provider.module, true);
    }

    private void afterProduced(Class<?> type, Object instance, boolean service) {
        if (instance instanceof PluginAware aware) {
            bindOrDefer(aware);
        }
        if (service) {
            serviceOrDefer(type, instance);
        }
    }

    private void bindOrDefer(PluginAware aware) {
        Plugin plugin = boundPlugin;
        if (bound && plugin != null) {
            tryBind(aware);
        } else {
            synchronized (pendingAware) {
                pendingAware.add(aware);
            }
        }
    }

    private void tryBind(PluginAware aware) {
        Plugin plugin = boundPlugin;
        if (plugin == null) {
            return;
        }
        try {
            aware.bindPlugin(plugin);
        } catch (Exception e) {
            error("绑定 plugin 到 " + aware.getClass().getName() + " 失败", e);
        }
    }

    private void serviceOrDefer(Class<?> type, Object instance) {
        if (bound) {
            registerServiceNow(type, instance);
        } else {
            synchronized (pendingServices) {
                pendingServices.add(new PendingService(type, instance));
            }
        }
    }

    private void registerServiceNow(Class<?> type, Object instance) {
        if (owner == null) {
            return;
        }
        try {
            Server server = owner.getServer();
            if (server != null) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Class rawType = type;
                server.getServiceManager().register(rawType, instance, owner, ServicePriority.NORMAL);
            }
        } catch (Exception e) {
            error("注册服务失败: " + type.getName(), e);
        }
    }

    // ==================== 内部：工具 ====================

    private static Object requireProduct(Class<?> type, Object product) {
        if (product == null) {
            throw new IllegalStateException("工厂产出 null: " + type.getName());
        }
        return product;
    }

    private static Constructor<?> requireNoArgConstructor(Class<?> type, String description) {
        Constructor<?> constructor;
        try {
            constructor = type.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(description + " 缺少无参构造（也未声明工厂方法）", e);
        }
        if (!Modifier.isPublic(constructor.getModifiers())) {
            try {
                constructor.setAccessible(true);
            } catch (Exception e) {
                throw new IllegalArgumentException(description + " 的无参构造器不可访问（非 public 且无法提权）", e);
            }
        }
        return constructor;
    }

    private static void tryAccessible(java.lang.reflect.AccessibleObject accessible) {
        try {
            accessible.setAccessible(true);
        } catch (Exception ignored) {
            // 同类加载器环境下通常无需提权；失败则依赖原有的可访问性
        }
    }

    private static String requireId(JFrameModule module) {
        return requireNonBlank(module.id(), "模块 id 不能为空: " + module.getClass().getName());
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("ModuleManager 已关闭，拒绝操作");
        }
    }

    private Server currentServer() {
        if (owner != null) {
            try {
                return owner.getServer();
            } catch (Exception ignored) {
                // 宿主插件尚未初始化完成时回退到全局 Server
            }
        }
        return Server.getInstance();
    }

    private void info(String message) {
        if (owner != null) {
            owner.getLogger().info("[ModuleManager] " + message);
        }
    }

    private void error(String message, Throwable t) {
        if (owner != null) {
            owner.getLogger().error("[ModuleManager] " + message, t);
        } else {
            System.err.println("[ModuleManager] " + message + ": " + t);
        }
    }
}
