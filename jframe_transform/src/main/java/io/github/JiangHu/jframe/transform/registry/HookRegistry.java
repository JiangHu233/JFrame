package io.github.JiangHu.jframe.transform.registry;

import io.github.JiangHu.jframe.transform.HookHandle;
import io.github.JiangHu.jframe.transform.HookPriority;
import io.github.JiangHu.jframe.transform.HookStatus;
import io.github.JiangHu.jframe.transform.InstrumentShape;
import io.github.JiangHu.jframe.transform.agent.AgentInstaller;
import io.github.JiangHu.jframe.transform.agent.AttachSource;
import io.github.JiangHu.jframe.transform.bytebuddy.TransformerInstaller;
import io.github.JiangHu.jframe.transform.bridge.Bridge;
import io.github.JiangHu.jframe.transform.bridge.MethodHook;
import io.github.JiangHu.jframe.transform.bridge.ReplaceHook;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 钩子注册表——模块心脏（设计方案 §3.5）。
 * <ul>
 *   <li><b>规格表</b>：{@link TargetSpec} 列表（不可变快照、锁内变更）驱动类匹配
 *       与方法匹配（{@code *} 通配）</li>
 *   <li><b>方法登记表</b>：methodKey → {@link MethodRegistration}（分发 id、链、
 *       织入形状、返回值元数据）；id 织入时分配并内联，永不重组</li>
 *   <li><b>形状协调</b>：同形状增删钩子仅改链（零 retransform）；形状跃迁标脏类，
 *       由刷新驱动重织；链空则类还原</li>
 *   <li><b>分发</b>：{@link BridgeSinkImpl} 按 id 查表执行（volatile 读零锁）</li>
 * </ul>
 * 线程模型：注册/卸载/织入计划在 {@code mainLock} 内（低频）；分发走并发表只读。
 */
public final class HookRegistry {

    /** JDK 前缀黑名单（bootstrap 类不可 hook，注册期与匹配期双重拒绝） */
    private static final String[] REJECT_PREFIXES = {
            "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun."
    };

    private final Object mainLock = new Object();

    /** 目标规格（不可变快照引用，锁内整体替换） */
    private volatile List<TargetSpec> specs = List.of();

    /** regId → 注册（句柄状态） */
    private final Map<Integer, Registration> registrations = new ConcurrentHashMap<>();

    /** 插件名 → regId 集（disable 自动 unhook） */
    private final Map<String, Set<Integer>> pluginRegs = new ConcurrentHashMap<>();

    /** methodKey → 方法登记 */
    private final Map<String, MethodRegistration> methodRegs = new ConcurrentHashMap<>();

    /** 分发 id → 方法登记（内联 id 查表） */
    private final Map<Integer, MethodRegistration> byId = new ConcurrentHashMap<>();

    /** 已织入类名 → 方法 key 集（还原驱动） */
    private final Map<String, Set<String>> wovenClasses = new ConcurrentHashMap<>();

    /** 待重织/还原类名（刷新驱动消费） */
    private final Set<String> dirtyClasses = new CopyOnWriteArraySet<>();

    private final AtomicInteger idSeq = new AtomicInteger(1);
    private final AtomicInteger regSeq = new AtomicInteger(1);

    /** 分发总开关（关服清理时置 false，内联指令空转） */
    private volatile boolean dispatchEnabled = true;

    /** 已安装的 transformer（shutdown 时移除） */
    private volatile ClassFileTransformer transformer;

    /** 刷新驱动（transformer 安装后注入） */
    private volatile RefreshDriver driver;

    /** 关服后禁止再注册 */
    private volatile boolean closed;

    /** retransform 类次计数（测试探针） */
    private final AtomicInteger retransformCount = new AtomicInteger();

    // ==================== agent ====================

    /**
     * 确保 agent / transformer / bridge 分发回调就绪（首次注册与 isAvailable 探测触发）。
     * bridge sink 在 bridge jar 注入完成之后装载（Bridge 类此时才可解析）。
     */
    public void ensureAgent() {
        if (driver != null) {
            return;
        }
        synchronized (mainLock) {
            if (driver != null || closed) {
                return;
            }
            Instrumentation inst = AgentInstaller.ensureInstalled();
            this.transformer = TransformerInstaller.install(this, inst);
            this.driver = new RefreshDriver(this, inst);
            Bridge.sink = new BridgeSinkImpl(this);
        }
    }

