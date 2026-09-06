package io.github.JiangHu.jframe.title;

/**
 * 标题显示模式
 *
 * <ul>
 *   <li>{@link #PERSISTENT} 常驻模式：视图经 KeepAlive 管理生命周期
 *       （show / reactivate → deactivate → dispose），支持动作栏周期续期，
 *       数据更新即时刷新，适用于常驻信息栏；</li>
 *   <li>{@link #TRANSIENT} 瞬时模式：show 后按 fadeIn + stay + fadeOut 总时长
 *       调度到期任务，到期自动清除标题并释放视图；stay 期间仍响应数据更新，
 *       适用于公告、提示等一次性展示。</li>
 * </ul>
 */
public enum TitleMode {

    /** 常驻模式：KeepAlive 生命周期，数据驱动刷新 */
    PERSISTENT,

    /** 瞬时模式：按时序到期自动清除并释放 */
    TRANSIENT
}
