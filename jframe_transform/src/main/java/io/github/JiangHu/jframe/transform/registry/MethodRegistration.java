package io.github.JiangHu.jframe.transform.registry;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个（类#方法+签名）的织入登记——分发 id 的持有者。
 * <p>
 * id 在首次织入时分配并内联进目标方法体（永不重组）；链写时复制，读侧零锁。
 * 织入形状（相位省略结果）与分发所需的返回值元数据在此维护。
 */
final class MethodRegistration {

    /** 分发 id（Advice 常量内联） */
    final int id;

    /** 目标类全限定名 */
    final String className;

    /** 目标方法名 */
    final String methodName;

    /** 方法描述符（JVM 形态，如 {@code (ILjava/lang/String;)V}） */
    final String descriptor;

    /** 参数类型名列表（基本类型为基本名） */
    final List<String> paramTypeNames;

    /** 参数个数 */
    final int paramCount;

    /** 目标方法是否 static */
    final boolean isStatic;

    /** 返回 void */
    final boolean voidReturn;

    /** 返回类型名（基本类型为基本名） */
    final String returnTypeName;

    /** 返回基本类型 */
    final boolean primitiveReturn;

    /** 钩子链（按优先级降序；写时复制） */
    volatile List<HookRecord> chain = List.of();

    /** 链中是否有 after 记录（分发快速判断） */
    volatile boolean hasAfter;

    /** 织入时记录：是否织入 enter（相位省略结果） */
    volatile boolean wovenEnter;

    /** 织入时记录：是否织入 exit */
    volatile boolean wovenExit;

    /** 织入时记录：enter 是否带参数数组（模板形态） */
    volatile boolean wovenArgs;

    /** REPLACE null/类型不符的默认值回退警告计数（限流） */
    final AtomicLong defaultWarnCount = new AtomicLong();

    MethodRegistration(int id, String className, String methodName, String descriptor,
                       List<String> paramTypeNames, int paramCount, boolean isStatic,
                       boolean voidReturn, String returnTypeName, boolean primitiveReturn) {
        this.id = id;
        this.className = className;
        this.methodName = methodName;
        this.descriptor = descriptor;
        this.paramTypeNames = paramTypeNames;
        this.paramCount = paramCount;
        this.isStatic = isStatic;
        this.voidReturn = voidReturn;
        this.returnTypeName = returnTypeName;
        this.primitiveReturn = primitiveReturn;
    }

    /**
     * 计算链的相位要求（注册期与织入期共用的纯函数）。
     * <p>
     * needsEnter 时无条件织 exit：非 void 是短路返回值写回的出口；void 是
     * forceThrow / REPLACE 抛出异常的<b>唯一传播出口</b>（缺少 exit 时异常会被
     * 静默吞掉——外部审查 P0-B）。
     *
     * @param chain 钩子链
     * @return {needsEnter, needsArgs, needsExit, hasAfter}
     */
    static boolean[] requirementsOf(List<HookRecord> chain) {
        boolean needsEnter = false;
        boolean needsArgs = false;
        boolean needsExit = false;
        boolean hasAfter = false;
        for (HookRecord rec : chain) {
            if (rec.occupiesEnter) {
                needsEnter = true;
                if (rec.needsArgs) {
                    needsArgs = true;
                }
            }
            if (rec.occupiesExit) {
                needsExit = true;
                hasAfter = true;
            }
        }
        if (needsEnter) {
            needsExit = true;
        }
        return new boolean[]{needsEnter, needsArgs, needsExit, hasAfter};
    }
}
