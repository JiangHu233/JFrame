package io.github.JiangHu.jframe.transform.scanfixture.empty;

import io.github.JiangHu.jframe.transform.annotation.HookOn;
import io.github.JiangHu.jframe.transform.bridge.HookAt;
import io.github.JiangHu.jframe.transform.bridge.HookContext;

/**
 * 空方法体注解切面宿主：空方法 = 空相位（跳过），同类其余方法正常注册。
 */
public class EmptyPatches {

    /** 空方法体 → 空相位 → 跳过（警告） */
    @HookOn(target = "io.github.JiangHu.jframe.transform.scanfixture.ScanTargets",
            method = "greet", at = HookAt.RETURN)
    public void emptyReturn(HookContext ctx) {
    }

    /** 真实方法体 → 正常注册 */
    @HookOn(target = "io.github.JiangHu.jframe.transform.scanfixture.ScanTargets",
            method = "greet", at = HookAt.RETURN)
    public void realReturn(HookContext ctx) {
        ctx.setReturn(ctx.getReturn() + "~");
    }
}
