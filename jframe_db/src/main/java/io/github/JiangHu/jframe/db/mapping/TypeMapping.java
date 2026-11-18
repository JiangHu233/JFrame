package io.github.JiangHu.jframe.db.mapping;

import io.github.JiangHu.jframe.db.annotation.Column;
import io.github.JiangHu.jframe.db.spi.ColumnType;
import io.github.JiangHu.jframe.serialize.exception.DataException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Java 字段类型 ↔ 列逻辑类型 的推断与值转换（静态工具）。
 * <p>
 * 类型映射表（v1）：
 * <pre>
 *   int/Integer → INT   long/Long → LONG      double/Double → DOUBLE
 *   boolean/Boolean → BOOLEAN                 String → STRING
 *   enum → STRING(name())                     Instant → LONG(epochMilli)
 *   UUID → STRING(36)                         BigDecimal → DECIMAL(precision,scale)
 *   其他（嵌套对象 / 未拆表容器）→ JSON
 * </pre>
 * {@code @Column(type)} 显式声明优先于推断（AUTO = 推断）。
 */
public final class TypeMapping {

    private TypeMapping() {
    }

    /**
     * 解析字段的列逻辑类型（@Column 显式声明优先）。
     *
     * @param field        字段
     * @param columnDecl   字段上的 @Column（可为 null）
     * @return 逻辑类型
     */
    public static ColumnType resolve(Field field, Column columnDecl) {
        if (columnDecl != null && columnDecl.type() != ColumnType.AUTO) {
            return columnDecl.type();
        }
        return resolve(field.getType());
    }

    /**
     * 按 Java 类型推断列逻辑类型。
     *
     * @param type Java 类型
     * @return 逻辑类型
     */
    public static ColumnType resolve(Class<?> type) {
        if (type == int.class || type == Integer.class) return ColumnType.INT;
        if (type == long.class || type == Long.class) return ColumnType.LONG;
        if (type == double.class || type == Double.class) return ColumnType.DOUBLE;
        if (type == boolean.class || type == Boolean.class) return ColumnType.BOOLEAN;
        if (type == String.class) return ColumnType.STRING;
        if (type.isEnum()) return ColumnType.STRING;
        if (type == Instant.class) return ColumnType.LONG;
        if (type == UUID.class) return ColumnType.STRING;
        if (type == BigDecimal.class) return ColumnType.DECIMAL;
        return ColumnType.JSON;
    }

    /**
     * 字段值 → 列值（按逻辑类型归一，供 Row 携带）。
     * <p>
     * enum → name()；Instant → epochMilli；UUID → toString；JSON 列应走
     * RowCodec 的 JSON 文本化，不经本方法。
     *
     * @param fieldValue 字段值
     * @param columnType 逻辑类型
     * @return 列值
     */
    public static Object toColumnValue(Object fieldValue, ColumnType columnType) {
        if (fieldValue == null) {
            return null;
        }
        return switch (columnType) {
            case STRING -> fieldValue instanceof Enum<?> e ? e.name() : String.valueOf(fieldValue);
            case LONG -> fieldValue instanceof Instant i ? i.toEpochMilli() : fieldValue;
            case INT, DOUBLE, BOOLEAN, DECIMAL, TEXT -> fieldValue;
            default -> String.valueOf(fieldValue);
        };
    }

    /**
     * 列值 → JsonElement 兼容的内存值（供实体装配）。
     * <p>
     * 与 {@link #toColumnValue} 对称：enum name → enum 常量、epochMilli → Instant、
     * 字符串 → UUID 等，由字段类型驱动。
     *
     * @param columnValue 列值
     * @param fieldType   字段类型
     * @return 内存值
     */
    public static Object toFieldValue(Object columnValue, Class<?> fieldType) {
        if (columnValue == null) {
            return null;
        }
        if (fieldType.isEnum()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object constant = Enum.valueOf((Class<? extends Enum>) (Class) fieldType, String.valueOf(columnValue));
            return constant;
        }
        if (fieldType == Instant.class) {
            return Instant.ofEpochMilli(((Number) columnValue).longValue());
        }
        if (fieldType == UUID.class) {
            return UUID.fromString(String.valueOf(columnValue));
        }
        if (fieldType == int.class || fieldType == Integer.class) {
            return ((Number) columnValue).intValue();
        }
        if (fieldType == long.class || fieldType == Long.class) {
            return ((Number) columnValue).longValue();
        }
        if (fieldType == double.class || fieldType == Double.class) {
            return ((Number) columnValue).doubleValue();
        }
        if (fieldType == float.class || fieldType == Float.class) {
            return ((Number) columnValue).floatValue();
        }
        return columnValue;
    }

    /**
     * 校验 @Column 参数与逻辑类型的匹配（fail-fast）。
     *
     * @param columnDecl @Column 声明
     * @param resolved   解析后的逻辑类型
     * @param fieldName  字段名（报错用）
     */
    public static void validateColumn(Column columnDecl, ColumnType resolved, String fieldName) {
        if (columnDecl == null) {
            return;
        }
        if (columnDecl.length() >= 0 && resolved != ColumnType.STRING && resolved != ColumnType.TEXT) {
            throw new DataException("@Column(length=" + columnDecl.length() + ") 只能用于 STRING/TEXT 列，字段 "
                    + fieldName + " 的列类型为 " + resolved);
        }
        if (columnDecl.type() == ColumnType.AUTO) {
            return;
        }
        if ((columnDecl.precision() != 10 || columnDecl.scale() != 2)
                && resolved != ColumnType.DECIMAL) {
            throw new DataException("@Column(precision/scale) 只能用于 DECIMAL 列，字段 "
                    + fieldName + " 的列类型为 " + resolved);
        }
    }
}
