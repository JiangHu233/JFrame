package io.github.JiangHu.jframe.transform.registry;

import io.github.JiangHu.jframe.transform.HookPriority;
import io.github.JiangHu.jframe.transform.bridge.MethodHook;
import io.github.JiangHu.jframe.transform.bridge.ReplaceHook;

import java.lang.reflect.Method;

/**
 * 单条钩子记录（注册表最小单元）——一条注册对应一条记录。
 * <p>
 * 相位占用：{@code occupiesEnter}（before 非空或 REPLACE）/ {@code occupiesExit}
 * （after 非空）驱动相位省略（未占用相位不内联 dispatch）。REPLACE 的两种形态：
 * <ul>
 *   <li>命令式 / 注解无类型：{@code replace != null}（{@link ReplaceHook}）</li>
 *   <li>注解签名镜像：{@code mirror != null}（反射按目标签名装箱/拆箱调用）</li>
 * </ul>
 */
public final class HookRecord {

    /** 归属插件名（disable 时自动 unhook） */
    final String pluginName;

    /** 优先级 */
    final HookPriority priority;

    /** 注册 id */
    final int regId;

    /** before/after 钩子（接口形态或注解适配器；可 null） */
    final MethodHook hook;

    /** REPLACE 无类型实现（可 null） */
    final ReplaceHook replace;

    /** REPLACE 签名镜像（注解层专属；可 null） */
    final MirrorInfo mirror;

    /** 是否需要参数数组（before/REPLACE 阶段；镜像 REPLACE 恒 true） */
    final boolean needsArgs;

    /** 是否占用 enter 相位（before 非空或 REPLACE） */
    final boolean occupiesEnter;

    /** 是否占用 exit 相位（after 非空） */
    final boolean occupiesExit;

    HookRecord(String pluginName, HookPriority priority, int regId,
               MethodHook hook, ReplaceHook replace, MirrorInfo mirror,
               boolean needsArgs, boolean occupiesEnter, boolean occupiesExit) {
        this.pluginName = pluginName;
        this.priority = priority;
        this.regId = regId;
        this.hook = hook;
        this.replace = replace;
        this.mirror = mirror;
        this.needsArgs = mirror != null || needsArgs;
        this.occupiesEnter = occupiesEnter;
        this.occupiesExit = occupiesExit;
    }

    /** 签名镜像元数据（注解层 REPLACE） */
    public static final class MirrorInfo {

        /** 插件侧镜像方法（签名与目标一致） */
        public final Method method;

        /** 宿主实例（静态注解方法为 null） */
        public final Object host;

        /** 镜像完整参数类型名列表（可能含首部实例参，织入校验时裁定） */
        public final java.util.List<String> paramTypeNames;

        /** 镜像返回类型全限定名（基本类型为基本名） */
        public final String returnTypeName;

        /** 织入校验裁定：参数列表首部是否为实例参（目标非 static 时 true） */
        public volatile boolean resolvedInstanceParam;

        public MirrorInfo(Method method, Object host, java.util.List<String> paramTypeNames,
                          String returnTypeName) {
            this.method = method;
            this.host = host;
            this.paramTypeNames = paramTypeNames;
            this.returnTypeName = returnTypeName;
        }
    }
}
