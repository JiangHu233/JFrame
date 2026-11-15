package io.github.JiangHu.jframe.transform.scanfixture.bad;

import io.github.JiangHu.jframe.transform.annotation.HookOn;
import io.github.JiangHu.jframe.transform.bridge.HookAt;
import io.github.JiangHu.jframe.transform.bridge.HookContext;

/**
 * 非法注解切面宿主（注册期 fail-fast 用例）。
 */
public class BadPatches {

    /** 镜像实例参类型与目标类不符 → 注册期 fail-fast */
    @HookOn(target = "io.github.JiangHu.jframe.transform.scanfixture.ScanTargets",
            method = "add", at = HookAt.REPLACE)
    public int wrongMirror(String self, int a, int b) {
        return 0;
    }

    /** HEAD 签名不符（非 (HookContext)→void）→ 注册期 fail-fast */
    @HookOn(target = "io.github.JiangHu.jframe.transform.scanfixture.ScanTargets",
            method = "greet", at = HookAt.HEAD)
    public void wrongSignature(String name) {
    }

    /** 无关方法（无注解，扫描应忽略） */
    public void notAnnotated(HookContext ctx) {
    }
}
