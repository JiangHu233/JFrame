package io.github.JiangHu.jframe.example.transform;

import io.github.JiangHu.jframe.transform.annotation.HookOn;
import io.github.JiangHu.jframe.transform.bridge.HookAt;
import io.github.JiangHu.jframe.transform.bridge.HookContext;

/**
 * 动态类增强<b>注解声明式演示宿主</b>——普通类，无需实现任何接口；
 * {@code @HookOn} 标注在方法上，{@code /jtransform on} 扫描注册后生效。
 *
 * @see TransformDemoController
 */
public class TransformPatches {

    /**
     * RETURN 相位（after 手感 ≈ Mixin {@code @At("RETURN")}）：
     * 给服务器列表 Motd 加前缀——原方法体照常执行，出口改返回值。
     */
    @HookOn(target = "cn.nukkit.Server", method = "getMotd", at = HookAt.RETURN)
    public void motdPrefixHook(HookContext ctx) {
        ctx.setReturn("[JFrame 增强] " + ctx.getReturn());
    }
}
