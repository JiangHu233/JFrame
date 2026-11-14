package io.github.JiangHu.jframe.engine.pool;

/**
 * ECS 对象池配置（XML 可配的普通 POJO）。
 *
 * <ul>
 *   <li>{@code enabled}（默认 true）：全局开关，false 时全部行为与无池一致；</li>
 *   <li>{@code maxComponentPerClass}（默认 128）：每组件类空闲上限；</li>
 *   <li>{@code maxEntityShells}（默认 512）：实体壳空闲上限；</li>
 *   <li>{@code debug}（默认 false）：开启双重释放检测 + 组件字段残留扫描（开发期用）。</li>
 * </ul>
 */
public class EcsPoolConfig {

    /** 全局开关（false = 行为与无池完全一致） */
    private boolean enabled = true;

    /** 每组件类空闲表上限 */
    private int maxComponentPerClass = 128;

    /** 实体壳空闲表上限 */
    private int maxEntityShells = 512;

    /** debug 模式（双重释放检测 + 归池前字段残留扫描告警） */
    private boolean debug;

    /**
     * 构造默认配置（enabled=true）。
     */
    public EcsPoolConfig() {
    }

    /**
     * 全局开关。
     *
     * @return 启用 true
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置全局开关。
     *
     * @param enabled 启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 每组件类空闲上限。
     *
     * @return 上限
     */
    public int getMaxComponentPerClass() {
        return maxComponentPerClass;
    }

    /**
     * 设置每组件类空闲上限。
     *
     * @param maxComponentPerClass 上限
     */
    public void setMaxComponentPerClass(int maxComponentPerClass) {
        this.maxComponentPerClass = maxComponentPerClass;
    }

    /**
     * 实体壳空闲上限。
     *
     * @return 上限
     */
    public int getMaxEntityShells() {
        return maxEntityShells;
    }

    /**
     * 设置实体壳空闲上限。
     *
     * @param maxEntityShells 上限
     */
    public void setMaxEntityShells(int maxEntityShells) {
        this.maxEntityShells = maxEntityShells;
    }

    /**
     * debug 模式。
     *
     * @return 开启 true
     */
    public boolean isDebug() {
        return debug;
    }

    /**
     * 设置 debug 模式。
     *
     * @param debug 开启
     */
    public void setDebug(boolean debug) {
        this.debug = debug;
    }
}
