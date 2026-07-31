package io.github.JiangHu.jframe.command.resolve;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 参数解析与类型转换工具。
 * <p>
 * 提供两项核心能力：
 * <ol>
 *   <li>{@link #parseArgs} — 将原始 {@code String[]} 拆分为<b>位置参数</b>与<b>命名参数</b></li>
 *   <li>{@link #convert} — 将字符串值转换为目标类型（基础类型 + {@link Player}）</li>
 * </ol>
 *
 * <h3>命名参数解析规则</h3>
 * <pre>
 *   输入: [tower, --desc=cool, -n=3, --public]
 *   位置: [tower]
 *   命名: {desc=cool, n=3, public=true}
 * </pre>
 * <ul>
 *   <li>{@code --key value}：长选项（分离格式），{@code key} 绑定为 {@code value}</li>
 *   <li>{@code --key=value}：长选项（<b>内联格式</b>），{@code key} 绑定为 {@code value}，
 *       可传递以 {@code -} 开头的值（如 {@code --reason=-1}）</li>
 *   <li>{@code --key}（后跟另一选项或位于末尾）：布尔标记，绑定为 {@code "true"}</li>
 *   <li>{@code -k value} / {@code -k=value}：短选项，等价于 {@code --k value} / {@code --k=value}</li>
 *   <li>其余 token 视为位置参数</li>
 * </ul>
 *
 * <h3>支持的类型转换</h3>
 * <ul>
 *   <li>{@code String} — 原值</li>
 *   <li>{@code int}/{@code Integer}、{@code long}/{@code Long}、
 *       {@code double}/{@code Double}、{@code float}/{@code Float}、
 *       {@code boolean}/{@code Boolean} — 对应包装/基本类型</li>
 *   <li>{@link Player} — 按名称查找在线玩家</li>
 * </ul>
 * 转换失败抛出 {@link ArgumentConversionException}，由上层捕获并提示发送者。
 *
 * @see CommandContext
 */
public final class ArgumentResolver {

    private ArgumentResolver() {
    }

    /**
     * 将原始参数数组拆分为位置参数与命名参数。
     * <p>
     * 同时记录每个位置参数在原始 {@code args} 数组中的下标（{@link ParsedArgs#positionalIndices}），
     * 供路由层计算「路径消耗后的剩余原始参数」使用。
     *
     * @param args 原始参数（Nukkit 传入）
     * @return 解析结果（positional + named + positionalIndices）
     */
    public static ParsedArgs parseArgs(String[] args) {
        List<String> positional = new ArrayList<>();
        List<Integer> positionalIndices = new ArrayList<>();
        Map<String, String> named = new LinkedHashMap<>();

        if (args == null) {
            return new ParsedArgs(positional, named, positionalIndices);
        }

        int i = 0;
        while (i < args.length) {
            String token = args[i];
            OptionToken opt = parseOptionToken(token);

            if (opt != null) {
                if (opt.hasInlineValue()) {
                    // --key=value 内联格式：值已附带，直接绑定（可传递以 - 开头的值）
                    named.put(opt.key(), opt.value());
                    i += 1;
                } else {
                    // --key value 分离格式：尝试消费下一个非选项 token
                    String value = consumeValue(args, i + 1);
                    if (value != null) {
                        named.put(opt.key(), value);
                        i += 2;
                    } else {
                        named.put(opt.key(), "true"); // 布尔标记
                        i += 1;
                    }
                }
            } else {
                positional.add(token);
                positionalIndices.add(i); // 记录在原始 args 中的下标
                i += 1;
            }
        }
        return new ParsedArgs(positional, named, positionalIndices);
    }

    /**
     * 取下一个非选项 token 作为选项值；若下一个是选项或越界则返回 null（布尔标记语义）。
     */
    private static String consumeValue(String[] args, int nextIndex) {
        if (nextIndex >= args.length) return null;
        String next = args[nextIndex];
        if (parseOptionToken(next) != null) {
            return null; // 下一个是选项 → 当前为布尔标记
        }
        return next;
    }

    /**
     * 尝试将 token 解析为选项（长选项 {@code --key} 或短选项 {@code -k}）。
     * <p>
     * 同时识别内联赋值格式 {@code --key=value} / {@code -k=value}：
     * <ul>
     *   <li>含 {@code =}：返回的 {@link OptionToken#hasInlineValue()} 为 {@code true}，
     *       {@link OptionToken#value()} 为 {@code =} 之后的子串（可为空串，
     *       也可为以 {@code -} 开头的值，这是内联格式的优势）</li>
     *   <li>不含 {@code =}：{@code hasInlineValue()} 为 {@code false}，
     *       值由后续 token 决定（分离格式或布尔标记）</li>
     * </ul>
     *
     * @param token 待解析的 token
     * @return 选项信息；非选项 token（位置参数或负数 {@code -3}）返回 null
     */
    private static OptionToken parseOptionToken(String token) {
        if (token.startsWith("--") && token.length() > 2) {
            // 长选项 --key / --key=value
            String body = token.substring(2);
            int eq = body.indexOf('=');
            if (eq >= 0) {
                return new OptionToken(body.substring(0, eq), body.substring(eq + 1), true);
            }
            return new OptionToken(body, null, false);
        }
        if (token.startsWith("-") && token.length() > 1 && !isNumeric(token)) {
            // 短选项 -k / -k=value（排除负数 -3）
            String body = token.substring(1);
            int eq = body.indexOf('=');
            if (eq >= 0) {
                return new OptionToken(body.substring(0, eq), body.substring(eq + 1), true);
            }
            return new OptionToken(body, null, false);
        }
        return null;
    }

    /**
     * 判断字符串是否为数字（用于区分短选项 {@code -k} 与负数 {@code -3}）。
     */
    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 将字符串值转换为目标类型。
     *
     * @param value      字符串值
     * @param targetType 目标类型
     * @param sender     命令发送者（用于 Player 查找的上下文）
     * @return 转换后的值
     * @throws ArgumentConversionException 转换失败
     */
    @SuppressWarnings("unchecked")
    public static Object convert(String value, Class<?> targetType, CommandSender sender) {
        if (value == null) return null;

        // String 直接返回
        if (targetType == String.class) {
            return value;
        }

        // 基础类型
        if (targetType == int.class || targetType == Integer.class) {
            return parseInt(value);
        }
        if (targetType == long.class || targetType == Long.class) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new ArgumentConversionException(value, targetType);
            }
        }
        if (targetType == double.class || targetType == Double.class) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                throw new ArgumentConversionException(value, targetType);
            }
        }
        if (targetType == float.class || targetType == Float.class) {
            try {
                return Float.parseFloat(value);
            } catch (NumberFormatException e) {
                throw new ArgumentConversionException(value, targetType);
            }
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return parseBoolean(value);
        }

        // Player：按名称查找
        if (targetType == Player.class) {
            Player player = Server.getInstance().getPlayerExact(value);
            if (player == null) {
                // 容错：部分匹配
                player = Server.getInstance().getPlayer(value);
            }
            if (player == null) {
                throw new ArgumentConversionException(value, targetType);
            }
            return player;
        }

        // CommandSender：尝试按名称查找玩家，否则无法解析
        if (targetType == CommandSender.class) {
            Player player = Server.getInstance().getPlayerExact(value);
            if (player != null) return player;
            throw new ArgumentConversionException(value, targetType);
        }

        // 未知类型：原样返回字符串（交由反射处理可能的失败）
        return value;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new ArgumentConversionException(value, int.class);
        }
    }

    private static boolean parseBoolean(String value) {
        if (value == null) return false;
        if (value.equalsIgnoreCase("true") || value.equals("1")) return true;
        if (value.equalsIgnoreCase("false") || value.equals("0")) return false;
        // 非标准布尔值：非空非 false 视为 true（宽松语义，适配布尔标记）
        return Boolean.parseBoolean(value);
    }

    /**
     * 解析结果：位置参数 + 命名参数 + 位置参数原始下标。
     *
     * @param positional        位置参数列表
     * @param named             命名参数映射
     * @param positionalIndices 每个位置参数在原始 args 数组中的下标（与 positional 一一对应）
     */
    public record ParsedArgs(List<String> positional, Map<String, String> named,
                             List<Integer> positionalIndices) {}

    /**
     * 选项 token 解析结果。
     *
     * @param key            选项名（已去除前导 {@code -}）
     * @param value          内联值（仅 {@code hasInlineValue=true} 时有意义）
     * @param hasInlineValue 是否为 {@code --key=value} 内联格式
     */
    private record OptionToken(String key, String value, boolean hasInlineValue) {}
}
