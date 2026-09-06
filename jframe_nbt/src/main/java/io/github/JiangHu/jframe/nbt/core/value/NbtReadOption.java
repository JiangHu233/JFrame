package io.github.JiangHu.jframe.nbt.core.value;

/**
 * 类型化读取选项（DESIGN.md 4.3 / 4.6）—— 配合 {@code NbtAPI.get(root, path, type, option)} 使用。
 *
 * <p>不可变值对象，三个维度：
 * <ul>
 *   <li>{@link #coerceMode()} —— 读取转换模式（默认 {@link CoerceMode#LENIENT}，读宽容）；</li>
 *   <li>{@link #defaultValue()} —— LENIENT 降级 / 0 命中时返回的默认值（null 表示降级为
 *       {@code Optional.empty()}；开放问题 #8 建议默认值语义，可在 M2 复议）；</li>
 *   <li>{@link #deepUnwrap()} —— 是否深解包（{@code NbtValues.toJavaValue} 语义，
 *       false 时浅层解包保持 Tag）。</li>
 * </ul>
 *
 * <p>M1 为基础档；M2 完整化时按需扩展（如窄化策略细化）。
 *
 * @param coerceMode  读取转换模式（null 视为 LENIENT）
 * @param defaultValue 降级默认值（可为 null）
 * @param deepUnwrap  是否深解包为纯 Java 结构
 */
public record NbtReadOption(CoerceMode coerceMode, Object defaultValue, boolean deepUnwrap) {

    public NbtReadOption {
        if (coerceMode == null) {
            coerceMode = CoerceMode.LENIENT;
        }
    }

    /** 仅指定模式的选项（无默认值、浅解包）。 */
    public static NbtReadOption of(CoerceMode mode) {
        return new NbtReadOption(mode, null, false);
    }

    /** 指定模式 + 降级默认值。 */
    public static NbtReadOption of(CoerceMode mode, Object defaultValue) {
        return new NbtReadOption(mode, defaultValue, false);
    }

    /** 深解包选项。 */
    public static NbtReadOption deep(CoerceMode mode) {
        return new NbtReadOption(mode, null, true);
    }

    public NbtReadOption withCoerceMode(CoerceMode mode) {
        return new NbtReadOption(mode, defaultValue, deepUnwrap);
    }

    public NbtReadOption withDefaultValue(Object value) {
        return new NbtReadOption(coerceMode, value, deepUnwrap);
    }

    public NbtReadOption withDeepUnwrap(boolean deep) {
        return new NbtReadOption(coerceMode, defaultValue, deep);
    }
}
