package io.github.JiangHu.jframe.db.mapping;

import io.github.JiangHu.jframe.db.annotation.ChildTable;
import io.github.JiangHu.jframe.db.annotation.Column;
import io.github.JiangHu.jframe.db.annotation.Indexed;
import io.github.JiangHu.jframe.db.annotation.RelationTable;
import io.github.JiangHu.jframe.db.spi.ColumnDef;
import io.github.JiangHu.jframe.db.spi.ColumnType;
import io.github.JiangHu.jframe.db.spi.TableDef;
import io.github.JiangHu.jframe.serialize.SaveIdentifiable;
import io.github.JiangHu.jframe.serialize.annotation.SaveField;
import io.github.JiangHu.jframe.serialize.core.meta.MetadataCache;
import io.github.JiangHu.jframe.serialize.exception.DataException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link EntityMapping} 解析与 fail-fast 校验测试。
 */
@DisplayName("实体映射（EntityMapping）测试")
class EntityMappingTest {

    private final MetadataCache cache = new MetadataCache();

    // ========== 夹具 ==========

    static class Title implements SaveIdentifiable {
        @SaveField(required = true)
        String id;

        @SaveField
        String display;

        public Title() {
        }

        @Override
        public String saveKey() {
            return id;
        }
    }

    static class Achieve {
        @SaveField
        String code;

        @SaveField
        int score;

        public Achieve() {
        }

        public Achieve(String code, int score) {
            this.code = code;
            this.score = score;
        }
    }

    static class FullPlayer implements SaveIdentifiable {
        @SaveField(required = true)
        String uuid;

        @SaveField @Indexed @Column(length = 50)
        String name;

        @SaveField @Column(type = ColumnType.TEXT)
        String bio;

        @SaveField @Column(precision = 10, scale = 2)
        BigDecimal balance;

        @SaveField
        int level;

        @SaveField
        Instant lastLogin;

        @SaveField
        UUID token;

        @SaveField
        List<String> tags;                    // JSON 列（未拆表容器默认）

        @SaveField @ChildTable
        List<String> mails;                   // 标量元素子表 → value STRING

        @SaveField @ChildTable
        List<Achieve> achieves;               // 复杂元素子表 → value JSON

        @SaveField @RelationTable(refTable = "titles", refEntity = Title.class)
        List<Title> titles;

        public FullPlayer() {
        }

        @Override
        public String saveKey() {
            return uuid;
        }
    }

    // ========== 主表解析 ==========

    @Test
    @DisplayName("主表定义：主键列 + 标量列类型 + @Column 参数 + 索引")
    void mainTableDef() {
        EntityMapping mapping = EntityMapping.of(FullPlayer.class, cache, "players", "uuid");

        TableDef main = mapping.mainTable();
        assertEquals("players", main.name());
        assertEquals("uuid", main.keyColumn());
        assertEquals("uuid", main.columns().get(0).name());
        assertEquals(ColumnType.STRING, main.column("uuid").orElseThrow().type());

        // @Indexed + @Column(length=50)
        ColumnDef name = main.column("name").orElseThrow();
        assertEquals(ColumnType.STRING, name.type());
        assertTrue(name.indexed());
        assertEquals(50, name.length());

        // @Column(type=TEXT)
        assertEquals(ColumnType.TEXT, main.column("bio").orElseThrow().type());

        // BigDecimal → DECIMAL(10,2)
        ColumnDef balance = main.column("balance").orElseThrow();
        assertEquals(ColumnType.DECIMAL, balance.type());
        assertEquals(10, balance.precision());
        assertEquals(2, balance.scale());

        assertEquals(ColumnType.INT, main.column("level").orElseThrow().type());
        assertEquals(ColumnType.LONG, main.column("lastLogin").orElseThrow().type());
        assertEquals(ColumnType.STRING, main.column("token").orElseThrow().type());
        assertEquals(ColumnType.JSON, main.column("tags").orElseThrow().type());
    }

