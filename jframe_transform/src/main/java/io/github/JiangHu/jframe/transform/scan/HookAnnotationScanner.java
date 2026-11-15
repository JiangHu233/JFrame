package io.github.JiangHu.jframe.transform.scan;

import io.github.JiangHu.jframe.transform.HookHandle;
import io.github.JiangHu.jframe.transform.HookPriority;
import io.github.JiangHu.jframe.transform.annotation.HookOn;
import io.github.JiangHu.jframe.transform.bridge.HookAt;
import io.github.JiangHu.jframe.transform.bridge.HookContext;
import io.github.JiangHu.jframe.transform.bridge.MethodHook;
import io.github.JiangHu.jframe.transform.registry.HookRecord;
import io.github.JiangHu.jframe.transform.registry.HookRegistry;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code @HookOn} 注解方法扫描器（AOP 声明式层，设计方案 §3.4.5）。
 * <p>
 * 枚举基础包下的类 → 反射查 {@code @HookOn} 方法 → 校验签名 / 空方法体 →
 * 适配为内部 {@code MethodHook}（HEAD/RETURN）或签名镜像（REPLACE）→ 走命令式
 * 注册路径（字节码机制与接口形态完全同一套）。宿主类按需实例化一次（无参构造
 * fail-fast）；static 注解方法免实例化。
 */
public final class HookAnnotationScanner {

    private final HookRegistry registry;

    /**
     * 构造扫描器。
     *
     * @param registry 注册表
     */
    public HookAnnotationScanner(HookRegistry registry) {
        this.registry = registry;
    }

    /**
     * 扫描并注册。
     *
     * @param pluginLoader 插件类加载器（扫插件 jar）
     * @param pluginName   归属插件名
     * @param basePackage  基础包（含子包）
     */
    public void scan(ClassLoader pluginLoader, String pluginName, String basePackage) {
        Set<Class<?>> classes = PackageClassWalker.walk(pluginLoader, basePackage);
        for (Class<?> cls : classes) {
            List<Method> annotated = new ArrayList<>();
            for (Method m : cls.getDeclaredMethods()) {
                if (m.isAnnotationPresent(HookOn.class)) {
                    annotated.add(m);
                }
            }
            if (annotated.isEmpty()) {
                continue;
            }
            Object host = null;
            for (Method m : annotated) {
                if (!Modifier.isStatic(m.getModifiers())) {
                    host = instantiate(cls);
                    break;
                }
            }
            for (Method m : annotated) {
                registerAnnotatedMethod(cls, m, m.getAnnotation(HookOn.class), host, pluginName);
            }
        }
    }

    /**
     * 宿主类实例化（无参构造 fail-fast，同 @JBean 习惯）。
     */
    private Object instantiate(Class<?> cls) {
        try {
            java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("宿主类缺少可用无参构造（含非 static @HookOn 方法的类必须可实例化）: "
                    + cls.getName(), e);
        }
    }

    /**
     * 注册单个注解方法：按 at 校验签名并适配。
     */
    private void registerAnnotatedMethod(Class<?> cls, Method m, HookOn anno, Object host, String pluginName) {
        String where = cls.getName() + "#" + m.getName();
        HookAt at = anno.at();

        if (at == HookAt.REPLACE) {
            registerMirror(cls, m, anno, host, pluginName, where);
            return;
        }

        // HEAD / RETURN：必须 (HookContext) → void
        Class<?>[] paramTypes = m.getParameterTypes();
        if (paramTypes.length != 1 || paramTypes[0] != HookContext.class || m.getReturnType() != void.class) {
            throw new IllegalArgumentException("@" + at + " 注解方法签名必须为 (HookContext) → void: " + where);
        }
        // 空方法体 = 空相位（相位省略）：警告并跳过该方法，不影响同类其他注解方法
        if (registry.isPhaseBodyEmpty(cls, m.getName())) {
            registry.warnEmptyAnnotatedMethod(where);
            return;
        }

        List<String> targetParams = paramNames(anno);
        MethodHook adapter = at == HookAt.HEAD
                ? new EnterAdapter(m, host)
                : new ExitAdapter(m, host);
        registry.register(pluginName, anno.target(), anno.method(), targetParams,
                adapter, null, null, anno.priority(), anno.needsArgs());
    }

