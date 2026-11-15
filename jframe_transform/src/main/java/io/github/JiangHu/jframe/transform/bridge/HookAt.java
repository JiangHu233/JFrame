package io.github.JiangHu.jframe.transform.bridge;

/**
 * 注解声明层的相位（bridge 包）。
 * <ul>
 *   <li>{@link #HEAD} — before 回调（≈ Mixin {@code @At("HEAD")}）</li>
 *   <li>{@link #RETURN} — after 回调（≈ Mixin {@code @At("RETURN")}）</li>
 *   <li>{@link #REPLACE} — 整方法接管（≈ Mixin {@code @Overwrite}）：
 *       注解方法签名与目标完全一致（同参同返回值，首参为目标实例，static 目标无实例参）</li>
 * </ul>
 * 一个方法要同时挂 HEAD 与 RETURN，写两个注解方法即可（无 BOTH）。
 */
public enum HookAt {
    HEAD,
    RETURN,
    REPLACE
}
