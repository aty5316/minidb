package ru.lab.minidb;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.*;
public final class Model {
    private Model() { }
    public enum Type {
        INT, DOUBLE, STRING, DATE, BOOLEAN;
        public Object convert(Sql.Literal literal) {
            if (!literal.quoted && literal.text.equalsIgnoreCase("NULL")) return null;
            String s = literal.text;
            try {
                switch (this) {
                    case STRING: if (literal.quoted) return s; break;
                    case DATE: if (literal.quoted) return LocalDate.parse(s); break;
                    case INT: if (!literal.quoted && s.matches("[+-]?[0-9]+")) return Integer.valueOf(s); break;
                    case DOUBLE:
                        if (!literal.quoted && s.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) {
                            double d = Double.parseDouble(s);
                            if (Double.isFinite(d)) return d == 0.0 ? 0.0 : d;
                        }
                        break;
                    case BOOLEAN:
                        if (!literal.quoted && (s.equalsIgnoreCase("TRUE") || s.equalsIgnoreCase("FALSE")))
                            return Boolean.valueOf(s);
                        break;
                    default: break;
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Некорректное значение " + this + ": " + s, e);
            }
            throw new IllegalArgumentException("Ожидается " + this + ", получено: " + s);
        }
    }
    public static final class Column implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String name;
        public final Type type;
        public final boolean primary;
        public final String refTable, refColumn;
        public Column(String name, Type type, boolean primary, String refTable, String refColumn) {
            this.name = name; this.type = type; this.primary = primary;
            this.refTable = refTable; this.refColumn = refColumn;
        }
    }
    public static final class Database implements Serializable {
        private static final long serialVersionUID = 1L;
        public long revision;
        public final Map<String, Table> tables = new LinkedHashMap<>();
        public Table table(String name) {
            Table t = tables.get(name);
            if (t == null) throw new IllegalArgumentException("Нет таблицы: " + name);
            return t;
        }
    }
    public static final class Table implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String name;
        public final List<Column> columns;
        public final int pk;
        public LinkedHashMap<Object, List<Object>> rows = new LinkedHashMap<>();
        public final Map<String, Index> indexes = new LinkedHashMap<>();
        public Table(String name, List<Column> columns) {
            this.name = name; this.columns = new ArrayList<>(columns);
            int found = -1;
            Set<String> names = new HashSet<>();
            for (int i = 0; i < columns.size(); i++) {
                Column c = columns.get(i);
                if (!names.add(c.name)) throw new IllegalArgumentException("Повтор столбца: " + c.name);
                if (c.primary) {
                    if (found != -1) throw new IllegalArgumentException("Разрешён один PRIMARY KEY");
                    found = i;
                }
            }
            if (found == -1) throw new IllegalArgumentException("Требуется PRIMARY KEY");
            pk = found;
        }
        public int column(String name) {
            for (int i = 0; i < columns.size(); i++) if (columns.get(i).name.equals(name)) return i;
            throw new IllegalArgumentException("Нет столбца: " + name);
        }
        public void rebuildIndexes() {
            for (Index ix : indexes.values()) {
                ix.entries.clear();
                for (Map.Entry<Object, List<Object>> r : rows.entrySet()) {
                    Object value = r.getValue().get(ix.column);
                    if (value != null) ix.entries.computeIfAbsent(value, k -> new LinkedHashSet<>()).add(r.getKey());
                }
            }
        }
    }
    public static final class Index implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String name;
        public final int column;
        public final NavigableMap<Object, Set<Object>> entries = new TreeMap<>();
        public Index(String name, int column) { this.name = name; this.column = column; }
        public Collection<Object> lookup(String op, Object value) {
            if (value == null) return Collections.emptyList();
            if (op.equals("=")) return entries.getOrDefault(value, Collections.emptySet());
            NavigableMap<Object, Set<Object>> range;
            switch (op) {
                case ">": range = entries.tailMap(value, false); break;
                case ">=": range = entries.tailMap(value, true); break;
                case "<": range = entries.headMap(value, false); break;
                case "<=": range = entries.headMap(value, true); break;
                default: throw new IllegalArgumentException("Оператор не поддержан индексом");
            }
            List<Object> keys = new ArrayList<>();
            for (Set<Object> bucket : range.values()) keys.addAll(bucket);
            return keys;
        }
    }
}
