package io.github.JiangHu.jframe.db.support;

import io.github.JiangHu.jframe.db.spi.ColumnDef;
import io.github.JiangHu.jframe.db.spi.RelationalStore;
import io.github.JiangHu.jframe.db.spi.RelationalTable;
import io.github.JiangHu.jframe.db.spi.Row;
import io.github.JiangHu.jframe.db.spi.TableDef;
import io.github.JiangHu.jframe.db.spi.TransactionalStore;
import io.github.JiangHu.jframe.serialize.exception.DataException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 内存版 RelationalStore 假实现 — 单元测试与 jframe_example 演示用
 * （不连任何真实数据库，开箱即跑）。
 * <p>
 * 实现说明：按 TableDef 建表/补列（记录列集合）；行以
 * {@code 主键 → 有序键值对} 存于 ConcurrentHashMap；查询为线性扫描
 * （内存数据量小，无需索引结构）；实现 {@link TransactionalStore}
 * （内存操作原子性足够，事务为直通执行）。
 */
public final class InMemoryRelationalStore implements RelationalStore, TransactionalStore {

    /** 表名 → 表实例 */
    private final Map<String, MemTable> tables = new ConcurrentHashMap<>();

    @Override
    public RelationalTable ensureTable(TableDef def) {
        MemTable table = tables.computeIfAbsent(def.name(), n -> new MemTable(def));
        table.ensureColumns(def);
        return table;
    }

    @Override
    public <R> R inTransaction(Function<RelationalStore, R> work) {
        // 内存实现：操作均为单线程安全的 Map 写入，直通执行
        return work.apply(this);
    }

    /** 单张内存表 */
    private static final class MemTable implements RelationalTable {

        private final TableDef def;

        /** 主键 → 行（有序键值对） */
        private final Map<String, LinkedHashMap<String, Object>> rows = new ConcurrentHashMap<>();

        /** 当前列集合（补列用） */
        private volatile List<ColumnDef> columns;

        MemTable(TableDef def) {
            this.def = def;
            this.columns = def.columns();
        }

        /** 幂等补列（列名对比；已存在列定义以建表时为准，不修改） */
        void ensureColumns(TableDef def) {
            synchronized (this) {
                for (ColumnDef wanted : def.columns()) {
                    boolean exists = columns.stream().anyMatch(c -> c.name().equals(wanted.name()));
                    if (!exists) {
                        List<ColumnDef> next = new ArrayList<>(columns);
                        next.add(wanted);
                        columns = next;
                    }
                }
            }
        }

        TableDef def() {
            return def;
        }

        /** 列集合快照（测试用） */
        List<ColumnDef> columnSnapshot() {
            return List.copyOf(columns);
        }

        @Override
        public Row get(String key) {
            LinkedHashMap<String, Object> values = rows.get(key);
            return values == null ? null : new Row(def.keyColumn(), values);
        }

        @Override
        public void upsert(Row row) {
            Objects.requireNonNull(row, "row");
            String key = row.key();
            if (key == null) {
                throw new DataException("upsert 行缺少主键列 " + def.keyColumn() + "（表 " + def.name() + "）");
            }
            rows.put(key, row.copyValues());
        }

        @Override
        public boolean delete(String key) {
            return rows.remove(key) != null;
        }

        @Override
        public boolean exists(String key) {
            return rows.containsKey(key);
        }

        @Override
        public long count() {
            return rows.size();
        }

        @Override
        public void forEach(int batchSize, Consumer<List<Row>> batch) {
            if (batchSize <= 0) {
                throw new IllegalArgumentException("batchSize 必须为正数");
            }
            List<Row> buffer = new ArrayList<>(batchSize);
            for (LinkedHashMap<String, Object> values : rows.values()) {
                buffer.add(new Row(def.keyColumn(), values));
                if (buffer.size() >= batchSize) {
                    batch.accept(List.copyOf(buffer));
                    buffer.clear();
                }
            }
            if (!buffer.isEmpty()) {
                batch.accept(List.copyOf(buffer));
            }
        }

        @Override
        public List<Row> getBatch(Collection<String> keys) {
            List<Row> result = new ArrayList<>();
            for (String key : keys) {
                Row row = get(key);
                if (row != null) {
                    result.add(row);
                }
            }
            return result;
        }

        @Override
        public List<Row> findBy(String column, Object value) {
            List<Row> result = new ArrayList<>();
            for (LinkedHashMap<String, Object> values : rows.values()) {
                if (Objects.equals(values.get(column), value)) {
                    result.add(new Row(def.keyColumn(), values));
                }
            }
            result.sort(Comparator.comparing(Row::key));
            return result;
        }

        @Override
        public List<Row> findByIn(String column, Collection<?> values) {
            List<Row> result = new ArrayList<>();
            for (LinkedHashMap<String, Object> row : rows.values()) {
                for (Object v : values) {
                    if (Objects.equals(row.get(column), v)) {
                        result.add(new Row(def.keyColumn(), row));
                        break;
                    }
                }
            }
            result.sort(Comparator.comparing(Row::key));
            return result;
        }
    }
}
