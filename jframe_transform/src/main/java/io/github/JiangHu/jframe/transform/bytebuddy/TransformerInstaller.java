package io.github.JiangHu.jframe.transform.bytebuddy;

import io.github.JiangHu.jframe.transform.bytebuddy.advice.EnterAdviceArgs;
import io.github.JiangHu.jframe.transform.bytebuddy.advice.EnterAdviceNoArgs;
import io.github.JiangHu.jframe.transform.bytebuddy.advice.EnterAdviceReadOnly;
import io.github.JiangHu.jframe.transform.bytebuddy.advice.ExitAdviceTemplates;
import io.github.JiangHu.jframe.transform.registry.HookRegistry;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

/**
 * AgentBuilder 装配（设计方案 §3.3）——单一 retransform-capable transformer，
 * 白名单式匹配（仅当前目标类），每方法按织入计划（相位省略 + 模板矩阵）应用 Advice。
 * <p>
 * 生命周期：{@link #install} 注册 transformer；后续激活/重织/还原统一由
 * {@code instrumentation.retransformClasses} 驱动（JVM 对原始字节码重放全部
 * transformer；类不再命中 matcher 即自然还原，无需增删 transformer）。
 */
public final class TransformerInstaller {

    /** 参数写回模板的元数上限（超过走只读模板） */
    public static final int WRITE_BACK_MAX_ARITY = 6;

    private TransformerInstaller() {
    }

    /**
     * 安装 transformer（幂等由调用方 HookRegistry 保证）。
     *
     * @param registry 注册表
     * @param inst     Instrumentation
     * @return 已注册的 transformer（关服移除用）
     */
    public static ClassFileTransformer install(HookRegistry registry, Instrumentation inst) {
        return new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new FailReportListener(registry))
                .disableClassFormatChanges()
                .type(new TargetClassMatcher(registry))
                .transform(new HookAdviceTransformer(registry))
                .installOn(inst);
    }

    /**
     * enter 模板选取：免参数 / 元数 0..6 可写回 / 高元只读。
     */
    static Class<?> enterTemplate(int arity, boolean needsArgs) {
        if (!needsArgs) {
            return EnterAdviceNoArgs.class;
        }
        if (arity <= WRITE_BACK_MAX_ARITY) {
            switch (arity) {
                case 0:
                    return EnterAdviceArgs.A0.class;
                case 1:
                    return EnterAdviceArgs.A1.class;
                case 2:
                    return EnterAdviceArgs.A2.class;
                case 3:
                    return EnterAdviceArgs.A3.class;
                case 4:
                    return EnterAdviceArgs.A4.class;
                case 5:
                    return EnterAdviceArgs.A5.class;
                default:
                    return EnterAdviceArgs.A6.class;
            }
        }
        return EnterAdviceReadOnly.class;
    }

    /**
     * exit 模板选取：void/非 void × 是否伴随 enter（@Advice.Enter 可用性）。
     */
    static Class<?> exitTemplate(boolean voidReturn, boolean enterApplied) {
        if (voidReturn) {
            return enterApplied ? ExitAdviceTemplates.VoidEnter.class : ExitAdviceTemplates.VoidSolo.class;
        }
        return enterApplied ? ExitAdviceTemplates.RetEnter.class : ExitAdviceTemplates.RetSolo.class;
    }

    /**
     * 目标类 matcher——实时读注册表（白名单：仅当前有规格命中的类；JDK 前缀由
     * 注册表拒绝；卸载后类不再命中 → retransform 即还原）。
     */
    static final class TargetClassMatcher implements ElementMatcher<TypeDescription> {
        private final HookRegistry registry;

        TargetClassMatcher(HookRegistry registry) {
            this.registry = registry;
        }

        @Override
        public boolean matches(TypeDescription target) {
            return registry.isTargetClass(target.getName());
        }
    }

    /**
     * 逐方法 Advice 装配 transformer。
     */
    static final class HookAdviceTransformer implements AgentBuilder.Transformer {
        private final HookRegistry registry;

        HookAdviceTransformer(HookRegistry registry) {
            this.registry = registry;
        }

        @Override
        public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
                                                TypeDescription type,
                                                ClassLoader classLoader,
                                                net.bytebuddy.utility.JavaModule module,
                                                ProtectionDomain protectionDomain) {
            if (classLoader == null) {
                return builder;   // bootstrap 加载器：bridge 不可见，跳过
            }
            AsmVisitorWrapper.ForDeclaredMethods visitors = new AsmVisitorWrapper.ForDeclaredMethods();
            boolean any = false;
            for (MethodDescription md : type.getDeclaredMethods()) {
                if (md.isConstructor() || md.isTypeInitializer()
                        || md.isAbstract() || md.isNative()) {
                    continue;
                }
                List<String> paramNames = new ArrayList<>(md.getParameters().size());
                for (TypeDescription p : md.getParameters().asTypeList().asErasures()) {
                    paramNames.add(p.getName());
                }
                String returnName = md.getReturnType().asErasure().getName();
                HookRegistry.WeavePlan plan = registry.weavePlan(
                        type.getName(), md.getName(), md.getDescriptor(),
                        paramNames, md.isStatic(), returnName);
                if (plan == null) {
                    continue;
                }
                ElementMatcher.Junction<MethodDescription> method = ElementMatchers
                        .named(md.getName())
                        .and(ElementMatchers.hasDescriptor(md.getDescriptor()));
                // enter/exit 必须位于同一 Advice 实例（@Advice.Enter 链路要求），
                // 双类重载 to(enter, exit) 支持两相位分属不同模板类
                Class<?> enterT = plan.needsEnter
                        ? enterTemplate(paramNames.size(), plan.needsArgs) : null;
                Class<?> exitT = plan.needsExit
                        ? exitTemplate("void".equals(returnName), plan.needsEnter) : null;
                Advice advice;
                if (enterT != null && exitT != null) {
                    advice = Advice.withCustomMapping().bind(HookIdMarker.class, plan.id).to(enterT, exitT);
                } else if (enterT != null) {
                    advice = Advice.withCustomMapping().bind(HookIdMarker.class, plan.id).to(enterT);
                } else {
                    advice = Advice.withCustomMapping().bind(HookIdMarker.class, plan.id).to(exitT);
                }
                visitors = visitors.method(method, advice);
                any = true;
            }
            return any ? builder.visit(visitors) : builder;
        }
    }

    /**
     * 失败上报监听器——逐类错误标记注册 FAILED，不中断其他类。
     */
    static final class FailReportListener implements AgentBuilder.Listener {
        private final HookRegistry registry;

        FailReportListener(HookRegistry registry) {
            this.registry = registry;
        }

        @Override
        public void onDiscovery(String typeName, ClassLoader classLoader,
                                net.bytebuddy.utility.JavaModule module, boolean loaded) {
        }

        @Override
        public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                     net.bytebuddy.utility.JavaModule module, boolean loaded,
                                     DynamicType dynamicType) {
        }

        @Override
        public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader,
                              net.bytebuddy.utility.JavaModule module, boolean loaded) {
        }

        @Override
        public void onError(String typeName, ClassLoader classLoader,
                            net.bytebuddy.utility.JavaModule module, boolean loaded,
                            Throwable throwable) {
            registry.markClassFailed(typeName, throwable);
        }

        @Override
        public void onComplete(String typeName, ClassLoader classLoader,
                               net.bytebuddy.utility.JavaModule module, boolean loaded) {
        }
    }
}
