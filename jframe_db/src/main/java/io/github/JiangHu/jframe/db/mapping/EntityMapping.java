package io.github.JiangHu.jframe.db.mapping;

import io.github.JiangHu.jframe.db.annotation.ChildTable;
import io.github.JiangHu.jframe.db.annotation.Column;
import io.github.JiangHu.jframe.db.annotation.Indexed;
import io.github.JiangHu.jframe.db.annotation.RelationTable;
import io.github.JiangHu.jframe.db.spi.ColumnDef;
import io.github.JiangHu.jframe.db.spi.ColumnType;
import io.github.JiangHu.jframe.db.spi.TableDef;
import io.github.JiangHu.jframe.serialize.SaveIdentifiable;
import io.github.JiangHu.jframe.serialize.core.meta.ClassMetadata;
import io.github.JiangHu.jframe.serialize.core.meta.FieldMetadata;
import io.github.JiangHu.jframe.serialize.core.meta.MetadataCache;
import io.github.JiangHu.jframe.serialize.exception.DataException;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 实体映射模型 — 解析实体类产出的全部表定义与字段分类（不可变）。
 * <p>
 * 一个 {@link EntityMapping} = 实体类 + 主表 TableDef + 子表/关联表 TableDef
 * + 字段分类（标量列 / JSON 列 / @ChildTable / @RelationTable）。
 * 由 {@code DataManagerBuilder.build()} 构建一次，{@code DataManager} 与
 * {@code RowCodec} 共享使用。
 *
 * <h3>校验（fail-fast，映射期报错）</h3>
 * <ul>
 *   <li>实体必须实现 {@link SaveIdentifiable} 且至少有一个 @SaveField 字段</li>
 *   <li>数据库注解（@Indexed/@Column/@ChildTable/@RelationTable）必须与 @SaveField 同用</li>
 *   <li>@ChildTable 只允许 Collection 字段；@RelationTable 元素必须实现 SaveIdentifiable
 *       且为扁平实体（自身不得再含 @ChildTable/@RelationTable，防装配环）</li>
 *   <li>@Indexed 不能用于 JSON 列与容器字段；@Column 参数与列类型匹配
 *       （length→STRING/TEXT、precision/scale→DECIMAL）</li>
 * </ul>
 */
public final class EntityMapping {

    /** 标量/JSON 列字段 */
    public record ScalarField(FieldMetadata meta, ColumnType columnType) {
    }

    /** @ChildTable 字段（容器拆子表） */
    public record ChildField(FieldMetadata meta, TableDef table, boolean jsonValue, Class<?> elementType) {
    }

    /** @RelationTable 字段（关联表挂接） */
    public record RelationField(FieldMetadata meta, TableDef table,
                                 String refTable, String refKeyColumn, Class<?> refEntity) {
    }

    private final Class<?> type;
    private final String tableName;
    private final String keyColumn;
    private final TableDef mainTable;
    private final List<ScalarField> scalars;
    private final List<ChildField> children;
    private final List<RelationField> relations;

    private EntityMapping(Class<?> type, String tableName, String keyColumn, TableDef mainTable,
                          List<ScalarField> scalars, List<ChildField> children,
                          List<RelationField> relations) {
        this.type = type;
        this.tableName = tableName;
        this.keyColumn = keyColumn;
        this.mainTable = mainTable;
        this.scalars = List.copyOf(scalars);
        this.children = List.copyOf(children);
        this.relations = List.copyOf(relations);
    }

