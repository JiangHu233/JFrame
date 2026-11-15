package io.github.JiangHu.jframe.transform.registry;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 类刷新驱动——注册/卸载后对已加载类下发 retransform（设计方案 §3.3 形状协调）。
 * <ul>
 *   <li><b>激活</b>：已加载且匹配规格但未织入的类 → retransform（matcher 命中即织入）</li>
 *   <li><b>重织</b>：形状跃迁的已织入类 → retransform 换模板</li>
 *   <li><b>还原</b>：链空的已织入类 → retransform（matcher 不再命中 → 原始字节码）</li>
 * </ul>
 * 批量失败降级为逐类执行，单类失败标记注册 FAILED 不影响其余。
 */
final class RefreshDriver {

    private final HookRegistry registry;
    private final Instrumentation instrumentation;

    RefreshDriver(HookRegistry registry, Instrumentation instrumentation) {
        this.registry = registry;
        this.instrumentation = instrumentation;
    }

    /**
     * 刷新：脏类（重织/还原）+ 新规格激活（未织入类 / 已织入类的新方法）。
     *
     * @param dirty           脏类名
     * @param newSpec         本次新增规格（激活判定；卸载传 null）
     * @param retransformCount 计数探针
     */
    void refresh(Set<String> dirty, TargetSpec newSpec, AtomicInteger retransformCount) {
        if ((dirty == null || dirty.isEmpty()) && newSpec == null) {
            return;
        }
        List<Class<?>> targets = new ArrayList<>();
        Class<?>[] loaded = instrumentation.getAllLoadedClasses();
        for (Class<?> cls : loaded) {
            String name = cls.getName();
            if (name.startsWith("[") || cls.isPrimitive() || HookRegistry.isJdk(name)) {
                continue;
            }
            boolean isDirty = dirty != null && dirty.contains(name);
            boolean needsWeave = registry.needsActivationWeave(newSpec, name);
            if (isDirty || needsWeave) {
                targets.add(cls);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        retransformAll(targets, retransformCount);
    }

    /**
     * 关服全量还原（先移除 transformer，retransform 后无人接管 → 原始字节码）。
     */
    void restoreAll(Set<String> classNames, ClassFileTransformer transformer) {
        if (classNames.isEmpty()) {
            return;
        }
        try {
            instrumentation.removeTransformer(transformer);
        } catch (Throwable t) {
            TLog.error("移除 transformer 失败", t);
        }
        List<Class<?>> targets = new ArrayList<>();
        for (Class<?> cls : instrumentation.getAllLoadedClasses()) {
            if (classNames.contains(cls.getName())) {
                targets.add(cls);
            }
        }
        if (!targets.isEmpty()) {
            try {
                instrumentation.retransformClasses(targets.toArray(new Class[0]));
            } catch (Throwable t) {
                TLog.error("关服还原 retransform 失败（" + targets.size() + " 类）", t);
            }
        }
    }

    private void retransformAll(List<Class<?>> targets, AtomicInteger counter) {
        try {
            instrumentation.retransformClasses(targets.toArray(new Class[0]));
            counter.addAndGet(targets.size());
            return;
        } catch (Throwable batchFail) {
            // 批量原子失败 → 逐类隔离
        }
        for (Class<?> cls : targets) {
            try {
                instrumentation.retransformClasses(cls);
                counter.incrementAndGet();
            } catch (Throwable t) {
                registry.markClassFailed(cls.getName(), t);
            }
        }
    }
}
