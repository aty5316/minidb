package ru.lab.minidb;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
public final class Main {
    private Main() { }
    public static void main(String[] args) {
        Path dir = Paths.get(args.length > 0 ? args[0] : "data");
        try (FileRepository repo = new FileRepository(dir)) {
            Engine engine = new Engine(repo);
            if (args.length > 1) {
                try (Reader reader = Files.newBufferedReader(Paths.get(args[1]), StandardCharsets.UTF_8)) {
                    run(reader, engine, false);
                }
            } else {
                System.out.println("MiniDB. SQL завершается ;  Выход: EXIT;  Справка: HELP;");
                run(new InputStreamReader(System.in, StandardCharsets.UTF_8), engine, true);
            }
        } catch (Exception e) { System.err.println("Ошибка: " + e.getMessage()); System.exit(1); }
    }
    private static void run(Reader source, Engine engine, boolean interactive) throws IOException {
        PushbackReader reader = new PushbackReader(source, 1);
        StringBuilder buffer = new StringBuilder(); boolean quoted = false;
        if (interactive) System.out.print("sql> ");
        int ch;
        while ((ch = reader.read()) != -1) {
            char c = (char) ch;
            if (c == '\'') {
                if (quoted) {
                    int next = reader.read();
                    if (next == '\'') { buffer.append("''"); continue; }
                    quoted = false; if (next != -1) reader.unread(next);
                } else quoted = true;
            }
            if (c == ';' && !quoted) {
                String sql = buffer.toString().trim(); buffer.setLength(0);
                if (sql.equalsIgnoreCase("EXIT")) return;
                if (sql.equalsIgnoreCase("HELP")) System.out.println("INSERT, SELECT, UPDATE, DELETE, CREATE TABLE, DROP TABLE, CREATE INDEX. Примеры: examples/demo.sql");
                else if (!sql.isEmpty()) {
                    try { TablePrinter.print(engine.execute(sql), System.out); }
                    catch (IllegalArgumentException | IOException e) {
                        if (!interactive) throw new IOException("Команда отклонена: " + e.getMessage(), e);
                        System.err.println("Ошибка: " + e.getMessage());
                    }
                }
                if (interactive) System.out.print("sql> ");
            } else buffer.append(c);
        }
        if (!buffer.toString().trim().isEmpty()) throw new IOException("Незавершённая команда: требуется ;");
    }
}
