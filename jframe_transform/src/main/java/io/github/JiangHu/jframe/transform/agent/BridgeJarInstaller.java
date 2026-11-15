package io.github.JiangHu.jframe.transform.agent;

import io.github.JiangHu.jframe.transform.TransformException;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * bridge jar 组装与注入（设计方案 §2 类加载器边界）。
 * <p>
 * 把打包在 jar 内 {@code META-INF/jframe-bridge/} 下的 bridge 包原始 .class 资源
 * 组装为 {@code bridge-<内容hash>.jar} 落盘（稳定文件名 + 内容指纹，已存在且指纹
 * 匹配则复用），经 {@code appendToSystemClassLoaderSearch} 注入系统类加载器，
 * 再 {@code Class.forName} 取得<b>全 JVM 唯一规范副本</b>。
 * <p>
 * 若系统加载器已能解析 bridge 类（如单元测试 classpath 直含 bridge 包 / 其他途径
 * 已注入），直接复用、不再注入——保证任何环境单一副本。
 */
public final class BridgeJarInstaller {

    /** bridge 包内嵌资源前缀（与 pom 的 antrun 拷贝路径一致） */
    private static final String RESOURCE_PREFIX = "META-INF/jframe-bridge/";

    /** bridge 包全部类（与 src/main/java/.../bridge 一一对应；新增类需同步此表） */
    private static final String[] BRIDGE_CLASSES = {
            "MethodHook",
            "ReplaceHook",
            "HookAt",
            "HookContext",
            "Bridge",
            "State",
            "ExitState",
            "Sink",
    };

    /** bridge 包根（路径形态） */
    private static final String BRIDGE_PACKAGE_PATH = "io/github/JiangHu/jframe/transform/bridge";

    /** 落盘目录名（宿主插件数据目录下） */
    public static final String DIR_NAME = "transform";

    private final ClassLoader frameworkLoader;

    /**
     * 构造安装器。
     *
     * @param frameworkLoader jframe_transform 所在类加载器（读取内嵌资源用）
     */
    public BridgeJarInstaller(ClassLoader frameworkLoader) {
        this.frameworkLoader = frameworkLoader;
    }

    /**
     * 确保 bridge 类在系统类加载器可解析（幂等）。
     *
     * @param instrumentation Instrumentation
     * @param dataDir         落盘目录（宿主插件数据目录；不可写时调用方应传临时目录）
     */
    public void install(Instrumentation instrumentation, Path dataDir) {
        ClassLoader system = ClassLoader.getSystemClassLoader();
        // 1. 已可解析（测试 classpath / 已注入）→ 单副本直接复用
        if (resolvable(system)) {
            return;
        }
        // 2. 组装 bridge jar（内容指纹命名，命中即复用）
        try {
            Path jar = materializeJar(dataDir);
            instrumentation.appendToSystemClassLoaderSearch(
                    new JarFile(jar.toAbsolutePath().toString()));
        } catch (Exception e) {
            throw new TransformException("bridge jar 注入系统类加载器失败: " + e, e);
        }
        // 3. 校验规范副本可解析
        if (!resolvable(system)) {
            throw new TransformException("bridge jar 注入后系统类加载器仍无法解析 bridge 类（未知环境问题）");
        }
    }

    /**
     * bridge 类是否可被给定加载器解析。
     */
    private boolean resolvable(ClassLoader loader) {
        try {
            Class.forName(bridgeName("Bridge"), false, loader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * 组装 bridge jar 到 dataDir/transform/bridge-<hash>.jar。
     */
    private Path materializeJar(Path dataDir) throws Exception {
        List<byte[]> blobs = new ArrayList<>(BRIDGE_CLASSES.length);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String simpleName : BRIDGE_CLASSES) {
            String resource = RESOURCE_PREFIX + BRIDGE_PACKAGE_PATH + "/" + simpleName + ".class";
            URL url = frameworkLoader.getResource(resource);
            if (url == null) {
                throw new TransformException("内嵌 bridge 资源缺失: " + resource
                        + "（打包异常：antrun embed-bridge-classes 未执行？）");
            }
            byte[] bytes;
            try (InputStream in = url.openStream()) {
                bytes = in.readAllBytes();
            }
            blobs.add(bytes);
            digest.update(simpleName.getBytes(StandardCharsets.UTF_8));
            digest.update(bytes);
        }
        String hash = HexFormat.of().formatHex(digest.digest(), 0, 8);
        Path dir = dataDir.resolve(DIR_NAME);
        Files.createDirectories(dir);
        Path jar = dir.resolve("bridge-" + hash + ".jar");
        if (Files.exists(jar)) {
            return jar;
        }
        Path tmp = dir.resolve("bridge-" + hash + ".jar.tmp");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(tmp))) {
            for (int i = 0; i < BRIDGE_CLASSES.length; i++) {
                out.putNextEntry(new JarEntry(BRIDGE_PACKAGE_PATH + "/" + BRIDGE_CLASSES[i] + ".class"));
                out.write(blobs.get(i));
                out.closeEntry();
            }
        }
        try {
            Files.move(tmp, jar, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFail) {
            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        }
        return jar;
    }

    /**
     * 反射读取 -javaagent 预注入的 Instrumentation（系统类加载器副本的 TransformAgent）。
     *
     * @return Instrumentation；未预注入返回 null
     */
    public static Instrumentation readPremainInstrumentation() {
        try {
            Class<?> agent = Class.forName(
                    "io.github.JiangHu.jframe.transform.agent.TransformAgent",
                    true, ClassLoader.getSystemClassLoader());
            return (Instrumentation) agent.getField("instrumentation").get(null);
        } catch (ClassNotFoundException notOnSystem) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String bridgeName(String simpleName) {
        return "io.github.JiangHu.jframe.transform.bridge." + simpleName;
    }
}
