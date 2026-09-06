package io.github.JiangHu.jframe.nbt.core.value;

/**
 * 读取方向数值转换模式（DESIGN.md 4.3）。
 *
 * <p>总原则「读宽容、写严格」：读取默认 {@link #LENIENT}（探测式编程不因类型不符炸掉），
 * 需要强约束的场景（配置校验）显式选 {@link #STRICT}。
 *
 * <table border="1">
 * <caption>两模式行为对照</caption>
 * <tr><th>模式</th><th>数值类型不符</th><th>窄化溢出/丢精度</th><th>不可转换（如 StringTag → int）</th></tr>
 * <tr><td>{@link #STRICT}</td><td>抛 {@code NbtTypeMismatchException}</td><td>抛</td><td>抛</td></tr>
 * <tr><td>{@link #LENIENT}（读取默认）</td><td>{@code xxxValue()} 尽力转（Java 原生窄化：高位截断）</td><td>截断接受</td>
 * <td>降级：Optional 系 → {@code Optional.empty()}，原生系 → 类型零值（开放问题 #8 建议默认值，可在 M2 复议）</td></tr>
 * </table>
 *
 * <p>M1 落地基础档（宽化无损自动 + STRICT 抛 / LENIENT 降级）；窄化丢精度的完整策略
 * （如 {@code NbtReadOption.defaultValue} 的组合语义）在 M2 完整化。
 */
public enum CoerceMode {

    /** 严格：类型不符 / 窄化溢出 / 不可转换一律抛 {@code NbtTypeMismatchException}。 */
    STRICT,

    /** 宽松（读取默认）：尽力转换，失败降级为类型零值 / {@code Optional.empty()}。 */
    LENIENT
}
