package io.github.JiangHu.jframe.transform.bytebuddy.advice;

import io.github.JiangHu.jframe.transform.bytebuddy.HookIdMarker;
import io.github.JiangHu.jframe.transform.bridge.Bridge;
import io.github.JiangHu.jframe.transform.bridge.ExitState;
import io.github.JiangHu.jframe.transform.bridge.State;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * exit 模板（方法体尾部，onThrowable 捕获异常；原异常未处理时自动重抛）。
 * <ul>
 *   <li>{@code ExitVoidEnter} — void 方法 + enter 相位已织入（@Advice.Enter 可用）</li>
 *   <li>{@code ExitVoidSolo} — void 方法 + 仅 after（无 enter 相位，无 @Advice.Enter）</li>
 *   <li>{@code ExitRetEnter} — 非 void + enter（返回值可写回：setReturn / skipWith）</li>
 *   <li>{@code ExitRetSolo} — 非 void + 仅 after</li>
 * </ul>
 * ExitState.throwable 非 null → 裸 throw 覆盖（forceThrow / REPLACE 抛出）。
 */
@SuppressWarnings("unused")
public final class ExitAdviceTemplates {

    private ExitAdviceTemplates() {
    }

    /** void + enter */
    public static class VoidEnter {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(@HookIdMarker int id,
                         @Advice.Enter State enter,
                         @Advice.This(optional = true) Object self,
                         @Advice.AllArguments Object[] args,
                         @Advice.Thrown Throwable thrown) throws Throwable {
            ExitState r = Bridge.exit(id, self, args, enter, thrown, null);
            if (r != null && r.throwable != null) {
                throw r.throwable;
            }
        }
    }

    /** void + 仅 after */
    public static class VoidSolo {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(@HookIdMarker int id,
                         @Advice.This(optional = true) Object self,
                         @Advice.AllArguments Object[] args,
                         @Advice.Thrown Throwable thrown) throws Throwable {
            ExitState r = Bridge.exit(id, self, args, null, thrown, null);
            if (r != null && r.throwable != null) {
                throw r.throwable;
            }
        }
    }

    /** 非 void + enter */
    public static class RetEnter {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(@HookIdMarker int id,
                         @Advice.Enter State enter,
                         @Advice.This(optional = true) Object self,
                         @Advice.AllArguments Object[] args,
                         @Advice.Thrown Throwable thrown,
                         @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object ret) throws Throwable {
            ExitState r = Bridge.exit(id, self, args, enter, thrown, ret);
            if (r == null) {
                return;
            }
            if (r.throwable != null) {
                throw r.throwable;
            }
            if (r.retModified) {
                ret = r.ret;
            }
        }
    }

    /** 非 void + 仅 after */
    public static class RetSolo {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(@HookIdMarker int id,
                         @Advice.This(optional = true) Object self,
                         @Advice.AllArguments Object[] args,
                         @Advice.Thrown Throwable thrown,
                         @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object ret) throws Throwable {
            ExitState r = Bridge.exit(id, self, args, null, thrown, ret);
            if (r == null) {
                return;
            }
            if (r.throwable != null) {
                throw r.throwable;
            }
            if (r.retModified) {
                ret = r.ret;
            }
        }
    }
}
