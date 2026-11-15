package io.github.JiangHu.jframe.transform.agent;

import io.github.JiangHu.jframe.transform.TransformException;
import net.bytebuddy.agent.ByteBuddyAgent;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Instrumentation 获取器（设计方案 §3.2，双检锁幂等）。
 * <p>
 * 优先级：①运维以 {@code -javaagent} 预注入（TransformAgent 静态持有，无警告、
 * 最稳）；②{@link ByteBuddyAgent#install()} 运行中自附加（服务器零启动参数——
 * 自附加被拒时 ByteBuddy 自动走外部进程 attach 兜底；JDK 21+ 控制台会打一条
 * 动态附加警告，属预期、无害）。
 * <p>
 * 成功后立即注入 bridge jar（系统类加载器唯一副本，见 {@link BridgeJarInstaller}）。
 */
public final class AgentInstaller {

    /** 全局唯一安装器状态（同一 JVM / 同一插件类加载器内共享） */
    private static final ReentrantLock LOCK = new ReentrantLock();

    private static volatile Instrumentation instrumentation;
    private static volatile AttachSource source = AttachSource.NONE;
    private static volatile TransformException failure;

    /** bridge jar 落盘目录（宿主插件数据目录；由 TransformModule 注入，测试可指临时目录） */
    private static volatile Path dataDir;

    private AgentInstaller() {
    }

    /**
     * 指定 bridge jar 落盘目录（只读文件系统时调用方可换临时目录）。
     *
     * @param dir 目录（父目录即可，内部再建 transform/ 子目录）
     */
    public static void configureDataDir(Path dir) {
        if (dir != null) {
            dataDir = dir;
        }
    }

    /**
     * 确保 Instrumentation 与 bridge jar 就绪（幂等；失败会记住原因重复抛出）。
     *
     * @return Instrumentation
     * @throws TransformException 自附加失败（消息含诊断建议）
     */
    public static Instrumentation ensureInstalled() {
        if (instrumentation != null) {
            return instrumentation;
        }
        if (failure != null) {
            throw failure;
        }
        LOCK.lock();
        try {
            if (instrumentation != null) {
                return instrumentation;
            }
            if (failure != null) {
                throw failure;
            }

            // ① -javaagent 预注入
            Instrumentation pre = BridgeJarInstaller.readPremainInstrumentation();
            if (pre != null) {
                finishInstall(pre, AttachSource.PREMAIN);
                return pre;
            }

            // ② 运行中自附加（ByteBuddyAgent：先直接自附加，被拒则外部进程兜底）
            try {
                Instrumentation self = ByteBuddyAgent.install();
                finishInstall(self, AttachSource.SELF_ATTACH);
                return self;
            } catch (Throwable t) {
                failure = new TransformException(diagnose(t), t);
                throw failure;
            }
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * 安装 bridge jar 并记录来源。
     */
    private static void finishInstall(Instrumentation inst, AttachSource src) {
        Path dir = dataDir;
        if (dir == null) {
            dir = fallbackDir();
        }
        try {
            new BridgeJarInstaller(AgentInstaller.class.getClassLoader())
                    .install(inst, dir);
        } catch (RuntimeException e) {
            failure = new TransformException("bridge jar 安装失败: " + e.getMessage(), e);
            throw failure;
        }
        instrumentation = inst;
        source = src;
    }

    /**
     * 未配置目录时的降级落盘目录（临时目录）。
     */
    private static Path fallbackDir() {
        try {
            Path tmp = Files.createTempDirectory("jframe-transform-bridge");
            return tmp;
        } catch (Exception e) {
            throw new TransformException("无法创建 bridge jar 落盘目录", e);
        }
    }

    /**
     * agent 是否就绪（不触发安装）。
     *
     * @return true 已就绪
     */
    public static boolean isInstalled() {
        return instrumentation != null;
    }

    /**
     * 获取途径（未安装为 {@link AttachSource#NONE}）。
     *
     * @return 途径
     */
    public static AttachSource source() {
        return source;
    }

    /**
     * 探测：尝试安装并返回是否可用（吞掉安装异常，供 isAvailable() 探测）。
     *
     * @return true 可用
     */
    public static boolean tryInstall() {
        try {
            ensureInstalled();
            return true;
        } catch (TransformException e) {
            return false;
        }
    }

    /**
     * 组装诊断信息。
     */
    private static String diagnose(Throwable t) {
        String java = System.getProperty("java.version", "?");
        String vendor = System.getProperty("java.vendor", "?");
        return "向当前 JVM 自附加 agent 失败（java " + java + " / " + vendor
                + "，原因: " + t + "）。诊断建议："
                + "① 精简 JRE 可能缺少 jdk.attach 模块，请换完整 JDK；"
                + "② 容器环境可能禁用 attach（-XX:+DisableAttachMechanism），请移除该参数"
                + "或放行 attach；"
                + "③ 仍失败可改用启动参数 -javaagent:jframe_main.jar 预注入（最稳、无警告）；"
                + "④ JDK 21+ 自附加会打一条警告，可用 -XX:+EnableDynamicAgentLoading 显式确认。";
    }
}
