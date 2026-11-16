package io.github.JiangHu.jframe.transform.bytebuddy.advice;

import io.github.JiangHu.jframe.transform.bytebuddy.HookIdMarker;
import io.github.JiangHu.jframe.transform.bridge.Bridge;
import io.github.JiangHu.jframe.transform.bridge.State;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.OnNonDefaultValue;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * enter 带参数模板（元数 0..6，按方法参数个数选取）——组参数数组且<b>可写回</b>
 * （{@code @Advice.Argument(readOnly=false, typing=DYNAMIC)}：基本类型装箱读取、
 * 拆箱写回）。State.args 非 null 时逐槽写回（未改槽位写回原值无害）。
 * <p>
 * 返回 State 仅在短路时非 null（skipOn = 非 null 跳过原方法体，State 经
 * {@code @Advice.Enter} 传给 exit 写回短路返回值/强制抛出）；仅改参未短路时
 * 写回参数后返回 null，原方法体照常执行。
 * <p>
 * 高于 6 参的方法用 {@link EnterAdviceReadOnly}（只读，不写回）。
 */
@SuppressWarnings("unused")
public final class EnterAdviceArgs {

    private EnterAdviceArgs() {
    }

    /** 元数 0 */
    public static class A0 {
        @Advice.OnMethodEnter(skipOn = OnNonDefaultValue.class)
        static State enter(@HookIdMarker int id,
                           @Advice.This(optional = true) Object self) {
            State s = Bridge.enter(id, self, new Object[0]);
            return s != null && s.skip ? s : null;
        }
    }

    /** 元数 1 */
    public static class A1 {
        @Advice.OnMethodEnter(skipOn = OnNonDefaultValue.class)
        static State enter(@HookIdMarker int id,
                           @Advice.This(optional = true) Object self,
                           @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object a0) {
            State s = Bridge.enter(id, self, new Object[]{a0});
            if (s == null) {
                return null;
            }
            if (s.args != null) {
                a0 = s.args[0];
            }
            return s.skip ? s : null;
        }
    }

    /** 元数 2 */
    public static class A2 {
        @Advice.OnMethodEnter(skipOn = OnNonDefaultValue.class)
        static State enter(@HookIdMarker int id,
                           @Advice.This(optional = true) Object self,
                           @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object a0,
                           @Advice.Argument(value = 1, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object a1) {
            State s = Bridge.enter(id, self, new Object[]{a0, a1});
            if (s == null) {
                return null;
            }
            if (s.args != null) {
                a0 = s.args[0];
                a1 = s.args[1];
            }
            return s.skip ? s : null;
        }
    }

    /** 元数 3 */
    public static class A3 {
        @Advice.OnMethodEnter(skipOn = OnNonDefaultValue.class)
        static State enter(@HookIdMarker int id,
                           @Advice.This(optional = true) Object self,
                           @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object a0,
                           @Advice.Argument(value = 1, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object a1,
                           @Advice.Argument(value = 2, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object a2) {
            State s = Bridge.enter(id, self, new Object[]{a0, a1, a2});
            if (s == null) {
                return null;
            }
            if (s.args != null) {
                a0 = s.args[0];
                a1 = s.args[1];
                a2 = s.args[2];
            }
            return s.skip ? s : null;
        }
    }

    /** 元数 4 */
    public static class A4 {
        @Advice.OnMethodEnter(skipOn = OnNonDefaultValue.class)
        static State enter(@HookIdMarker int id,
                           @Advice.This(optional = true) Object self,
                           @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object a0,
                           @Advice.Argument(value = 1, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object a1,
                           @Advice.Argument(value = 2, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object a2,
                           @Advice.Argument(value = 3, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object a3) {
            State s = Bridge.enter(id, self, new Object[]{a0, a1, a2, a3});
            if (s == null) {
                return null;
            }
            if (s.args != null) {
                a0 = s.args[0];
                a1 = s.args[1];
                a2 = s.args[2];
                a3 = s.args[3];
            }
            return s.skip ? s : null;
        }
    }

    /** 元数 5 */
    public static class A5 {
        @Advice.OnMethodEnter(skipOn = OnNonDefaultValue.class)
        static State enter(@HookIdMarker int id,
                           @Advice.This(optional = true) Object self,
                           @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object a0,
                           @Advice.Argument(value = 1, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object a1,
                           @Advice.Argument(value = 2, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object a2,
                           @Advice.Argument(value = 3, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object a3,
                           @Advice.Argument(value = 4, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object a4) {
            State s = Bridge.enter(id, self, new Object[]{a0, a1, a2, a3, a4});
            if (s == null) {
                return null;
            }
            if (s.args != null) {
                a0 = s.args[0];
                a1 = s.args[1];
                a2 = s.args[2];
                a3 = s.args[3];
                a4 = s.args[4];
            }
            return s.skip ? s : null;
        }
    }

    /** 元数 6 */
    public static class A6 {
        @Advice.OnMethodEnter(skipOn = OnNonDefaultValue.class)
        static State enter(@HookIdMarker int id,
                           @Advice.This(optional = true) Object self,
                           @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object a0,
                           @Advice.Argument(value = 1, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object a1,
                           @Advice.Argument(value = 2, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object a2,
                           @Advice.Argument(value = 3, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object a3,
                           @Advice.Argument(value = 4, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object a4,
                           @Advice.Argument(value = 5, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object a5) {
            State s = Bridge.enter(id, self, new Object[]{a0, a1, a2, a3, a4, a5});
            if (s == null) {
                return null;
            }
            if (s.args != null) {
                a0 = s.args[0];
                a1 = s.args[1];
                a2 = s.args[2];
                a3 = s.args[3];
                a4 = s.args[4];
                a5 = s.args[5];
            }
            return s.skip ? s : null;
        }
    }
}
