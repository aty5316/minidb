package ru.lab.minidb;

import java.util.*;
public final class Sql {
    private Sql() { }
    public static final class Literal {
        public final String text;
        public final boolean quoted;
        public Literal(String text, boolean quoted) { this.text = text; this.quoted = quoted; }
    }
    public static final class Condition {
        public String column, op;
        public Literal value;
    }
    public static final class Command {
        public String kind, table, index, indexColumn;
        public List<String> names = new ArrayList<>();
        public List<List<Literal>> tuples = new ArrayList<>();
        public Map<String, Literal> changes = new LinkedHashMap<>();
        public List<Model.Column> columns = new ArrayList<>();
        public Condition where;
    }
    public static Command parse(String sql) { return new Parser(sql).parse(); }

    private static final class Parser {
        private final List<Literal> tokens = new ArrayList<>();
        private int pos;
        Parser(String sql) {
            for (int i = 0; i < sql.length();) {
                char c = sql.charAt(i);
                if (Character.isWhitespace(c)) { i++; continue; }
                if (c == '\'') {
                    StringBuilder b = new StringBuilder(); boolean closed = false; i++;
                    while (i < sql.length()) {
                        char ch = sql.charAt(i++);
                        if (ch == '\'') {
                            if (i < sql.length() && sql.charAt(i) == '\'') { b.append('\''); i++; }
                            else { closed = true; break; }
                        } else b.append(ch);
                    }
                    if (!closed) throw error("Незакрытая строка");
                    tokens.add(new Literal(b.toString(), true));
                } else if ("(),;*=<>!".indexOf(c) >= 0) {
                    String op = String.valueOf(c); i++;
                    if (i < sql.length() && (("<>!".indexOf(c) >= 0 && sql.charAt(i) == '=') ||
                            (c == '<' && sql.charAt(i) == '>'))) op += sql.charAt(i++);
                    tokens.add(new Literal(op, false));
                } else {
                    int start = i++;
                    while (i < sql.length() && !Character.isWhitespace(sql.charAt(i)) &&
                            "(),;*=<>!'".indexOf(sql.charAt(i)) < 0) i++;
                    tokens.add(new Literal(sql.substring(start, i), false));
                }
            }
        }
        IllegalArgumentException error(String s) { return new IllegalArgumentException(s + " (токен " + (pos + 1) + ")"); }
        boolean eat(String s) {
            if (pos < tokens.size() && !tokens.get(pos).quoted && tokens.get(pos).text.equalsIgnoreCase(s)) {
                pos++; return true;
            }
            return false;
        }
        void need(String s) { if (!eat(s)) throw error("Ожидается " + s); }
        Literal next() { if (pos >= tokens.size()) throw error("Неожиданный конец команды"); return tokens.get(pos++); }
        String id() {
            Literal t = next();
            if (t.quoted || !t.text.matches("[a-zA-Z_][a-zA-Z_0-9]*")) throw error("Ожидается идентификатор");
            return t.text.toLowerCase(Locale.ROOT);
        }
        List<String> names() {
            List<String> result = new ArrayList<>();
            do { String n = id(); if (result.contains(n)) throw error("Повтор столбца: " + n); result.add(n); } while (eat(","));
            return result;
        }
        Condition where() {
            if (!eat("WHERE")) return null;
            Condition w = new Condition(); w.column = id();
            Literal op = next(); w.op = op.text;
            if (op.quoted || !Arrays.asList("=", "!=", "<>", ">", ">=", "<", "<=").contains(w.op)) throw error("Некорректный оператор");
            w.value = next(); return w;
        }
        Command parse() {
            Command c = new Command();
            if (eat("SELECT")) {
                c.kind = "SELECT";
                if (!eat("*")) c.names = names();
                need("FROM"); c.table = id(); c.where = where();
            } else if (eat("INSERT")) {
                c.kind = "INSERT"; need("INTO"); c.table = id();
                if (eat("(")) { c.names = names(); need(")"); }
                need("VALUES");
                do {
                    need("("); List<Literal> tuple = new ArrayList<>();
                    do { tuple.add(next()); } while (eat(","));
                    need(")"); c.tuples.add(tuple);
                } while (eat(","));
            } else if (eat("UPDATE")) {
                c.kind = "UPDATE"; c.table = id(); need("SET");
                do {
                    String col = id(); need("=");
                    if (c.changes.put(col, next()) != null) throw error("Повтор присваивания: " + col);
                } while (eat(","));
                c.where = where();
            } else if (eat("DELETE")) {
                c.kind = "DELETE"; need("FROM"); c.table = id(); c.where = where();
            } else if (eat("CREATE")) {
                if (eat("INDEX")) {
                    c.kind = "CREATE INDEX"; c.index = id(); need("ON"); c.table = id();
                    need("("); c.indexColumn = id(); need(")");
                } else {
                    need("TABLE"); c.kind = "CREATE TABLE"; c.table = id(); need("(");
                    do {
                        String name = id(), typeName = id(); Model.Type type;
                        try { type = Model.Type.valueOf(typeName.toUpperCase(Locale.ROOT)); }
                        catch (IllegalArgumentException e) { throw error("Неизвестный тип: " + typeName); }
                        boolean primary = false; String rt = null, rc = null;
                        if (eat("PRIMARY")) { need("KEY"); primary = true; }
                        if (eat("REFERENCES")) { rt = id(); need("("); rc = id(); need(")"); }
                        c.columns.add(new Model.Column(name, type, primary, rt, rc));
                    } while (eat(","));
                    need(")");
                }
            } else if (eat("DROP")) {
                need("TABLE"); c.kind = "DROP TABLE"; c.table = id();
            } else throw error("Неизвестная команда");
            eat(";");
            if (pos != tokens.size()) throw error("Лишний текст после команды");
            return c;
        }
    }
}
