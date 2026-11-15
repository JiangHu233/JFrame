package io.github.JiangHu.jframe.transform.scanfixture.good;

import io.github.JiangHu.jframe.transform.annotation.HookOn;
import io.github.JiangHu.jframe.transform.bridge.HookAt;
import io.github.JiangHu.jframe.transform.bridge.HookContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 合法注解切面宿主：HEAD / RETURN / REPLACE(static 签名镜像) 三形态共存于一类。
 */
public class GoodPatches {

    /** 事件记录（断言用） */
    public static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    /** HEAD：改参（before 手感） */
    @HookOn(target = "io.github.JiangHu.jframe.transform.scanfixture.ScanTargets",
            method = "add", at = HookAt.HEAD, needsArgs = true)
    public void addHead(HookContext ctx) {
        EVENTS.add("head:" + ctx.getArgs()[0]);
        ctx.setArg(1, 20);
    }

    /** RETURN：改返回值（after 手感） */
    @HookOn(target = "io.github.JiangHu.jframe.transform.scanfixture.ScanTargets",
            method = "greet", at = HookAt.RETURN)
    public void greetReturn(HookContext ctx) {
        ctx.setReturn(ctx.getReturn() + "!");
    }

    /** REPLACE 签名镜像：static 目标 → 无实例参（@Overwrite 手感） */
    @HookOn(target = "io.github.JiangHu.jframe.transform.scanfixture.ScanTargets",
            method = "stat", at = HookAt.REPLACE)
    public static int statMirror(int x) {
        EVENTS.add("replace:" + x);
        return x + 1000;
    }
}
