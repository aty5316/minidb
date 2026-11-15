package ru.lab.minidb;

import java.io.IOException;
import java.util.*;
public final class Engine {
    private final DatabaseRepository repository;
    private Model.Database database;
    public Engine(DatabaseRepository repository) throws IOException {
        this.repository = repository; database = repository.load(); validate(database);
    }
    public static final class Result {
        public final List<String> columns = new ArrayList<>();
        public final List<List<Object>> rows = new ArrayList<>();
        public String plan = "";
        public int affected, examined;
    }
    public Result execute(String sql) throws IOException { return execute(sql, true); }
    public Result execute(String sql, boolean useIndexes) throws IOException {
        Sql.Command c = Sql.parse(sql);
        if (c.kind.equals("SELECT")) return select(c, useIndexes);
        Model.Database next = FileRepository.decode(FileRepository.encode(database));
        Result result = mutate(next, c);
        validate(next);
        for (Model.Table t : next.tables.values()) t.rebuildIndexes();
        next.revision = database.revision + 1;
        repository.save(next);
        database = next;
        return result;
    }
    private Result mutate(Model.Database db, Sql.Command c) {
        Result result = new Result();
        if (c.kind.equals("CREATE TABLE")) {
            if (db.tables.containsKey(c.table)) throw new IllegalArgumentException("Таблица уже существует");
            db.tables.put(c.table, new Model.Table(c.table, c.columns)); return result;
        }
        Model.Table t = db.table(c.table);
        switch (c.kind) {
            case "DROP TABLE": db.tables.remove(c.table); break;
            case "CREATE INDEX":
                for (Model.Table other : db.tables.values()) if (other.indexes.containsKey(c.index))
                    throw new IllegalArgumentException("Имя индекса уже занято");
                t.indexes.put(c.index, new Model.Index(c.index, t.column(c.indexColumn))); break;
            case "INSERT":
                List<Integer> positions = positions(t, c.names);
                for (List<Sql.Literal> tuple : c.tuples) {
                    if (tuple.size() != positions.size()) throw new IllegalArgumentException("Число значений не совпадает с числом столбцов");
                    List<Object> row = new ArrayList<>(Collections.nCopies(t.columns.size(), null));
                    for (int i = 0; i < tuple.size(); i++) {
                        int col = positions.get(i); row.set(col, t.columns.get(col).type.convert(tuple.get(i)));
                    }
                    putUnique(t.rows, row.get(t.pk), row); result.affected++;
                }
                break;
            case "UPDATE":
                Map<Integer, Object> changes = new LinkedHashMap<>();
                for (Map.Entry<String, Sql.Literal> entry : c.changes.entrySet()) {
                    int col = t.column(entry.getKey()); changes.put(col, t.columns.get(col).type.convert(entry.getValue()));
                }
                Predicate update = new Predicate(t, c.where);
                LinkedHashMap<Object, List<Object>> replaced = new LinkedHashMap<>();
                for (List<Object> row : t.rows.values()) {
                    if (update.test(row)) {
                        for (Map.Entry<Integer, Object> e : changes.entrySet()) row.set(e.getKey(), e.getValue());
                        result.affected++;
                    }
                    putUnique(replaced, row.get(t.pk), row);
                }
                t.rows = replaced; break;
            case "DELETE":
                Predicate delete = new Predicate(t, c.where);
                Iterator<List<Object>> it = t.rows.values().iterator();
                while (it.hasNext()) if (delete.test(it.next())) { it.remove(); result.affected++; }
                break;
            default: throw new IllegalArgumentException("Неизвестная операция");
        }
        return result;
    }
    private static void putUnique(Map<Object, List<Object>> rows, Object key, List<Object> row) {
        if (key == null) throw new IllegalArgumentException("PRIMARY KEY не может быть NULL");
        if (rows.containsKey(key)) throw new IllegalArgumentException("Повтор PRIMARY KEY: " + key);
        rows.put(key, row);
    }
    private static List<Integer> positions(Model.Table t, List<String> names) {
        List<Integer> positions = new ArrayList<>();
        if (names.isEmpty()) for (int i = 0; i < t.columns.size(); i++) positions.add(i);
        else for (String name : names) positions.add(t.column(name));
        return positions;
    }
    private Result select(Sql.Command c, boolean useIndexes) {
        Model.Table t = database.table(c.table);
        List<Integer> projection = positions(t, c.names);
        Predicate predicate = new Predicate(t, c.where);
        Result result = new Result();
        for (int col : projection) result.columns.add(t.columns.get(col).name);
        Collection<Object> keys = t.rows.keySet(); result.plan = "FULL SCAN";
        if (useIndexes && c.where != null && !c.where.op.equals("!=") && !c.where.op.equals("<>")) {
            for (Model.Index ix : t.indexes.values()) if (ix.column == predicate.column) {
                keys = ix.lookup(c.where.op, predicate.value); result.plan = "INDEX " + ix.name; break;
            }
        }
        for (Object key : keys) {
            List<Object> row = t.rows.get(key); result.examined++;
            if (predicate.test(row)) {
                List<Object> selected = new ArrayList<>();
                for (int col : projection) selected.add(row.get(col));
                result.rows.add(selected);
            }
        }
        return result;
    }
    private static void validate(Model.Database db) {
        for (Model.Table t : db.tables.values()) for (int col = 0; col < t.columns.size(); col++) {
            Model.Column c = t.columns.get(col);
            if (c.refTable == null) continue;
            Model.Table parent = db.table(c.refTable);
            int parentCol = parent.column(c.refColumn);
            if (parentCol != parent.pk || parent.columns.get(parentCol).type != c.type)
                throw new IllegalArgumentException("REFERENCES требует PRIMARY KEY того же типа");
            for (List<Object> row : t.rows.values()) {
                Object value = row.get(col);
                if (value != null && !parent.rows.containsKey(value))
                    throw new IllegalArgumentException("Нарушен внешний ключ: " + t.name + "." + c.name + " = " + value);
            }
        }
    }
    private static final class Predicate {
        final int column;
        final Object value;
        final String op;
        Predicate(Model.Table table, Sql.Condition c) {
            column = c == null ? -1 : table.column(c.column);
            op = c == null ? "" : c.op;
            value = c == null ? null : table.columns.get(column).type.convert(c.value);
        }
        @SuppressWarnings("unchecked")
        boolean test(List<Object> row) {
            if (column == -1) return true;
            Object left = row.get(column);
            if (left == null || value == null) return false;
            int cmp = ((Comparable<Object>) left).compareTo(value);
            switch (op) {
                case "=": return cmp == 0;
                case "!=": case "<>": return cmp != 0;
                case ">": return cmp > 0;
                case ">=": return cmp >= 0;
                case "<": return cmp < 0;
                case "<=": return cmp <= 0;
                default: throw new IllegalArgumentException("Неизвестный оператор");
            }
        }
    }
}