    /**
     * 解析实体类构建映射（含全部 fail-fast 校验）。
     *
     * @param type      实体类
     * @param cache     元数据缓存（serialize 内核）
     * @param tableName 表名
     * @param keyColumn 主键列名
     * @return 映射模型
     * @throws DataException 任一校验失败
     */
    public static EntityMapping of(Class<?> type, MetadataCache cache,
                                   String tableName, String keyColumn) {
        if (!SaveIdentifiable.class.isAssignableFrom(type)) {
            throw new DataException("实体类 " + type.getName() + " 必须实现 SaveIdentifiable" +
                    "（saveKey() 提供存储主键）");
        }
        ClassMetadata classMeta = cache.get(type)
                .orElseThrow(() -> new DataException("实体类 " + type.getName() +
                        " 没有任何 @SaveField 字段，无法映射为表 " + tableName));
        validateNoOrphanDbAnnotations(type);

        List<ScalarField> scalars = new ArrayList<>();
        List<ChildField> children = new ArrayList<>();
        List<RelationField> relations = new ArrayList<>();
        List<ColumnDef> columns = new ArrayList<>();
        columns.add(ColumnDef.key(keyColumn));

        for (FieldMetadata meta : classMeta.fields()) {
            Field field = meta.field();
            ChildTable childDecl = field.getAnnotation(ChildTable.class);
            RelationTable relationDecl = field.getAnnotation(RelationTable.class);
            Indexed indexed = field.getAnnotation(Indexed.class);
            Column columnDecl = field.getAnnotation(Column.class);
            String fieldName = type.getSimpleName() + "." + field.getName();

            if (childDecl != null && relationDecl != null) {
                throw new DataException("字段 " + fieldName + " 同时标注了 @ChildTable 与 @RelationTable，只能选一");
            }
            if (childDecl != null) {
                if (indexed != null) {
                    throw new DataException("字段 " + fieldName + "：@Indexed 不能用于 @ChildTable 容器字段");
                }
                children.add(parseChild(fieldName, meta, tableName));
                continue;
            }
            if (relationDecl != null) {
                if (indexed != null) {
                    throw new DataException("字段 " + fieldName + "：@Indexed 不能用于 @RelationTable 容器字段");
                }
                relations.add(parseRelation(fieldName, meta, tableName, relationDecl));
                continue;
            }

            ColumnType columnType = TypeMapping.resolve(field, columnDecl);
            TypeMapping.validateColumn(columnDecl, columnType, fieldName);
            if (indexed != null && columnType == ColumnType.JSON) {
                throw new DataException("字段 " + fieldName + "：@Indexed 不能用于 JSON 列（无法建索引）");
            }
            scalars.add(new ScalarField(meta, columnType));
            ColumnDef def = ColumnDef.of(meta.alias(), columnType);
            if (columnDecl != null) {
                if (columnDecl.length() >= 0) {
                    def = def.length(columnDecl.length());
                }
                if (columnType == ColumnType.DECIMAL) {
                    def = def.decimal(columnDecl.precision(), columnDecl.scale());
                }
            }
            if (indexed != null) {
                def = def.withIndexed();
            }
            columns.add(def);
        }

        TableDef mainTable = new TableDef(tableName, keyColumn, columns);
        return new EntityMapping(type, tableName, keyColumn, mainTable, scalars, children, relations);
    }

