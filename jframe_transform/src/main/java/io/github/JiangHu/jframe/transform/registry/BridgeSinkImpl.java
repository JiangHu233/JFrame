package io.github.JiangHu.jframe.transform.registry;

import io.github.JiangHu.jframe.transform.bridge.ExitState;
import io.github.JiangHu.jframe.transform.bridge.HookContext;
import io.github.JiangHu.jframe.transform.bridge.State;
import io.github.JiangHu.jframe.transform.bridge.Sink;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 分发回调实现（设计方案 §2 架构图 SinkImpl）——Bridge 内联 dispatch 的最终落点。
 * <ul>
 *   <li>enter：建 ctx → before 链（优先级序，skip 截断）/ REPLACE（最高优先级胜出）
 *       → State（skip/改参/强制抛出）</li>
 *   <li>exit：after 链（反序）→ setReturn/forceThrow → ExitState；短路路径写回
 *       skipWith 值（基本类型 null/类型不符 → 默认值 + 限流警告）</li>
 *   <li>回调异常一律捕获记录，本次调用按"未修改"继续（REPLACE 抛出除外——
 *       那是语义的一部分，直接传播给调用方）</li>
 * </ul>
 */
public final class BridgeSinkImpl implements Sink {

    private static final String TAG = "JFrame-Transform";

    private final HookRegistry registry;

    /**
     * 构造分发实现。
     *
     * @param registry 注册表
     */
    public BridgeSinkImpl(HookRegistry registry) {
        this.registry = registry;
    }

    @Override
    public State onEnter(int id, Object self, Object[] args) {
        MethodRegistration mr = registry.dispatchLookup(id);
        if (mr == null) {
            return null;
        }
        List<HookRecord> chain = mr.chain;
        if (chain.isEmpty()) {
            return null;
        }
        HookContext ctx = new HookContext(self, args,
                args != null && mr.paramCount <= io.github.JiangHu.jframe.transform.bytebuddy.TransformerInstaller.WRITE_BACK_MAX_ARITY,
                mr.className, mr.methodName);
        State st = new State();
        st.ctx = ctx;
        boolean engaged = false;

        for (HookRecord rec : chain) {
            if (rec.mirror != null || rec.replace != null) {
                engaged = true;
                invokeReplace(mr, rec, ctx, st);
                break;   // REPLACE 胜出，剩余钩子与原体全部跳过
            }
            if (rec.occupiesEnter && rec.hook != null) {
                engaged = true;
                try {
                    rec.hook.before(ctx);
                } catch (Throwable t) {
                    TLog.error("before 钩子异常（" + rec.pluginName + " → "
                            + mr.className + "#" + mr.methodName + "），本次按未修改继续", t);
                }
                if (ctx.internalSkip()) {
                    break;   // skip 截断剩余 before
                }
            }
        }

        if (!engaged) {
            return null;   // 链中无 enter 相位钩子（不应发生——相位省略），零副作用
        }

        Throwable forced = ctx.internalForceThrow();
        if (forced != null) {
            st.skip = true;
            st.throwable = forced;
            return st;
        }
        st.skip = ctx.internalSkip();
        if (st.skip) {
            st.retValue = ctx.internalSkipValue();
            st.args = null;   // 短路后参数无需写回
        } else if (args != null) {
            st.args = ctx.getArgs();   // 改参（含未改槽位的原值，写回无害）
        }
        return st;
    }

    /**
     * 执行 REPLACE（无类型 / 签名镜像）：结果即短路返回值；抛出即强制抛出。
     */
    private void invokeReplace(MethodRegistration mr, HookRecord rec, HookContext ctx, State st) {
        try {
            Object result;
            if (rec.mirror != null) {
                result = invokeMirror(mr, rec.mirror, ctx);
            } else {
                result = rec.replace.replace(ctx);
            }
            if (mr.voidReturn) {
                ctx.skip();
            } else {
                ctx.skipWith(result);
            }
        } catch (Throwable t) {
            ctx.forceThrow(t);
            st.throwable = t;
        }
    }

