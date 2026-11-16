package io.github.JiangHu.jframe.transform;

import io.github.JiangHu.jframe.transform.bridge.MethodHook;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部审查 §9.5：ClassLoader 同名类语义验证。
 * <p>
 * 语义定案：<b>按类名 hook 影响所有类加载器中的同名类</b>（Registry 以类名+方法签名
 * 为键，不含加载器身份）。验证：织入后由 child-first 加载器新加载的同名类在定义时
 * 被织入（模拟插件 reload：新类加载器加载同名类即被增强）；unhook 后两个加载器的
 * 同名类都还原，无串扰。
 * <p>
 * 支持边界：目标类的类加载器父链必须可见 bridge（系统加载器）——无父加载器的
 * 自定义加载器无法解析 dispatch，不支持（README 已说明）。
 */
class ClassLoaderIsolationTest {

    /** 同名副本的源类（顶层，便于按资源复制字节码） */
    public static class DuplicateTarget {
        public int value() {
            return 1;
        }
    }

    /**
     * child-first 加载器：仅对目标类名优先用本 URL 定义（模拟另一份插件 jar），
     * 其余类名委托父链——保证 bridge / JDK 等经系统加载器解析。
     */
    static final class ChildFirstLoader extends URLClassLoader {
        private final String targetName;

        ChildFirstLoader(URL[] urls, ClassLoader parent, String targetName) {
            super(urls, parent);
            this.targetName = targetName;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    if (name.equals(targetName)) {
                        c = findClass(name);
                    } else {
                        c = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }
    }

    private static byte[] classBytes(Class<?> cls) throws Exception {
        try (InputStream in = cls.getResourceAsStream("/" + cls.getName().replace('.', '/') + ".class")) {
            return in.readAllBytes();
        }
    }

    private static Path materialize(byte[] bytes, Class<?> cls) throws Exception {
        Path dir = Files.createTempDirectory("jframe-dup");
        Path classFile = dir.resolve(cls.getName().replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, bytes);
        return dir;
    }

    @Test
    void sameNameClassAcrossLoadersHookedByNameAndRestore() throws Exception {
        Class<?> original = DuplicateTarget.class;
        Path dir = materialize(classBytes(original), original);
        String name = original.getName();

        // 织入后加载同名副本（模拟另一插件 / reload 后的新类加载器）
        List<Object> returns = new ArrayList<>();
        HookHandle handle = TransformTestBase.scope().hook(name, "value",
                TransformHookTest.afterHook(ctx -> {
                    returns.add(ctx.getReturn());
                    ctx.setReturn(99);
                }));
        try {
            // ① 系统加载器实例被增强
            assertEquals(99, new DuplicateTarget().value());
            assertEquals(1, returns.get(0));   // after 记录原始返回值

            // ② 织入后由 child-first 加载器加载同名类 → 定义时即被织入
            java.lang.instrument.ClassFileTransformer probe =
                    new java.lang.instrument.ClassFileTransformer() {
                        @Override
                        public byte[] transform(ClassLoader cl, String name2, Class<?> red,
                                                java.security.ProtectionDomain pd, byte[] buf) {
                            if (name2 != null && name2.contains("DuplicateTarget")) {
                                System.out.println("[DBG] jvm-transform fired for " + name2
                                        + " loader=" + cl);
                            }
                            return null;
                        }
                    };
            TransformTestBase.registry().addProbeTransformer(probe);
            ChildFirstLoader loader = new ChildFirstLoader(
                    new URL[]{dir.toUri().toURL()}, original.getClassLoader(), name);
            Class<?> duplicate = Class.forName(name, true, loader);
            System.out.println("[DBG] duplicate loader=" + duplicate.getClassLoader()
                    + " codeSource=" + duplicate.getProtectionDomain().getCodeSource());
            assertNotSame(original, duplicate);   // 同名不同 Class 身份
            TransformTestBase.registry().removeProbeTransformer(probe);
            Object dupInstance = duplicate.getDeclaredConstructor().newInstance();
            Method dupValue = duplicate.getMethod("value");
            assertEquals(99, dupValue.invoke(dupInstance));   // 按类名语义：两个加载器实例都被增强
            assertEquals(2, returns.size());
            assertTrue(handle.touchedClassNames().contains(name));
        } finally {
            handle.unhook();
        }

        // ③ unhook：还原按类名覆盖所有加载器中的同名类，无串扰
        assertEquals(1, new DuplicateTarget().value());
        ChildFirstLoader loader2 = new ChildFirstLoader(
                new URL[]{dir.toUri().toURL()}, original.getClassLoader(), name);
        Class<?> duplicate2 = Class.forName(name, true, loader2);
        Object dupInstance2 = duplicate2.getDeclaredConstructor().newInstance();
        assertEquals(1, duplicate2.getMethod("value").invoke(dupInstance2));
        assertEquals(1, new DuplicateTarget().value());
    }
}
