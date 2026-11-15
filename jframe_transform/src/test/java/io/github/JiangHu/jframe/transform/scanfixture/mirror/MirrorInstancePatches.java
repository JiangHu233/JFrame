package io.github.JiangHu.jframe.transform.scanfixture.mirror;

import io.github.JiangHu.jframe.transform.annotation.HookOn;
import io.github.JiangHu.jframe.transform.bridge.HookAt;
import io.github.JiangHu.jframe.transform.scanfixture.ScanTargets;

/**
 * 实例方法签名镜像宿主：首参 = 目标实例，同参同返回值。
 */
public class MirrorInstancePatches {

    /** 接管 ScanTargets#add：改为乘法 */
    @HookOn(target = "io.github.JiangHu.jframe.transform.scanfixture.ScanTargets",
            method = "add", at = HookAt.REPLACE)
    public int addMirror(ScanTargets self, int a, int b) {
        return a * b;
    }
}
