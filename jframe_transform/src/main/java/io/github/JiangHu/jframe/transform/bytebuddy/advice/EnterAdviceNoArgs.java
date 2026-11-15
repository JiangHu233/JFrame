package io.github.JiangHu.jframe.transform.bytebuddy.advice;

import io.github.JiangHu.jframe.transform.bytebuddy.HookIdMarker;
import io.github.JiangHu.jframe.transform.bridge.Bridge;
import io.github.JiangHu.jframe.transform.bridge.State;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.OnNonDefaultValue;

/**
 * enter 模板（免参数形态，任意参数个数方法通用）——needsArgs=false 时不组参数数组，
 * 免装箱。返回 State 非 null 即短路（skip 语义），null 则原方法体执行。
 */
@SuppressWarnings("unused")
public final class EnterAdviceNoArgs {

    @Advice.OnMethodEnter(skipOn = OnNonDefaultValue.class)
    static State enter(
            @HookIdMarker int id,
            @Advice.This(optional = true) Object self) {
        State s = Bridge.enter(id, self);
        return s != null && s.skip ? s : null;
    }

    private EnterAdviceNoArgs() {
    }
}
