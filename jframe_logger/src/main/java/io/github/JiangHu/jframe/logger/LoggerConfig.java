package io.github.JiangHu.jframe.logger;

import cn.nukkit.utils.ConfigSection;

import java.nio.file.Path;
import java.util.Map;

/**
 * logger 模块配置（对应 JFrame {@code config.yml} 的 {@code logger:} 节）。
 *
 * @param perSourceLimit     每来源（每子服×每插件）内存条数上限
 * @param globalLimit        全局内存条数上限（聚合视图收集范围）
 * @param persist            是否按天/按插件落盘到 {@code dataFolder/logs/}（默认关）
 * @param bind               host 监听绑定地址
 * @param port               默认端口（/jlog start 不带参数时使用）
 * @param serverIdOverride   子服标识覆盖（blank = 默认 {@code server-<本服端口>}）
 * @param token              简单 token 校验（空 = 不校验）
 * @param dataFolder         JFrame 插件数据目录（落盘根；null = 禁用落盘）
 */
public record LoggerConfig(
        int perSourceLimit,
        int globalLimit,
        boolean persist,
        String bind,
        int port,
        String serverIdOverride,
        String token,
        Path dataFolder) {

    /**
     * 默认配置。
     *
     * @param dataFolder 数据目录（可 null）
     * @return 默认配置
     */
    public static LoggerConfig defaults(Path dataFolder) {
        return new LoggerConfig(5000, 20000, false, "0.0.0.0", 18080, "", "", dataFolder);
    }

    /**
     * 从 {@code config.yml} 的 {@code logger:} 节解析（缺项用默认值）。
     *
     * @param section    logger 节（可为 null/空）
     * @param dataFolder 数据目录
     * @return 配置
     */
    public static LoggerConfig fromSection(ConfigSection section, Path dataFolder) {
        LoggerConfig def = defaults(dataFolder);
        if (section == null) {
            return def;
        }
        Map<String, Object> buffer = child(section, "buffer");
        Map<String, Object> web = child(section, "web");
        return new LoggerConfig(
                intVal(buffer, "per-source", def.perSourceLimit),
                intVal(buffer, "global", def.globalLimit),
                boolVal(section, "persist", def.persist),
                strVal(web, "bind", def.bind),
                intVal(web, "port", def.port),
                strVal(web, "server-id", ""),
                strVal(web, "token", ""),
                dataFolder);
    }

    private static Map<String, Object> child(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        return v instanceof Map<?, ?> m ? uncheckedMap(m) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> uncheckedMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static int intVal(Map<String, Object> section, String key, int def) {
        Object v = section.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static boolean boolVal(Map<String, Object> section, String key, boolean def) {
        Object v = section.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return def;
    }

    private static String strVal(Map<String, Object> section, String key, String def) {
        Object v = section.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : def;
    }
}