    @Test
    @DisplayName("子表/关联表定义：表名、列布局、元素类型")
    void childAndRelationDefs() {
        EntityMapping mapping = EntityMapping.of(FullPlayer.class, cache, "players", "uuid");

        assertEquals(2, mapping.children().size());
        EntityMapping.ChildField mails = mapping.children().get(0);
        assertEquals("players_mails", mails.table().name());
        assertEquals(ColumnType.STRING, mails.table().column("value").orElseThrow().type());
        assertTrue(mails.table().column("parent_key").orElseThrow().indexed());
        assertFalse(mails.jsonValue());

        EntityMapping.ChildField achievesRef = mapping.children().get(1);
        assertEquals("players_achieves", achievesRef.table().name());
        assertEquals(ColumnType.JSON, achievesRef.table().column("value").orElseThrow().type());
        assertTrue(achievesRef.jsonValue());
        assertEquals(Achieve.class, achievesRef.elementType());

        assertEquals(1, mapping.relations().size());
        EntityMapping.RelationField titles = mapping.relations().get(0);
        assertEquals("players_titles", titles.table().name());
        assertEquals("titles", titles.refTable());
        assertEquals("id", titles.refKeyColumn());
        assertTrue(titles.table().column("owner_key").orElseThrow().indexed());
    }

    // ========== fail-fast 校验 ==========

    @Test
    @DisplayName("未实现 SaveIdentifiable → 报错")
    void requireIdentifiable() {
        class NoIdentifiable {
            @SaveField
            String a;
        }
        assertThrows(DataException.class,
                () -> EntityMapping.of(NoIdentifiable.class, cache, "t", "id"));
    }

    @Test
    @DisplayName("无 @SaveField 字段 → 报错")
    void requireAtLeastOneField() {
        class Empty implements SaveIdentifiable {
            @Override
            public String saveKey() {
                return "x";
            }
        }
        assertThrows(DataException.class,
                () -> EntityMapping.of(Empty.class, cache, "t", "id"));
    }

    @Test
    @DisplayName("数据库注解缺少 @SaveField → 报错")
    void orphanDbAnnotation() {
        class Orphan implements SaveIdentifiable {
            @Indexed
            String forgotSaveField;

            @SaveField
            String ok;

            public Orphan() {
            }

            @Override
            public String saveKey() {
                return ok;
            }
        }
        DataException ex = assertThrows(DataException.class,
                () -> EntityMapping.of(Orphan.class, cache, "t", "id"));
        assertTrue(ex.getMessage().contains("forgotSaveField"));
    }

    @Test
    @DisplayName("@Indexed 用于 JSON 列 → 报错")
    void indexedOnJsonColumn() {
        class BadIndex implements SaveIdentifiable {
            @SaveField @Indexed
            List<String> tags;

            public BadIndex() {
            }

            @Override
            public String saveKey() {
                return "x";
            }
        }
        assertThrows(DataException.class,
                () -> EntityMapping.of(BadIndex.class, cache, "t", "id"));
    }

    @Test
    @DisplayName("@Column(length) 标在非 STRING/TEXT 列 → 报错")
    void lengthOnNonString() {
        class BadLength implements SaveIdentifiable {
            @SaveField @Column(length = 10)
            int level;

            public BadLength() {
            }

            @Override
            public String saveKey() {
                return "x";
            }
        }
        assertThrows(DataException.class,
                () -> EntityMapping.of(BadLength.class, cache, "t", "id"));
    }

    @Test
    @DisplayName("@ChildTable 用于非 Collection 字段 → 报错")
    void childOnNonCollection() {
        class BadChild implements SaveIdentifiable {
            @SaveField @ChildTable
            String single;

            public BadChild() {
            }

            @Override
            public String saveKey() {
                return "x";
            }
        }
        assertThrows(DataException.class,
                () -> EntityMapping.of(BadChild.class, cache, "t", "id"));
    }

    @Test
    @DisplayName("@RelationTable 的 refEntity 嵌套拆表 → 报错（防装配环）")
    void nestedRefRejected() {
        // FullPlayer 自身含 @ChildTable，作为 refEntity 必须被拒
        assertThrows(DataException.class, () -> EntityMapping.of(HolderOfFullPlayer.class,
                cache, "holders", "id"));
    }

    static class HolderOfFullPlayer implements SaveIdentifiable {
        @SaveField
        String id;

        @SaveField @RelationTable(refTable = "players", refEntity = FullPlayer.class)
        List<FullPlayer> players;

        public HolderOfFullPlayer() {
        }

        @Override
        public String saveKey() {
            return id;
        }
    }

    @Test
    @DisplayName("裸容器（无泛型）→ 报错")
    void rawCollectionRejected() {
        @SuppressWarnings("rawtypes")
        class Raw implements SaveIdentifiable {
            @SaveField @ChildTable
            List rawList;

            public Raw() {
            }

            @Override
            public String saveKey() {
                return "x";
            }
        }
        assertThrows(DataException.class,
                () -> EntityMapping.of(Raw.class, cache, "t", "id"));
    }
}
