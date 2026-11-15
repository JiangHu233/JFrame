package io.github.JiangHu.jframe.transform.agent;

import java.lang.instrument.Instrumentation;

/**
 * -javaagent 预注入入口（可选可靠性增强路径，设计方案 §3.2）。
 * <p>
 * 运维在启动脚本加 {@code -javaagent:jframe_main.jar} 时，JVM 把本 jar 追加到
 * 系统类加载器搜索路径并调用 {@link #premain}——本类（系统类加载器副本）静态
 * 持有 {@link Instrumentation}，AgentInstaller 反射读取后跳过自附加，控制台无
 * JDK 21+ 动态附加警告。未配置时本类不被加载，走 ByteBuddyAgent 自附加。
 * <p>
 * manifest 属性（jframe_main 打包注入）：
 * {@code Premain-Class} / {@code Agent-Class} = 本类，{@code Can-Retransform-Classes: true}。
 */
public final class TransformAgent {

    /** 预注入持有的 Instrumentation（agentmain 备用：本 jar 被动态 attach 时也可复用） */
    public static volatile Instrumentation instrumentation;

    private TransformAgent() {
    }

    /**
     * -javaagent 预注入入口。
     *
     * @param args agent 参数（未使用）
     * @param inst Instrumentation
     */
    public static void premain(String args, Instrumentation inst) {
        if (instrumentation == null) {
            instrumentation = inst;
        }
    }

    /**
     * 动态 attach 入口（备用路径）。
     *
     * @param args agent 参数（未使用）
     * @param inst Instrumentation
     */
    public static void agentmain(String args, Instrumentation inst) {
        if (instrumentation == null) {
            instrumentation = inst;
        }
    }
}