    /**
     * 签名镜像调用：[实例参?] + 目标参数 → 反射 invoke 插件镜像方法，装箱/拆箱由反射完成。
     */
    private Object invokeMirror(MethodRegistration mr, HookRecord.MirrorInfo mirror, HookContext ctx) {
        Method method = mirror.method;
        Object[] invokeArgs = new Object[method.getParameterCount()];
        Object[] targetArgs = ctx.getArgs();
        int offset = mirror.resolvedInstanceParam ? 1 : 0;
        if (offset == 1) {
            invokeArgs[0] = ctx.getInstance();
        }
        for (int i = 0; i < targetArgs.length && offset + i < invokeArgs.length; i++) {
            invokeArgs[offset + i] = targetArgs[i];
        }
        try {
            return method.invoke(mirror.host, invokeArgs);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("镜像 REPLACE 抛出受检异常: "
                    + mr.className + "#" + mr.methodName, cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("镜像 REPLACE 调用失败: "
                    + mr.className + "#" + mr.methodName, e);
        }
    }

    @Override
    public ExitState onExit(int id, Object self, Object[] args, State enter, Throwable thrown, Object ret) {
        MethodRegistration mr = registry.dispatchLookup(id);
        if (mr == null) {
            return null;
        }
        ExitState result = null;

        // 1) after 链（反序；短路路径读有效返回值 skipWith，而非未写回的默认槽位）
        HookContext ctx = enter != null && enter.ctx != null ? enter.ctx : null;
        if (mr.hasAfter) {
            if (ctx == null) {
                ctx = new HookContext(self, args, false, mr.className, mr.methodName);
            }
            Object effectiveRet = ret;
            if (enter != null && enter.skip && enter.throwable == null) {
                effectiveRet = normalize(mr, enter.retValue);
            }
            ctx.internalBindExit(effectiveRet,
                    thrown != null ? thrown : (enter != null ? enter.throwable : null));
            List<HookRecord> chain = mr.chain;
            for (int i = chain.size() - 1; i >= 0; i--) {
                HookRecord rec = chain.get(i);
                if (!rec.occupiesExit || rec.hook == null) {
                    continue;
                }
                try {
                    rec.hook.after(ctx);
                } catch (Throwable t) {
                    TLog.error("after 钩子异常（" + rec.pluginName + " → "
                            + mr.className + "#" + mr.methodName + "），本次按未修改继续", t);
                }
            }
            Throwable forced = ctx.internalForceThrow();
            if (forced != null) {
                result = new ExitState();
                result.throwable = forced;
                return result;
            }
            if (ctx.internalRetModified() && thrown == null && !mr.voidReturn) {
                result = new ExitState();
                result.ret = normalize(mr, ctx.internalNewReturn());
                result.retModified = true;
            }
        }

        // 2) 短路路径写回（enter 返回 State；after 链已 setReturn 的以后写优先，不覆盖）
        if (enter != null) {
            if (result == null) {
                result = new ExitState();
            }
            if (enter.throwable != null) {
                result.throwable = enter.throwable;
            } else if (!mr.voidReturn && !result.retModified) {
                result.ret = normalize(mr, enter.retValue);
                result.retModified = true;
            }
        }
        return result;
    }

    /**
     * 基本类型归一：null / 类型不符 → 声明类型默认值 + 限流警告（不炸服）。
     */
    private Object normalize(MethodRegistration mr, Object value) {
        if (!mr.primitiveReturn) {
            return value;
        }
        if (value instanceof Number number) {
            switch (mr.returnTypeName) {
                case "boolean":
                    break;   // Number 非 boolean，走默认值
                case "byte":
                    return number.byteValue();
                case "short":
                    return number.shortValue();
                case "int":
                    return number.intValue();
                case "long":
                    return number.longValue();
                case "float":
                    return number.floatValue();
                case "double":
                    return number.doubleValue();
                default:
                    break;
            }
        }
        if (value instanceof Boolean bool && "boolean".equals(mr.returnTypeName)) {
            return bool;
        }
        if (value instanceof Character ch && "char".equals(mr.returnTypeName)) {
            return ch;
        }
        warnDefault(mr, value);
        return defaultValue(mr.returnTypeName);
    }

    private static Object defaultValue(String typeName) {
        switch (typeName) {
            case "boolean":
                return Boolean.FALSE;
            case "byte":
                return (byte) 0;
            case "short":
                return (short) 0;
            case "int":
                return 0;
            case "long":
                return 0L;
            case "float":
                return 0f;
            case "double":
                return 0d;
            case "char":
                return (char) 0;
            default:
                return null;
        }
    }

    private void warnDefault(MethodRegistration mr, Object value) {
        if (mr.defaultWarnCount.incrementAndGet() <= 3) {
            TLog.warn("REPLACE/skipWith 返回值 " + (value == null ? "null" : value.getClass().getName())
                    + " 与目标 " + mr.className + "#" + mr.methodName + " 的返回类型 "
                    + mr.returnTypeName + " 不符，回退默认值（" + defaultValue(mr.returnTypeName) + "）");
        }
    }
}
