package io.github.JiangHu.jframe.transform;

/**
 * 钩子优先级（对齐事件系统习惯）——同方法多钩子时 HIGHEST→LOWEST 依次执行，
 * after 链反序；多个 REPLACE 时最高优先级者先执行并胜出。
 */
public enum HookPriority {

    /** 最高（先执行） */
    HIGHEST(5),

    /** 高 */
    HIGH(4),

    /** 普通（默认） */
    NORMAL(3),

    /** 低 */
    LOW(2),

    /** 最低（后执行） */
    LOWEST(1);

    private final int level;

    HookPriority(int level) {
        this.level = level;
    }

    /**
     * 优先级数值（大者先执行）。
     *
     * @return 数值
     */
    public int level() {
        return level;
    }
}
