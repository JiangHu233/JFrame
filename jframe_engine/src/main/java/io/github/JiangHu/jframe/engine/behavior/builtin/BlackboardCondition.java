package io.github.JiangHu.jframe.engine.behavior.builtin;

import io.github.JiangHu.jframe.engine.behavior.BtCondition;
import io.github.JiangHu.jframe.engine.behavior.BtConditionWord;
import io.github.JiangHu.jframe.engine.behavior.BtContext;

/**
 * 内置条件：黑板键比较（eq/ne/gt/lt/ge/le；数值按 double 比较，其余按字符串）。
 *
 * <p>YAML：{@code {cond: bt:blackboard, params: {key: last_seen, op: gt, value: 0}}}
 */
@BtConditionWord("bt:blackboard")
public final class BlackboardCondition extends BtCondition {

    /** 黑板键（公有字段=参数键） */
    public String key;

    /** 比较运算（eq/ne/gt/lt/ge/le；缺省 eq） */
    public String op;

    /** 比较值（数值或字符串） */
    public Object value;

    @Override
    public boolean test(BtContext context) {
        if (key == null) {
            return false;
        }
        Object actual = context.blackboard().get(key);
        if (actual == null) {
            return false; // 键缺失视为不满足（含 value 为 null 的语义约定）
        }
        String operator = op == null ? "eq" : op;
        if (actual instanceof Number a && value instanceof Number b) {
            double x = a.doubleValue();
            double y = b.doubleValue();
            return switch (operator) {
                case "eq" -> x == y;
                case "ne" -> x != y;
                case "gt" -> x > y;
                case "lt" -> x < y;
                case "ge" -> x >= y;
                case "le" -> x <= y;
                default -> false;
            };
        }
        String x = String.valueOf(actual);
        String y = String.valueOf(value);
        return switch (operator) {
            case "eq" -> x.equals(y);
            case "ne" -> !x.equals(y);
            default -> false; // 非数值只支持等值比较
        };
    }
}
