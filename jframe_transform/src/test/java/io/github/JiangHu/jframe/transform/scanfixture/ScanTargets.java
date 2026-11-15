package io.github.JiangHu.jframe.transform.scanfixture;

/**
 * 注解扫描测试目标类。
 */
public class ScanTargets {

    /** 加法（实例） */
    public int add(int a, int b) {
        return a + b;
    }

    /** 问候（实例） */
    public String greet(String name) {
        return "hi " + name;
    }

    /** 翻倍（静态） */
    public static int stat(int x) {
        return x * 2;
    }
}