    /**
     * REPLACE：签名镜像注册（目标/方法不允许通配）。
     */
    private void registerMirror(Class<?> cls, Method m, HookOn anno, Object host,
                                String pluginName, String where) {
        if (anno.target().contains("*") || anno.method().contains("*")) {
            throw new IllegalArgumentException("at = REPLACE 不支持通配（签名镜像要求精确目标）: " + where);
        }
        if (m.getReturnType() == void.class) {
            throw new IllegalArgumentException("at = REPLACE 方法必须返回与目标一致的类型（void 不合法）: " + where);
        }
        List<String> mirrorParams = new ArrayList<>(m.getParameterCount());
        for (Class<?> p : m.getParameterTypes()) {
            mirrorParams.add(p.getName());
        }
        String mirrorReturn = m.getReturnType().getName();
        HookRecord.MirrorInfo mirror = new HookRecord.MirrorInfo(m, host, mirrorParams, mirrorReturn);
        try {
            m.setAccessible(true);
        } catch (Exception ignored) {
            // 部分环境可能拒绝，invoke 时再报
        }

        // 目标已加载则注册期即时校验（fail-fast 打印双方签名）；未加载推迟到织入
        eagerValidateMirror(anno, mirror, pluginLoaderOrDefault(pluginName));

        List<String> targetParams = paramNames(anno);
        registry.register(pluginName, anno.target(), anno.method(), targetParams,
                null, null, mirror, anno.priority(), true);
    }

    /**
     * 目标类可解析时的注册期签名预检（不可解析则静默推迟到织入期）。
     */
    private void eagerValidateMirror(HookOn anno, HookRecord.MirrorInfo mirror, ClassLoader pluginLoader) {
        Class<?> target;
        try {
            target = Class.forName(anno.target(), false, ClassLoader.getSystemClassLoader());
        } catch (ClassNotFoundException tryPlugin) {
            try {
                target = Class.forName(anno.target(), false, pluginLoader);
            } catch (ClassNotFoundException defer) {
                return;   // 未加载：织入期校验
            }
        }
        List<String> mirrorParams = mirror.paramTypeNames;
        String mirrorReturn = mirror.returnTypeName;
        String expected = null;
        boolean found = false;
        for (Method tm : target.getDeclaredMethods()) {
            if (!tm.getName().equals(anno.method())) {
                continue;
            }
            List<String> tp = new ArrayList<>(tm.getParameterCount());
            for (Class<?> p : tm.getParameterTypes()) {
                tp.add(p.getName());
            }
            String tr = tm.getReturnType().getName();
            expected = tp + " → " + tr;
            boolean instanceTarget = !Modifier.isStatic(tm.getModifiers());
            List<String> compare = mirrorParams;
            if (instanceTarget) {
                if (mirrorParams.size() != tp.size() + 1
                        || !mirrorParams.get(0).equals(anno.target())) {
                    continue;
                }
                compare = mirrorParams.subList(1, mirrorParams.size());
            } else if (mirrorParams.size() != tp.size()) {
                continue;
            }
            if (compare.equals(tp) && mirrorReturn.equals(tr)) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IllegalArgumentException("REPLACE 签名镜像不匹配: 目标 " + anno.target() + "#"
                    + anno.method() + " 期望 " + (expected == null ? "?（未找到同名方法）" : expected)
                    + "，镜像 " + mirrorParams + " → " + mirrorReturn);
        }
    }

    private ClassLoader pluginLoaderOrDefault(String pluginName) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : getClass().getClassLoader();
    }

    private static List<String> paramNames(HookOn anno) {
        Class<?>[] params = anno.params();
        if (params.length == 0) {
            return null;
        }
        List<String> names = new ArrayList<>(params.length);
        for (Class<?> p : params) {
            names.add(p.getName());
        }
        return names;
    }

    // ==================== 适配器（只 override 活跃相位，另一相位保持 default 空 → 相位省略自然生效） ====================

    /** HEAD 相位适配器 */
    static final class EnterAdapter implements MethodHook {
        private final Method method;
        private final Object host;

        EnterAdapter(Method method, Object host) {
            this.method = method;
            this.host = host;
        }

        @Override
        public void before(HookContext ctx) {
            try {
                method.invoke(host, ctx);
            } catch (InvocationTargetException e) {
                rethrow(e);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("HEAD 适配器调用失败: " + method, e);
            }
        }
    }

    /** RETURN 相位适配器 */
    static final class ExitAdapter implements MethodHook {
        private final Method method;
        private final Object host;

        ExitAdapter(Method method, Object host) {
            this.method = method;
            this.host = host;
        }

        @Override
        public void after(HookContext ctx) {
            try {
                method.invoke(host, ctx);
            } catch (InvocationTargetException e) {
                rethrow(e);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("RETURN 适配器调用失败: " + method, e);
            }
        }
    }

    private static void rethrow(InvocationTargetException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new IllegalStateException("注解钩子抛出异常", cause);
    }
}
