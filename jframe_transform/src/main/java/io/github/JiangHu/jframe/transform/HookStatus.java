package io.github.JiangHu.jframe.transform;

/**
 * 钩子注册状态。
 */
public enum HookStatus {

    /** 目标类尚未加载/尚未织入：transformer 待命，类加载或下次刷新时激活 */
    PENDING,

    /** 已织入并生效 */
    ACTIVE,

    /** 失败（retransform 被拒 / 签名镜像不匹配 / 类不可增强），详见控制台日志 */
    FAILED
}
