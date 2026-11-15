package ru.lab.minidb;

import java.io.*;
import java.nio.file.*;
import java.util.*;
public final class IntegrationTest {
    private static int checks;
    private IntegrationTest() { }
    private interface Action { void run() throws Exception; }
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++;
    }
    private static void reject(Action action, String name) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IOException expected) { checks++; return; }
        throw new AssertionError("Ожидалась ошибка: " + name);
    }
    private static Set<List<Object>> rows(Engine.Result result) { return new HashSet<>(result.rows); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("minidb-tests-");
        try {
            functional(root.resolve("functional"));
            recovery(root.resolve("recovery"));
            diskFailure();
            System.out.println("PASS: " + checks + " проверок");
        } finally { deleteTree(root); }
    }
    private static void functional(Path dir) throws Exception {
        try (FileRepository repository = new FileRepository(dir)) {
            Engine e = new Engine(repository);
            reject(() -> new FileRepository(dir), "блокировка второго экземпляра");
            e.execute("CREATE TABLE owners (id INT PRIMARY KEY, name STRING, registered DATE, active BOOLEAN)");
            e.execute("CREATE TABLE cars (id INT PRIMARY KEY, brand STRING, price DOUBLE, available BOOLEAN, owner_id INT REFERENCES owners(id))");
            e.execute("INSERT INTO owners VALUES (1, 'Иван', '2024-02-29', TRUE), (2, 'Анна', '2025-01-01', FALSE)");
            e.execute("INSERT INTO cars VALUES (1, 'O''Brien; test, x', 25000.5, TRUE, 1), (2, 'BMW', 30000, FALSE, 2), (3, 'Kia', 18000, TRUE, NULL)");
            e.execute("INSERT INTO cars (id, brand) VALUES (4, 'NULL')");
            check(e.execute("SELECT * FROM cars").rows.size() == 4, "multi-row и частичный INSERT");
            check(e.execute("SELECT brand FROM cars WHERE id=1").rows.get(0).get(0).equals("O'Brien; test, x"), "escape кавычек");
            check(e.execute("SELECT price FROM cars WHERE id=4").rows.get(0).get(0) == null, "пропущенные значения NULL");
            check(e.execute("SELECT id FROM owners WHERE registered > '2024-12-31'").rows.size() == 1, "даты");
            check(e.execute("SELECT id FROM cars WHERE available = FALSE").rows.size() == 1, "boolean");
            reject(() -> e.execute("INSERT INTO cars VALUES (5, 'Good', 10, TRUE, 1), (1, 'Duplicate', 20, FALSE, 1)"), "дубликат внутри команды");
            check(e.execute("SELECT * FROM cars").rows.size() == 4, "откат всего INSERT");
            reject(() -> e.execute("INSERT INTO cars (id, owner_id) VALUES (6, 999)"), "внешний ключ");
            reject(() -> e.execute("INSERT INTO owners VALUES (3, 'Bad', '2025-02-29', TRUE)"), "невалидная дата");
            reject(() -> e.execute("INSERT INTO cars (id) VALUES (2147483648)"), "переполнение INT");
            reject(() -> e.execute("INSERT INTO cars (id, available) VALUES (5, 'TRUE')"), "строка вместо boolean");
            reject(() -> e.execute("INSERT INTO cars (id,price) VALUES (5, 1e999)"), "бесконечность");
            reject(() -> e.execute("INSERT INTO cars (brand) VALUES ('No key')"), "NULL PK");
            reject(() -> e.execute("UPDATE cars SET id = 1 WHERE id = 2"), "дубликат PK при UPDATE");
            reject(() -> e.execute("UPDATE cars SET owner_id=999"), "откат UPDATE");
            check(e.execute("SELECT * FROM cars WHERE owner_id=2").rows.size() == 1, "состояние после неуспешного UPDATE");
            reject(() -> e.execute("DELETE FROM owners WHERE id=1"), "RESTRICT DELETE");
            reject(() -> e.execute("UPDATE owners SET id=10 WHERE id=1"), "RESTRICT UPDATE PK");
            reject(() -> e.execute("DROP TABLE owners"), "RESTRICT DROP");
            e.execute("CREATE INDEX idx_price ON cars(price)");
            e.execute("CREATE INDEX idx_brand ON cars(brand)");
            e.execute("CREATE INDEX idx_date ON owners(registered)");
            e.execute("CREATE INDEX idx_bool ON cars(available)");
            e.execute("CREATE INDEX idx_owner ON cars(owner_id)");
            for (String operator : Arrays.asList("=", "!=", "<>", "<", "<=", ">", ">=")) {
                String sql = "SELECT * FROM cars WHERE price " + operator + " 25000.5";
                check(rows(e.execute(sql, true)).equals(rows(e.execute(sql, false))), "индекс vs scan " + operator);
            }
            for (String sql : Arrays.asList("SELECT * FROM cars WHERE brand='BMW'", "SELECT * FROM owners WHERE registered >= '2024-02-29'", "SELECT * FROM cars WHERE available=TRUE", "SELECT * FROM cars WHERE owner_id=2"))
                check(rows(e.execute(sql)).equals(rows(e.execute(sql, false))), "индексы всех типов");
            check(e.execute("SELECT * FROM cars WHERE price > 20000").examined == 2, "кандидаты индекса");
            check(e.execute("SELECT * FROM cars WHERE price=NULL").rows.isEmpty(), "SQL NULL");
            reject(() -> e.execute("CREATE INDEX idx_price ON owners(name)"), "уникальное имя индекса");
            e.execute("UPDATE cars SET price=15000, brand='BMW' WHERE id=1");
            check(e.execute("SELECT * FROM cars WHERE brand='BMW'").rows.size() == 2, "индекс после UPDATE");
            e.execute("UPDATE cars SET id=10 WHERE id=1");
            check(e.execute("SELECT id FROM cars WHERE price=15000").rows.get(0).get(0).equals(10), "индекс после смены PK");
            e.execute("DELETE FROM cars WHERE price=30000");
            check(e.execute("SELECT * FROM cars WHERE brand='BMW'").rows.size() == 1, "индекс после DELETE");
            e.execute("INSERT INTO cars (id, brand, price) VALUES (7, 'BMW', 15000)");
            check(e.execute("SELECT * FROM cars WHERE price=15000").rows.size() == 2, "индекс после INSERT");
            for (String sql : Arrays.asList("SELECT * FROM cars; DROP TABLE cars;", "SELECT absent FROM cars", "UPDATE cars SET missing=1", "SELECT * FROM cars WHERE brand='x", "INSERT INTO cars (id,id) VALUES (8,9)", "SELECT * FROM cars WHERE price > 0 AND id=1", "CREATE TABLE bad (x INT)", "CREATE TABLE bad (x INT PRIMARY KEY, y INT PRIMARY KEY)", "CREATE TABLE bad (x INT PRIMARY KEY, y STRING REFERENCES owners(id))"))
                reject(() -> e.execute(sql), "ошибка синтаксиса/схемы");
            e.execute("CREATE TABLE scratch (id INT PRIMARY KEY)");
            e.execute("INSERT INTO scratch VALUES (1),(2)");
            check(e.execute("UPDATE scratch SET id=3 WHERE id=1").affected == 1, "affected UPDATE");
            check(e.execute("DELETE FROM scratch").affected == 2, "DELETE без WHERE");
            e.execute("DROP TABLE scratch");
            reject(() -> e.execute("SELECT * FROM scratch"), "DROP TABLE");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            TablePrinter.print(e.execute("SELECT brand,price FROM cars"), new PrintStream(output, true, "UTF-8"));
            check(output.toString("UTF-8").contains("brand") && output.toString("UTF-8").contains("|"), "табличный вывод");
        }
        try (FileRepository repository = new FileRepository(dir)) {
            Engine e = new Engine(repository);
            Engine.Result r = e.execute("SELECT * FROM cars WHERE price=15000");
            check(r.rows.size() == 2 && r.plan.startsWith("INDEX"), "перезапуск: данные и индексы");
        }
    }
    private static void recovery(Path dir) throws Exception {
        try (FileRepository r = new FileRepository(dir)) {
            Engine e = new Engine(r);
            e.execute("CREATE TABLE t (id INT PRIMARY KEY)");
            e.execute("INSERT INTO t VALUES (1)");
        }
        Files.write(dir.resolve("snapshot.tmp"), new byte[]{1, 2});
        try (FileRepository r = new FileRepository(dir)) {
            check(new Engine(r).execute("SELECT * FROM t").rows.size() == 1, "незавершённый tmp игнорируется");
        }
        Files.write(dir.resolve("snapshot-0.bin"), new byte[]{9});
        try (FileRepository r = new FileRepository(dir)) {
            Engine e = new Engine(r);
            check(e.execute("SELECT * FROM t").rows.isEmpty(), "fallback на предыдущее поколение");
            e.execute("INSERT INTO t VALUES (2)");
        }
        String javaExe = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(javaExe, "-cp", System.getProperty("java.class.path"), CrashWriter.class.getName(), dir.toString()).inheritIO().start();
        check(child.waitFor() == 23, "аварийная остановка JVM без close");
        try (FileRepository r = new FileRepository(dir)) {
            check(new Engine(r).execute("SELECT * FROM t").rows.size() == 2, "подтверждённая запись переживает halt");
        }
        Files.write(dir.resolve("snapshot-0.bin"), new byte[]{9});
        Files.write(dir.resolve("snapshot-1.bin"), new byte[]{9});
        try (FileRepository r = new FileRepository(dir)) { reject(() -> new Engine(r), "оба снимка повреждены"); }
    }
    private static void diskFailure() throws Exception {
        DatabaseRepository bad = new DatabaseRepository() {
            public Model.Database load() { return new Model.Database(); }
            public void save(Model.Database d) throws IOException { throw new IOException("Simulated disk failure"); }
            public void close() { }
        };
        Engine e = new Engine(bad);
        reject(() -> e.execute("CREATE TABLE t (id INT PRIMARY KEY)"), "ошибка диска");
        reject(() -> e.execute("SELECT * FROM t"), "нет публикации неподтверждённого состояния");
    }
    static void deleteTree(Path root) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            Path[] sorted = paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
            for (Path p : sorted) Files.delete(p);
        }
    }
}
