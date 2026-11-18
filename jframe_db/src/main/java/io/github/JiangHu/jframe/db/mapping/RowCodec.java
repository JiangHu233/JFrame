package io.github.JiangHu.jframe.db.mapping;

import com.google.gson.Gson;
import io.github.JiangHu.jframe.db.spi.Row;
import io.github.JiangHu.jframe.serialize.SaveIdentifiable;
import io.github.JiangHu.jframe.serialize.core.meta.FieldMetadata;
import io.github.JiangHu.jframe.serialize.exception.DataException;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 实体 ↔ Row ↔ JSON 的编解码器。
 * <ul>
 *   <li>{@link #toMainRow}：实体 → 主表行（标量列归一 + JSON 列文本化）</li>
 *   <li>{@link #fromMainRow}：主表行 → 实体（经 serialize Gson 管线装配 @SaveField 字段）</li>
 *   <li>{@link #snapshot}/{@link #fromSnapshot}：实体 ↔ 缓存快照 JSON（同一管线）</li>
 *   <li>子表行 / 关联行的编码与装配辅助</li>
 * </ul>
 */
public final class RowCodec {

    private final Gson gson;

    /**
     * @param gson 注册了 serialize 注解管线的 Gson（与 DbAPI 同源）
     */
    public RowCodec(Gson gson) {
        this.gson = gson;
    }

    // ========== 实体 ↔ 主表行 ==========

    /**
     * 实体 → 主表行。
     *
     * @param entity  实体
     * @param mapping 映射
     * @param key     主键值
     * @param <T>     实体类型
     * @return 主表行（含主键列）
     */
    public <T> Row toMainRow(T entity, EntityMapping mapping, String key) {
        Row row = Row.empty(mapping.mainTable());
        row.put(mapping.keyColumn(), key);
        for (EntityMapping.ScalarField scalar : mapping.scalars()) {
            Object value = fieldValue(scalar.meta(), entity);
            if (scalar.columnType() == io.github.JiangHu.jframe.db.spi.ColumnType.JSON) {
                row.put(scalar.meta().alias(), value == null ? null
                        : gson.toJsonTree(value, scalar.meta().field().getGenericType()).toString());
            } else {
                row.put(scalar.meta().alias(),
                        TypeMapping.toColumnValue(value, scalar.columnType()));
            }
        }
        return row;
    }

    /**
     * 主表行 → 实体（装配标量与 JSON 列；容器字段由 DataManager 继续装配子表/关联）。
     * <p>
     * 标量列<b>直接反射赋值</b>（经 {@link TypeMapping#toFieldValue} 归一，绕开 Gson
     * 对 Instant/UUID/enum 的原生盲区）；JSON 列经 serialize 管线反序列化；
     * 缺失列按 @SaveField 的 required 语义 fail-fast。
     *
     * @param row     主表行
     * @param mapping 映射
     * @param <T>     实体类型
     * @return 实体
     */
    public <T> T fromMainRow(Row row, EntityMapping mapping) {
        T entity = newInstance(mapping.type());
        for (EntityMapping.ScalarField scalar : mapping.scalars()) {
            Object columnValue = row.get(scalar.meta().alias());
            if (columnValue == null) {
                if (scalar.meta().required()) {
                    throw new DataException("必需字段 '" + scalar.meta().alias()
                            + "' 的列值缺失（表 " + mapping.tableName() + "，主键 " + row.key() + "）");
                }
                continue;
            }
            Object value;
            if (scalar.columnType() == io.github.JiangHu.jframe.db.spi.ColumnType.JSON) {
                value = gson.fromJson(String.valueOf(columnValue),
                        scalar.meta().field().getGenericType());
            } else {
                value = TypeMapping.toFieldValue(columnValue, scalar.meta().field().getType());
            }
            setFieldValue(scalar.meta().field(), entity, value);
        }
        return entity;
    }

    /** 无参构造器实例化（支持非 public 类） */
    private static <T> T newInstance(Class<?> type) {
        try {
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            @SuppressWarnings("unchecked")
            T instance = (T) constructor.newInstance();
            return instance;
        } catch (NoSuchMethodException e) {
            throw new DataException("实体类 " + type.getName() + " 缺少无参构造器", e);
        } catch (Exception e) {
            throw new DataException("实例化实体类 " + type.getName() + " 失败", e);
        }
    }

    // ========== 缓存快照 ==========

    /**
     * 实体 → 缓存快照 JSON（serialize 注解管线，只含 @SaveField 字段）。
     *
     * @param entity 实体
     * @return JSON 文本
     */
    public String snapshot(Object entity) {
        return gson.toJson(entity);
    }

    /**
     * 缓存快照 JSON → 实体。
     *
     * @param json 快照
     * @param type 实体类
     * @param <T>  实体类型
     * @return 实体
     */
    public <T> T fromSnapshot(String json, Class<T> type) {
        return gson.fromJson(json, type);
    }

    // ========== 子表行 ==========

    /**
     * 容器元素 → 子表行集合（parent_key + seq 重写式）。
     *
     * @param parentKey   父主键
     * @param elements    容器元素（可为 null = 空容器）
     * @param child       子表字段
     * @return 子表行列表
     */
    public List<Row> toChildRows(String parentKey, Collection<?> elements,
                                 EntityMapping.ChildField child) {
        List<Row> rows = new ArrayList<>();
        if (elements == null) {
            return rows;
        }
        int seq = 0;
        for (Object element : elements) {
            Row row = Row.empty(child.table());
            row.put("id", parentKey + ":" + seq);
            row.put("parent_key", parentKey);
            row.put("seq", seq);
            if (child.jsonValue()) {
                row.put("value", element == null ? null
                        : gson.toJsonTree(element, child.elementType()).toString());
            } else {
                row.put("value", element);
            }
            rows.add(row);
            seq++;
        }
        return rows;
    }

    /**
     * 子表行 → 元素列表（按 seq 排序）。
     *
     * @param childRows 子表行
     * @param child     子表字段
     * @return 元素列表
     */
    public List<Object> decodeChildValues(List<Row> childRows, EntityMapping.ChildField child) {
        List<Row> sorted = new ArrayList<>(childRows);
        sorted.sort((a, b) -> Integer.compare(
                ((Number) a.get("seq")).intValue(), ((Number) b.get("seq")).intValue()));
        List<Object> values = new ArrayList<>(sorted.size());
        for (Row row : sorted) {
            Object raw = row.get("value");
            if (raw == null) {
                values.add(null);
            } else if (child.jsonValue()) {
                values.add(gson.fromJson(String.valueOf(raw), child.elementType()));
            } else {
                values.add(TypeMapping.toFieldValue(raw, child.elementType()));
            }
        }
        return values;
    }

    // ========== 关联行 ==========

    /**
     * 引用实体集合 → 关联行集合（owner_key + ref_key；id = ownerKey:refKey）。
     *
     * @param ownerKey 父主键
     * @param refs     引用实体集合（可为 null）
     * @param relation 关联字段
     * @return 关联行列表
     */
    public List<Row> toRelationRows(String ownerKey, Collection<?> refs,
                                    EntityMapping.RelationField relation) {
        List<Row> rows = new ArrayList<>();
        if (refs == null) {
            return rows;
        }
        for (Object ref : refs) {
            String refKey = ((SaveIdentifiable) ref).saveKey();
            Row row = Row.empty(relation.table());
            row.put("id", ownerKey + ":" + refKey);
            row.put("owner_key", ownerKey);
            row.put("ref_key", refKey);
            rows.add(row);
        }
        return rows;
    }

    /** 从关联行提取 ref_key 集合。 */
    public static Set<String> relationRefKeys(List<Row> relationRows) {
        Set<String> keys = new LinkedHashSet<>();
        for (Row row : relationRows) {
            keys.add(String.valueOf(row.get("ref_key")));
        }
        return keys;
    }

    // ========== 字段访问 ==========

    /** 读取字段值（反射，异常包装）。 */
    private static Object fieldValue(FieldMetadata meta, Object entity) {
        try {
            return meta.field().get(entity);
        } catch (IllegalAccessException e) {
            throw new DataException("无法读取字段 " + meta.field().getName()
                    + "（类 " + entity.getClass().getName() + "）", e);
        }
    }

    /** 写入字段值（反射，异常包装）。 */
    public static void setFieldValue(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException e) {
            throw new DataException("无法写入字段 " + field.getName()
                    + "（类 " + target.getClass().getName() + "）", e);
        }
    }
}
