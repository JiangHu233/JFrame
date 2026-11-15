package io.github.JiangHu.jframe.transform;

import io.github.JiangHu.jframe.transform.scanfixture.ScanTargets;
import io.github.JiangHu.jframe.transform.scanfixture.good.GoodPatches;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AOP 注解声明层测试（设计方案 §6 用例 8/13/14 注解形态部分）。
 * <p>
 * 宿主类无需接口；一类多钩子；static 注解方法免实例化；签名校验注册期 fail-fast；
 * 空方法体 = 空相位跳过；REPLACE 签名镜像（static 无实例参 / 实例含实例参）。
 */
class AnnotationScanTest {

    @AfterEach
    void cleanup() {
        TransformTestBase.scope().unhookAll();
        GoodPatches.EVENTS.clear();
    }

    @Test
    void scanRegistersHeadReturnAndStaticMirror() {
        TransformTestBase.scope().scan("io.github.JiangHu.jframe.transform.scanfixture.good");
        try {
            ScanTargets target = new ScanTargets();
            assertEquals(21, target.add(1, 1));   // HEAD 改参 b=20 → 1+20
            assertEquals("hi j!", target.greet("j"));   // RETURN 加叹号
            assertEquals(1003, ScanTargets.stat(3));   // REPLACE 镜像 3+1000
            assertTrue(GoodPatches.EVENTS.contains("head:1"));
            assertTrue(GoodPatches.EVENTS.contains("replace:3"));
        } finally {
            TransformTestBase.scope().unhookAll();
        }
        // 还原
        assertEquals(2, new ScanTargets().add(1, 1));
        assertEquals("hi j", new ScanTargets().greet("j"));
        assertEquals(6, ScanTargets.stat(3));
    }

    @Test
    void instanceMethodMirror() {
        TransformTestBase.scope().scan("io.github.JiangHu.jframe.transform.scanfixture.mirror");
        try {
            assertEquals(6, new ScanTargets().add(2, 3));   // 乘法接管
        } finally {
            TransformTestBase.scope().unhookAll();
        }
        assertEquals(5, new ScanTargets().add(2, 3));
    }

    @Test
    void badSignaturesRejectedFailFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TransformTestBase.scope().scan("io.github.JiangHu.jframe.transform.scanfixture.bad"));
        assertTrue(ex.getMessage() != null && !ex.getMessage().isEmpty());
        // 非法包不产生任何织入
        assertEquals(2, new ScanTargets().add(1, 1));
        assertEquals("hi j", new ScanTargets().greet("j"));
    }

    @Test
    void emptyAnnotatedMethodSkippedOthersRegistered() {
        assertDoesNotThrow(() ->
                TransformTestBase.scope().scan("io.github.JiangHu.jframe.transform.scanfixture.empty"));
        try {
            assertEquals("hi j~", new ScanTargets().greet("j"));   // realReturn 生效
        } finally {
            TransformTestBase.scope().unhookAll();
        }
        assertEquals("hi j", new ScanTargets().greet("j"));
    }
}
