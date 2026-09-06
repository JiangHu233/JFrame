package io.github.JiangHu.jframe.title.channel;

import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.title.TitleConstants;
import io.github.JiangHu.jframe.title.TitleLog;
import io.github.JiangHu.jframe.title.TitleMode;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * title 元数据解析器：{@link Template} → {@link TitleMetaConfig}
 *
 * <p>消费模板 {@code <meta key value/>} 收集到的元数据（title.* 命名空间），
 * 解析规则：</p>
 * <ul>
 *   <li>非 title.* 前缀的 key：属于其他模块命名空间，静默忽略；</li>
 *   <li>title.* 前缀但未知的 key：记录告警日志，不抛异常；</li>
 *   <li>已知 key 的值非法（非整数 / 越界 / 非法枚举）：
 *       抛出 {@link IllegalArgumentException}，中文消息指明模板名、key 与原值（fail-fast）；</li>
 *   <li>区间起点 &le; 终点等跨 key 校验在 {@link io.github.JiangHu.jframe.title.TitleTemplate}
 *       三级融合完成后统一执行。</li>
 * </ul>
 */
public final class TitleMetaResolver {

    private static final String TAG = "TitleMetaResolver";

    private TitleMetaResolver() {
    }

    /**
     * 解析模板元数据
     *
     * @param template 模板
     * @return 解析结果；模板无 title.* 元数据时返回 {@link TitleMetaConfig#EMPTY}
     */
    public static TitleMetaConfig resolve(Template template) {
        return resolve(template, null);
    }

    /**
     * 解析模板元数据
     *
     * @param template     模板
     * @param templateName 模板名（用于异常消息定位；可为 null）
     * @return 解析结果；模板无 title.* 元数据时返回 {@link TitleMetaConfig#EMPTY}
     * @throws IllegalArgumentException 已知 key 的值非法
     */
    public static TitleMetaConfig resolve(Template template, String templateName) {
        Map<String, String> metadata = template.getMetadata();
        if (metadata.isEmpty()) {
            return TitleMetaConfig.EMPTY;
        }

        OptionalInt subtitleFrom = OptionalInt.empty();
        OptionalInt subtitleTo = OptionalInt.empty();
        OptionalInt actionbarLine = OptionalInt.empty();
        OptionalInt timingFadeIn = OptionalInt.empty();
        OptionalInt timingStay = OptionalInt.empty();
        OptionalInt timingFadeOut = OptionalInt.empty();
        Optional<TitleMode> mode = Optional.empty();
        Optional<Boolean> autoClear = Optional.empty();
        OptionalInt actionBarRefresh = OptionalInt.empty();

        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!TitleMetaKeys.isTitleKey(key)) {
                continue;
            }
            switch (key) {
                case TitleMetaKeys.SUBTITLE_FROM -> subtitleFrom = OptionalInt.of(
                        parseInt(templateName, key, value, 0));
                case TitleMetaKeys.SUBTITLE_TO -> subtitleTo = OptionalInt.of(
                        parseInt(templateName, key, value, 0));
                case TitleMetaKeys.ACTIONBAR_LINE -> actionbarLine = OptionalInt.of(
                        parseInt(templateName, key, value, TitleConstants.ACTIONBAR_DISABLED));
                case TitleMetaKeys.TIMING_FADE_IN -> timingFadeIn = OptionalInt.of(
                        parseInt(templateName, key, value, 0));
                case TitleMetaKeys.TIMING_STAY -> timingStay = OptionalInt.of(
                        parseInt(templateName, key, value, 0));
                case TitleMetaKeys.TIMING_FADE_OUT -> timingFadeOut = OptionalInt.of(
                        parseInt(templateName, key, value, 0));
                case TitleMetaKeys.MODE -> mode = Optional.of(
                        parseMode(templateName, key, value));
                case TitleMetaKeys.AUTOCLEAR -> autoClear = Optional.of(
                        parseBoolean(templateName, key, value));
                case TitleMetaKeys.ACTIONBAR_REFRESH -> actionBarRefresh = OptionalInt.of(
                        parseInt(templateName, key, value, 0));
                default -> TitleLog.warning(TAG, "模板[" + safeName(templateName)
                        + "]包含未知的 title 元数据 key：" + key + "（已忽略）");
            }
        }

        return new TitleMetaConfig(subtitleFrom, subtitleTo, actionbarLine,
                timingFadeIn, timingStay, timingFadeOut, mode, autoClear, actionBarRefresh);
    }

    /** 解析非负（或允许的最小值）整数，非法时抛 IAE */
    private static int parseInt(String templateName, String key, String value, int minValue) {
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw illegalValue(templateName, key, value, "应为整数");
        }
        if (parsed < minValue) {
            throw illegalValue(templateName, key, value, "不得小于 " + minValue);
        }
        return parsed;
    }

    /** 解析显示模式（大小写不敏感） */
    private static TitleMode parseMode(String templateName, String key, String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "persistent" -> TitleMode.PERSISTENT;
            case "transient" -> TitleMode.TRANSIENT;
            default -> throw illegalValue(templateName, key, value, "应为 persistent 或 transient");
        };
    }

    /** 解析布尔值（仅接受 true / false，大小写不敏感） */
    private static boolean parseBoolean(String templateName, String key, String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }
        throw illegalValue(templateName, key, value, "应为 true 或 false");
    }

    private static IllegalArgumentException illegalValue(String templateName, String key, String value, String reason) {
        return new IllegalArgumentException("模板[" + safeName(templateName) + "]元数据 "
                + key + "=" + value + " 非法：" + reason);
    }

    private static String safeName(String templateName) {
        return templateName != null ? templateName : "<未命名>";
    }
}