    /** 扫描类层次全部字段：数据库注解必须与 @SaveField 同用 */
    private static void validateNoOrphanDbAnnotations(Class<?> type) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
                    continue;
                }
                boolean hasDbAnnotation = field.getAnnotation(Indexed.class) != null
                        || field.getAnnotation(Column.class) != null
                        || field.getAnnotation(ChildTable.class) != null
                        || field.getAnnotation(RelationTable.class) != null;
                boolean hasSaveField = field.getAnnotation(
                        io.github.JiangHu.jframe.serialize.annotation.SaveField.class) != null;
                if (hasDbAnnotation && !hasSaveField) {
                    throw new DataException("字段 " + current.getSimpleName() + "." + field.getName() +
                            " 标注了数据库注解但缺少 @SaveField——数据库注解只是列修饰符，必须与 @SaveField 同用");
                }
            }
            current = current.getSuperclass();
        }
    }

    /** 解析 @ChildTable 字段 */
    private static ChildField parseChild(String fieldName, FieldMetadata meta, String tableName) {
        Field field = meta.field();
        if (!Collection.class.isAssignableFrom(field.getType())) {
            throw new DataException("字段 " + fieldName + "：@ChildTable 只允许 Collection 字段（List/Set）");
        }
        Class<?> elementType = genericArgument(fieldName, field);
        boolean jsonValue = TypeMapping.resolve(field, null) == ColumnType.JSON
                && !elementType.equals(String.class) && !elementType.isEnum()
                && !elementType.equals(java.time.Instant.class)
                && !elementType.equals(java.util.UUID.class)
                && !Number.class.isAssignableFrom(elementType)
                && !elementType.equals(Boolean.class)
                && !elementType.isPrimitive();

        ColumnType valueType = jsonValue ? ColumnType.JSON : TypeMapping.resolve(elementType);
        List<ColumnDef> cols = new ArrayList<>();
        cols.add(ColumnDef.key("id"));
        cols.add(ColumnDef.of("parent_key", ColumnType.STRING).withIndexed().length(191));
        cols.add(ColumnDef.of("seq", ColumnType.INT));
        cols.add(ColumnDef.of("value", valueType));
        TableDef table = new TableDef(tableName + "_" + meta.alias(), "id", cols);
        return new ChildField(meta, table, jsonValue, elementType);
    }

    /** 解析 @RelationTable 字段 */
    private static RelationField parseRelation(String fieldName, FieldMetadata meta,
                                               String tableName, RelationTable decl) {
        Field field = meta.field();
        if (!Collection.class.isAssignableFrom(field.getType())) {
            throw new DataException("字段 " + fieldName + "：@RelationTable 只允许 Collection 字段（List/Set）");
        }
        Class<?> refEntity = decl.refEntity();
        if (!SaveIdentifiable.class.isAssignableFrom(refEntity)) {
            throw new DataException("字段 " + fieldName + "：@RelationTable 的 refEntity "
                    + refEntity.getName() + " 必须实现 SaveIdentifiable");
        }
        requireFlatRef(fieldName, refEntity);

        List<ColumnDef> cols = new ArrayList<>();
        cols.add(ColumnDef.key("id"));
        cols.add(ColumnDef.of("owner_key", ColumnType.STRING).withIndexed().length(191));
        cols.add(ColumnDef.of("ref_key", ColumnType.STRING).length(191));
        TableDef table = new TableDef(tableName + "_" + meta.alias(), "id", cols);
        return new RelationField(meta, table, decl.refTable(), decl.refKeyColumn(), refEntity);
    }

    /** ref 实体必须扁平（无 @ChildTable/@RelationTable，防装配环） */
    private static void requireFlatRef(String fieldName, Class<?> refEntity) {
        Class<?> current = refEntity;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getAnnotation(ChildTable.class) != null
                        || field.getAnnotation(RelationTable.class) != null) {
                    throw new DataException("字段 " + fieldName + "：refEntity " + refEntity.getName()
                            + " 含 @ChildTable/@RelationTable 字段（嵌套拆表不支持，防装配环）——" +
                            "ref 实体必须为扁平实体");
                }
            }
            current = current.getSuperclass();
        }
    }

    /** 提取 Collection 字段的泛型元素类型 */
    private static Class<?> genericArgument(String fieldName, Field field) {
        if (field.getGenericType() instanceof ParameterizedType pt
                && pt.getActualTypeArguments().length == 1
                && pt.getActualTypeArguments()[0] instanceof Class<?> elementClass) {
            return elementClass;
        }
        throw new DataException("字段 " + fieldName + "：@ChildTable/@RelationTable 容器必须携带泛型元素类型"
                + "（如 List<Achieve>），裸类型无法确定元素映射");
    }

    /** @return 实体类 */
    public Class<?> type() {
        return type;
    }

    /** @return 主表名 */
    public String tableName() {
        return tableName;
    }

    /** @return 主键列名 */
    public String keyColumn() {
        return keyColumn;
    }

    /** @return 主表定义 */
    public TableDef mainTable() {
        return mainTable;
    }

    /** @return 标量/JSON 列字段列表 */
    public List<ScalarField> scalars() {
        return scalars;
    }

    /** @return @ChildTable 字段列表 */
    public List<ChildField> children() {
        return children;
    }

    /** @return @RelationTable 字段列表 */
    public List<RelationField> relations() {
        return relations;
    }

    /**
     * 按字段名或别名查找标量字段（findBy 参数解析用）。
     *
     * @param name 字段名或别名
     * @return 字段 Optional
     */
    public Optional<ScalarField> scalarByName(String name) {
        for (ScalarField s : scalars) {
            if (s.meta().field().getName().equals(name) || s.meta().alias().equals(name)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
