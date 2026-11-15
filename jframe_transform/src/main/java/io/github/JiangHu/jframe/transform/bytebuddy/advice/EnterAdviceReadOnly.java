package io.github.JiangHu.jframe.transform.bytebuddy.advice;

import io.github.JiangHu.jframe.transform.bytebuddy.HookIdMarker;
import io.github.JiangHu.jframe.transform.bridge.Bridge;
import io.github.JiangHu.jframe.transform.bridge.State;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.OnNonDefaultValue;

/**
 * enter 只读参数模板（参数 &gt; 6 个的高元方法）：组参数数组（可读）但<b>不写回</b>
 * （ctx.setArg 运行时报错）。常规元数方法请用 {@code EnterAdviceArgs.A0..A6}（可写回）。
 */
@SuppressWarnings("unused")
public final class EnterAdviceReadOnly {

    @Advice.OnMethodEnter(skipOn = OnNonDefaultValue.class)
    static State enter(
            @HookIdMarker int id,
            @Advice.This(optional = true) Object self,
            @Advice.AllArguments Object[] args) {
        State s = Bridge.enter(id, self, args);
        return s != null && s.skip ? s : null;
    }

    private EnterAdviceReadOnly() {
    }
}