    /**
     * agent 是否就绪（触发一次尝试安装，吞异常）。
     *
     * @return true 可用
     */
    public boolean isAvailable() {
        if (driver != null) {
            return true;
        }
        try {
            ensureAgent();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Instrumentation 获取途径。
     *
     * @return 途径（未安装 NONE）
     */
    public AttachSource attachSource() {
        return AgentInstaller.source();
    }

    // ==================== 注册 ====================

    /**
     * 注册方法钩子（命令式接口形态或注解适配器形态共用入口）。
     *
     * @param pluginName   归属插件名
     * @param classPattern 类名（支持 {@code * } 通配）
     * @param methodName   方法名（支持 {@code *} 通配）
     * @param paramTypes   参数类型名（null = 任意重载）
     * @param hook         before/after 钩子（可 null）
     * @param replace      REPLACE 无类型实现（可 null）
     * @param mirror       REPLACE 签名镜像（可 null）
     * @param priority     优先级
     * @param needsArgs    before/REPLACE 阶段是否需要参数数组
     * @return 句柄
     * @throws IllegalArgumentException 目标非法（JDK 前缀）或钩子无有效相位
     */
    public HookHandle register(String pluginName, String classPattern, String methodName,
                               List<String> paramTypes, MethodHook hook, ReplaceHook replace,
                               HookRecord.MirrorInfo mirror, HookPriority priority, boolean needsArgs) {
        if (closed) {
            throw new IllegalStateException("transform 模块已关闭（关服清理后不可再注册）");
        }
        rejectJdk(classPattern);
        if (hook != null && (replace != null || mirror != null)) {
            throw new IllegalArgumentException("hook 与 replace/mirror 不可同时提供");
        }
        boolean enter = (hook != null && hookOccupiesEnter(hook)) || replace != null || mirror != null;
        boolean exit = hook != null && hookOccupiesExit(hook);
        if (!enter && !exit) {
            throw new IllegalArgumentException(
                    "钩子无有效相位：before/after 均为空实现（未 override 或空方法体），拒绝注册");
        }

        ensureAgent();

        int regId = regSeq.getAndIncrement();
        TargetSpec spec = new TargetSpec(regId, classPattern, methodName, paramTypes);
        HookRecord record = new HookRecord(pluginName, priority == null ? HookPriority.NORMAL : priority,
                regId, hook, replace, mirror, needsArgs, enter, exit);
        Registration reg = new Registration(regId, spec, record);

        Set<String> dirty = new LinkedHashSet<>();
        synchronized (mainLock) {
            List<TargetSpec> next = new ArrayList<>(specs);
            next.add(spec);
            next.sort(Comparator.comparingInt(s -> s.regId));
            this.specs = List.copyOf(next);
            registrations.put(regId, reg);
            pluginRegs.computeIfAbsent(pluginName, k -> ConcurrentHashMap.newKeySet()).add(regId);
            // 已织入方法中匹配本规格的：原地重建链（形状不变则零 retransform）
            for (MethodRegistration mr : methodRegs.values()) {
                if (spec.matchesClass(mr.className) && spec.matchesMethod(mr.methodName)
                        && spec.matchesParams(mr.paramTypeNames)) {
                    rebuildChainLocked(mr, dirty);
                }
            }
        }
        refresh(dirty, spec);
        return new RegistryHandle(reg);
    }

    /**
     * 接口形态钩子是否实际占用 enter 相位（override 且方法体非空）。
     */
    private boolean hookOccupiesEnter(MethodHook hook) {
        return EmptyPhaseDetector.occupiesEnter(hook);
    }

    private boolean hookOccupiesExit(MethodHook hook) {
        return EmptyPhaseDetector.occupiesExit(hook);
    }

    // ==================== 卸载 / 清理 ====================

    /**
     * 撤销一条注册（幂等）。
     */
    public void unhook(int regId) {
        Registration reg = registrations.get(regId);
        if (reg == null) {
            return;
        }
        Set<String> dirty = new LinkedHashSet<>();
        synchronized (mainLock) {
            if (registrations.remove(regId) == null) {
                return;
            }
            Set<Integer> ids = pluginRegs.get(reg.record.pluginName);
            if (ids != null) {
                ids.remove(regId);
            }
            List<TargetSpec> next = new ArrayList<>(specs);
            next.removeIf(s -> s.regId == regId);
            this.specs = List.copyOf(next);
            for (MethodRegistration mr : methodRegs.values()) {
                if (containsRecord(mr, regId)) {
                    rebuildChainLocked(mr, dirty);
                }
            }
        }
        refresh(dirty, null);
    }

    /**
     * 撤销某插件全部注册（PluginDisableEvent 自动 unhook；停分发优先）。
     */
    public void unhookPlugin(String pluginName) {
        Set<Integer> ids = pluginRegs.get(pluginName);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (Integer regId : List.copyOf(ids)) {
            unhook(regId);
        }
    }

    /**
     * 模块关服清理（设计方案 §3.6）：停分发 → 移除 transformer → 全部织入类还原 → 清空。
     */
    public void shutdown() {
        Set<String> dirty = new LinkedHashSet<>();
        synchronized (mainLock) {
            if (closed) {
                return;
            }
            dispatchEnabled = false;
            closed = true;
            dirty.addAll(wovenClasses.keySet());
            specs = List.of();
            registrations.clear();
            pluginRegs.clear();
            methodRegs.clear();
            byId.clear();
            wovenClasses.clear();
            dirtyClasses.clear();
        }
        RefreshDriver d = driver;
        ClassFileTransformer t = transformer;
        if (d != null && t != null) {
            d.restoreAll(dirty, t);
        }
        this.driver = null;
        this.transformer = null;
    }

    // ==================== 织入期（transformer 回调） ====================

    /**
     * 类名是否为当前目标（matcher 快路径；JDK 前缀在此一并拒绝）。
     */
    public boolean isTargetClass(String className) {
        if (isJdk(className)) {
            return false;
        }
        List<TargetSpec> snapshot = specs;
        for (TargetSpec spec : snapshot) {
            if (spec.matchesClass(className)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 类是否已有织入方法（刷新驱动：已织入且形状未变的类跳过 retransform）。
     */
    boolean isWovenClass(String className) {
        return wovenClasses.containsKey(className);
    }

    /**
     * 新规格是否要求对某已织入类再织一次（其目标方法尚未织入 / 类未织入）。
     *
     * @param spec      本次新增规格（null = 卸载场景，仅靠 dirty 驱动）
     * @param className 候选类名
     * @return true 需要对该类 retransform
     */
    boolean needsActivationWeave(TargetSpec spec, String className) {
        if (spec == null || !spec.matchesClass(className)) {
            return false;
        }
        if (!wovenClasses.containsKey(className)) {
            return true;   // 类从未织入：激活
        }
        for (MethodRegistration mr : methodRegs.values()) {
            if (mr.className.equals(className)
                    && spec.matchesMethod(mr.methodName)
                    && spec.matchesParams(mr.paramTypeNames)) {
                return false;   // 该规格目标方法已织入且形状未变（原地改链即可）
            }
        }
        return true;   // 同类新方法首次挂钩：重织
    }

    /**
     * 为（类，方法）生成织入计划——织入/重织时的唯一入口：创建或复用方法登记、
     * 按当前规格重建链、校验签名镜像、记录织入形状。
     *
     * @param className      类名
     * @param methodName     方法名
     * @param descriptor     方法描述符
     * @param paramTypeNames 参数类型名
     * @param isStatic       是否 static
     * @param returnTypeName 返回类型名（void 为 "void"）
     * @return 织入计划；无匹配规格返回 null
     */
    public WeavePlan weavePlan(String className, String methodName, String descriptor,
                               List<String> paramTypeNames, boolean isStatic, String returnTypeName) {
        synchronized (mainLock) {
            if (closed) {
                return null;
            }
            boolean specHit = false;
            List<TargetSpec> snapshot = specs;
            for (TargetSpec spec : snapshot) {
                if (spec.matchesClass(className) && spec.matchesMethod(methodName)
                        && spec.matchesParams(paramTypeNames)) {
                    specHit = true;
                    break;
                }
            }
            if (!specHit) {
                return null;
            }
            boolean voidReturn = "void".equals(returnTypeName);
            boolean primitiveReturn = isPrimitive(returnTypeName);
            String key = methodKey(className, methodName, descriptor);
            MethodRegistration mr = methodRegs.get(key);
            if (mr == null) {
                mr = new MethodRegistration(idSeq.getAndIncrement(), className, methodName, descriptor,
                        List.copyOf(paramTypeNames), paramTypeNames.size(), isStatic,
                        voidReturn, returnTypeName, primitiveReturn);
                methodRegs.put(key, mr);
                byId.put(mr.id, mr);
            }
            rebuildChainLocked(mr, null);
            WeavePlan plan = new WeavePlan();
            plan.id = mr.id;
            plan.needsEnter = mr.wovenEnter;
            plan.needsArgs = mr.wovenArgs;
            plan.needsExit = mr.wovenExit;
            wovenClasses.computeIfAbsent(className, k -> ConcurrentHashMap.newKeySet()).add(key);
            return plan;
        }
    }

    /**
     * 重建链并计算形状差异（mainLock 内）。
     *
     * @param dirty 形状变化/链空的类收集（织入期传 null —— 织入即按新形状落盘）
     */
    private void rebuildChainLocked(MethodRegistration mr, Set<String> dirty) {
        List<HookRecord> chain = new ArrayList<>();
        int replaceCount = 0;
        List<TargetSpec> snapshot = specs;
        for (TargetSpec spec : snapshot) {
            if (!spec.matchesClass(mr.className) || !spec.matchesMethod(mr.methodName)
                    || !spec.matchesParams(mr.paramTypeNames)) {
                continue;
            }
            Registration reg = registrations.get(spec.regId);
            if (reg == null) {
                continue;
            }
            HookRecord rec = reg.record;
            if (rec.mirror != null) {
                MirrorCheck check = validateMirror(rec.mirror, mr);
                if (!check.valid) {
                    reg.markFailed(check.reason);
                    if (!reg.mirrorWarned) {
                        reg.mirrorWarned = true;
                        TLog.warn("REPLACE 签名镜像不匹配，已丢弃: " + check.reason);
                    }
                    continue;
                }
                rec.mirror.resolvedInstanceParam = Boolean.TRUE.equals(check.instanceParam);
            }
            chain.add(rec);
            if (rec.replace != null || rec.mirror != null) {
                replaceCount++;
            }
        }
        chain.sort(Comparator.comparingInt((HookRecord r) -> r.priority.level()).reversed()
                .thenComparingInt(r -> r.regId));
        if (replaceCount > 1) {
            TLog.warn("方法 " + mr.className + "#" + mr.methodName + " 存在 " + replaceCount
                    + " 个 REPLACE：优先级最高者胜出，其余跳过");
        }

        String selfKey = methodKey(mr.className, mr.methodName, mr.descriptor);
        if (chain.isEmpty()) {
            byId.remove(mr.id);
            methodRegs.remove(selfKey);
            Set<String> keys = wovenClasses.get(mr.className);
            if (keys != null) {
                keys.remove(selfKey);
                if (keys.isEmpty()) {
                    wovenClasses.remove(mr.className);
                }
            }
            if (dirty != null && wasWoven(mr)) {
                dirty.add(mr.className);   // 还原为原始字节码
            }
            detachFromRegistrations(mr);
            return;
        }

        boolean[] req = MethodRegistration.requirementsOf(chain, mr.voidReturn);
        boolean wasWoven = wasWoven(mr);
        boolean shapeChanged = wasWoven
                && (req[0] != mr.wovenEnter || req[2] != mr.wovenExit || req[1] != mr.wovenArgs);
        mr.chain = List.copyOf(chain);
        mr.hasAfter = req[3];
        mr.wovenEnter = req[0];
        mr.wovenExit = req[2];
        mr.wovenArgs = req[1];
        if (dirty != null && shapeChanged) {
            dirty.add(mr.className);   // 形状跃迁 → 重织
        }
        attachToRegistrations(mr);
    }

    /** 方法登记是否曾织入（还原判定用） */
    private boolean wasWoven(MethodRegistration mr) {
        return mr.wovenEnter || mr.wovenExit;
    }

    private void attachToRegistrations(MethodRegistration mr) {
        for (HookRecord rec : mr.chain) {
            Registration reg = registrations.get(rec.regId);
            if (reg != null) {
                reg.methods.add(mr);
                reg.clearFailed();
            }
        }
    }

    private void detachFromRegistrations(MethodRegistration mr) {
        for (HookRecord rec : mr.chain) {
            Registration reg = registrations.get(rec.regId);
            if (reg != null) {
                reg.methods.remove(mr);
            }
        }
    }

    private boolean containsRecord(MethodRegistration mr, int regId) {
        for (HookRecord rec : mr.chain) {
            if (rec.regId == regId) {
                return true;
            }
        }
        return false;
    }

    // ==================== 刷新驱动入口 ====================

    /**
     * 注册/卸载后的类刷新：脏类重织 + 已加载未织入的目标类激活。
     *
     * @param dirty   形状跃迁/待还原类
     * @param newSpec 本次新增规格（激活已加载类；卸载传 null）
     */
    void refresh(Set<String> dirty, TargetSpec newSpec) {
        RefreshDriver d = driver;
        if (d == null) {
            return;
        }
        d.refresh(dirty, newSpec, retransformCount);
    }

    /**
     * 类织入/增强失败回调（AgentBuilder Listener / retransform 异常）。
     */
    public void markClassFailed(String className, Throwable error) {
        TLog.error("类增强失败: " + className, error);
        for (Registration reg : registrations.values()) {
            for (MethodRegistration mr : reg.methods) {
                if (mr.className.equals(className)) {
                    reg.markFailed("类增强失败: " + error);
                    break;
                }
            }
        }
    }

    /**
     * retransform 累计类次（测试探针）。
     */
    public int retransformCount() {
        return retransformCount.get();
    }

    /**
     * 判定 (HookContext)→void 方法体是否为空（注解层空相位检测）。
     *
     * @param cls        宿主类
     * @param methodName 方法名
     * @return true 空方法体
     */
    public boolean isPhaseBodyEmpty(Class<?> cls, String methodName) {
        return EmptyPhaseDetector.isBodyEmpty(cls, methodName);
    }

    /**
     * 空注解方法警告（扫描层跳过时调用）。
     *
     * @param where 类#方法
     */
    public void warnEmptyAnnotatedMethod(String where) {
        TLog.warn("@HookOn 方法体为空（视为空相位，已跳过）: " + where);
    }

    // ==================== 分发（sink 侧） ====================

    /**
     * 按 id 取方法登记（分发热路径）。
     */
    MethodRegistration dispatchLookup(int id) {
        if (!dispatchEnabled) {
            return null;
        }
        return byId.get(id);
    }

    // ==================== 内部工具 ====================

    private static String methodKey(String className, String methodName, String descriptor) {
        return className + '#' + methodName + descriptor;
    }

    static boolean isJdk(String className) {
        for (String prefix : REJECT_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static void rejectJdk(String className) {
        if (isJdk(className) || className.startsWith("[")) {
            throw new IllegalArgumentException("拒绝 hook JDK/bootstrap 类: " + className
                    + "（java./javax./jakarta./jdk./sun./com.sun. 与数组不可作为目标）");
        }
    }

    private static boolean isPrimitive(String typeName) {
        switch (typeName) {
            case "void":
            case "boolean":
            case "byte":
            case "short":
            case "int":
            case "long":
            case "float":
            case "double":
            case "char":
                return true;
            default:
                return false;
        }
    }

    /**
     * 签名镜像校验：镜像方法签名 vs 目标方法（严格——基本类型对基本类型、返回完全一致、
     * 实例参规则与目标 static 性吻合）。通过后把"是否含实例参"裁定写回镜像元数据。
     */
    private static MirrorCheck validateMirror(HookRecord.MirrorInfo mirror, MethodRegistration mr) {
        List<String> mirrorParams = mirror.paramTypeNames;
        boolean instanceParam;
        List<String> compareFrom;
        if (mr.isStatic) {
            // static 目标：镜像无实例参，参数逐位对应
            instanceParam = false;
            compareFrom = mirrorParams;
            if (mirrorParams.size() != mr.paramTypeNames.size()) {
                return new MirrorCheck(false, null, mismatchMessage(mirror, mr));
            }
        } else {
            // 实例目标：镜像首参 = 目标实例，其余逐位对应
            instanceParam = true;
            compareFrom = mirrorParams.subList(Math.min(1, mirrorParams.size()), mirrorParams.size());
            if (mirrorParams.size() != mr.paramTypeNames.size() + 1) {
                return new MirrorCheck(false, null, mismatchMessage(mirror, mr));
            }
            String selfType = mirrorParams.get(0);
            if (!selfType.equals(mr.className)) {
                return new MirrorCheck(false, null, "镜像实例参类型 " + selfType
                        + " 与目标类 " + mr.className + " 不一致");
            }
        }
        if (!compareFrom.equals(mr.paramTypeNames)) {
            return new MirrorCheck(false, null, mismatchMessage(mirror, mr));
        }
        if (!mirror.returnTypeName.equals(mr.returnTypeName)) {
            return new MirrorCheck(false, null,
                    "返回类型不一致: 目标 " + mr.returnTypeName + " vs 镜像 " + mirror.returnTypeName);
        }
        return new MirrorCheck(true, instanceParam, null);
    }

    private static String mismatchMessage(HookRecord.MirrorInfo mirror, MethodRegistration mr) {
        return "签名镜像不匹配: 目标 " + (mr.isStatic ? "static " : "") + mr.className + "#"
                + mr.methodName + mr.paramTypeNames + " vs 镜像 " + mirror.paramTypeNames;
    }

    private static final class MirrorCheck {
        final boolean valid;
        final Boolean instanceParam;
        final String reason;

        MirrorCheck(boolean valid, Boolean instanceParam, String reason) {
            this.valid = valid;
            this.instanceParam = instanceParam;
            this.reason = reason;
        }
    }

    // ==================== 数据 holder ====================

    /** 注册级状态（句柄聚合源） */
    static final class Registration {
        final int regId;
        final TargetSpec spec;
        final HookRecord record;
        final Set<MethodRegistration> methods = ConcurrentHashMap.newKeySet();
        volatile boolean failed;
        volatile String failReason;
        volatile boolean mirrorWarned;

        Registration(int regId, TargetSpec spec, HookRecord record) {
            this.regId = regId;
            this.spec = spec;
            this.record = record;
        }

        void markFailed(String reason) {
            failed = true;
            failReason = reason;
        }

        void clearFailed() {
            if (!methods.isEmpty()) {
                failed = false;
                failReason = null;
            }
        }
    }

    /** 织入计划（transformer 消费） */
    public static final class WeavePlan {
        public int id;
        public boolean needsEnter;
        public boolean needsArgs;
        public boolean needsExit;
    }

    /** 对外句柄实现 */
    private final class RegistryHandle implements HookHandle {
        private final Registration reg;

        RegistryHandle(Registration reg) {
            this.reg = reg;
        }

        @Override
        public HookStatus status() {
            if (reg.failed) {
                return HookStatus.FAILED;
            }
            for (MethodRegistration mr : reg.methods) {
                if (mr.wovenEnter || mr.wovenExit) {
                    return HookStatus.ACTIVE;
                }
            }
            return HookStatus.PENDING;
        }

        @Override
        public List<String> touchedClassNames() {
            Set<String> names = new LinkedHashSet<>();
            for (MethodRegistration mr : reg.methods) {
                names.add(mr.className);
            }
            return List.copyOf(names);
        }

        @Override
        public InstrumentShape shape() {
            boolean enter = false;
            boolean exit = false;
            for (MethodRegistration mr : reg.methods) {
                enter |= mr.wovenEnter;
                exit |= mr.wovenExit;
            }
            if (enter && exit) {
                return InstrumentShape.BOTH;
            }
            if (enter) {
                return InstrumentShape.ENTER;
            }
            if (exit) {
                return InstrumentShape.EXIT;
            }
            return InstrumentShape.NONE;
        }

        @Override
        public void unhook() {
            HookRegistry.this.unhook(reg.regId);
        }
    }
}
