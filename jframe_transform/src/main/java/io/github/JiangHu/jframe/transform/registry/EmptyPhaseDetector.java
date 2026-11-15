package io.github.JiangHu.jframe.transform.registry;

import io.github.JiangHu.jframe.transform.bridge.HookContext;
import io.github.JiangHu.jframe.transform.bridge.MethodHook;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 空相位判定（设计方案 §1.2 决策 6 相位省略）。
 * <p>
 * 接口形态钩子：未 override 的 default 方法视为空；override 了但方法体仅单条 RETURN
 * 指令（void）也视为空——读钩子类字节码一次性判定并缓存。注解形态：方法不存在 =
 * 相位不存在（天然省略），注解方法体为空同样判定为空（扫描层跳过注册）。
 */
final class EmptyPhaseDetector {

    /** 判定缓存：钩子类名 → {enter 非空, exit 非空} */
    private static final Map<String, boolean[]> CACHE = new ConcurrentHashMap<>();

    private static final String PHASE_DESC = "(Lio/github/JiangHu/jframe/transform/bridge/HookContext;)V";

    private EmptyPhaseDetector() {
    }

    /**
     * 钩子是否实际占用 enter 相位（before 已 override 且方法体非空）。
     */
    static boolean occupiesEnter(MethodHook hook) {
        return occupancy(hook)[0];
    }

    /**
     * 钩子是否实际占用 exit 相位（after 已 override 且方法体非空）。
     */
    static boolean occupiesExit(MethodHook hook) {
        return occupancy(hook)[1];
    }

    private static boolean[] occupancy(MethodHook hook) {
        String key = hook.getClass().getName();
        boolean[] cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        boolean enter = overriddenAndNonEmpty(hook, "before");
        boolean exit = overriddenAndNonEmpty(hook, "after");
        boolean[] result = new boolean[]{enter, exit};
        CACHE.putIfAbsent(key, result);
        return result;
    }

    /**
     * 指定相位方法是否被 override 且方法体非空。
     */
    private static boolean overriddenAndNonEmpty(MethodHook hook, String phase) {
        Method m;
        try {
            m = hook.getClass().getMethod(phase, HookContext.class);
        } catch (NoSuchMethodException e) {
            return false;
        }
        if (m.getDeclaringClass() == MethodHook.class) {
            return false;   // 未 override，走 default 空实现
        }
        return !isBodyEmpty(hook.getClass(), phase);
    }

    /**
     * 类字节码中 (HookContext)→void 方法的体是否为空（仅单条 RETURN 指令）。
     */
    static boolean isBodyEmpty(Class<?> cls, String methodName) {
        try (InputStream in = cls.getResourceAsStream(
                "/" + cls.getName().replace('.', '/') + ".class")) {
            if (in == null) {
                return false;   // 读不到字节码按"非空"处理（保守：相位保留）
            }
            byte[] bytes = in.readAllBytes();
            boolean[] found = new boolean[]{false};
            boolean[] empty = new boolean[]{false};
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (methodName.equals(name) && PHASE_DESC.equals(descriptor)) {
                        found[0] = true;
                        return new MethodVisitor(Opcodes.ASM9) {
                            private boolean sawReturn;
                            private int realOpcodes;

                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode == Opcodes.RETURN) {
                                    sawReturn = true;
                                } else {
                                    realOpcodes++;
                                }
                            }

                            @Override
                            public void visitVarInsn(int opcode, int index) {
                                realOpcodes++;
                            }

                            @Override
                            public void visitTypeInsn(int opcode, String type) {
                                realOpcodes++;
                            }

                            @Override
                            public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                                realOpcodes++;
                            }

                            @Override
                            public void visitMethodInsn(int opcode, String owner, String name,
                                                        String descriptor, boolean isInterface) {
                                realOpcodes++;
                            }

                            @Override
                            public void visitLdcInsn(Object value) {
                                realOpcodes++;
                            }

                            @Override
                            public void visitJumpInsn(int opcode, Label label) {
                                realOpcodes++;
                            }

                            @Override
                            public void visitEnd() {
                                empty[0] = sawReturn && realOpcodes == 0;
                            }
                        };
                    }
                    return null;
                }
            }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            if (!found[0]) {
                return false;   // 声明方法未找到（如 default 继承/父类 override），保守非空
            }
            return empty[0];
        } catch (Exception e) {
            return false;
        }
    }
}
