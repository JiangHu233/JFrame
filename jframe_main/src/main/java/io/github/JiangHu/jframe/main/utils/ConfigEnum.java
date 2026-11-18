package io.github.JiangHu.jframe.main.utils;

/**
 * JFrame 模块 id 枚举 — 与各 {@code XxxModule#id()} 一致的常量表。
 * <p>
 * 用途：{@code config.yml} 预加载列表的可读参考与日志；实际注册以
 * {@code JFrameMain} 构造时登记的 {@code JFrameModule} 为准。
 *
 * @see io.github.JiangHu.jframe.main.JFrameMain
 */
public enum ConfigEnum {

    /** 事件系统（jframe_listener）：EventAPI */
    EVENT("event"),

    /** 命令系统（jframe_command）：CommandAPI */
    COMMAND("command"),

    /** 异步任务（jframe_async）：ThreadAPI / TaskAPI / ThreadFlow / ReleaseAPI */
    ASYNC("async"),

    /** 表单（jframe_form）：ViewAPI */
    FORM("form"),

    /** 箱子界面（jframe_inventory）：InventoryAPI */
    INVENTORY("inventory"),

    /** 文件持久化（jframe_file，原 jframe_data）：FileAPI */
    FILE("file"),

    /** 数据库存取（jframe_db）：DbAPI（SPI 适配器注册后提供 ORM/缓存/区域） */
    DB("db"),

    /** 实体 AI（jframe_ai）：AiAPI */
    AI("ai"),

    /** 计分板（jframe_scoreboard_depend_template）：ScoreboardAPI */
    SCOREBOARD("scoreboard"),

    /** 标题（jframe_title_depend_template）：TitleAPI */
    TITLE("title"),

    /** 游戏引擎 ECS + 行为树（jframe_engine）：EcsAPI / BtAPI */
    ENGINE("engine"),

    /** 浮空字（jframe_floatingtext）：FloatingTextAPI */
    FLOATING_TEXT("floatingtext"),

    /** 统一日志（logger）：LoggerAPI + /jlog 指令 */
    LOGGER("logger"),

    /** 动态类增强（jframe_transform）：TransformAPI（@HookOn / hook / replace） */
    TRANSFORM("transform");

    private final String moduleId;

    ConfigEnum(String moduleId) {
        this.moduleId = moduleId;
    }

    /**
     * 模块 id（与 {@code JFrameModule#id()} 一致，用于 preload 配置与日志）。
     *
     * @return 模块 id
     */
    public String getModuleId() {
        return moduleId;
    }
}
