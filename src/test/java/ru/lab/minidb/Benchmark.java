package ru.lab.minidb;

import java.nio.file.*;
import java.util.*;
public final class Benchmark {
    private static volatile long sink;
    private Benchmark() { }
    public static void main(String[] args) throws Exception {
        System.out.println("Java: " + System.getProperty("java.runtime.version") + "; OS: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println("rows,matched,scan_median_us,index_median_us,speedup,scan_examined,index_examined,index_build_ms");
        for (int size : new int[]{10000, 50000, 100000}) measure(size);
        if (sink == 0) throw new AssertionError("Пустые результаты");
    }
    private static void measure(int size) throws Exception {
        Path dir = Files.createTempDirectory("minidb-bench-");
        try (FileRepository repository = new FileRepository(dir)) {
            Engine e = new Engine(repository);
            e.execute("CREATE TABLE cars (id INT PRIMARY KEY, brand STRING, price DOUBLE, available BOOLEAN)");
            StringBuilder sql = new StringBuilder("INSERT INTO cars VALUES ");
            for (int i = 0; i < size; i++) {
                if (i > 0) sql.append(',');
                sql.append('(').append(i).append(",'Brand").append(i % 10000).append("',25000.0,TRUE)");
            }
            e.execute(sql.toString());
            long start = System.nanoTime();
            e.execute("CREATE INDEX idx_brand ON cars(brand)");
            double buildMs = (System.nanoTime() - start) / 1e6;
            String query = "SELECT id,brand FROM cars WHERE brand='Brand42'";
            Engine.Result scan = e.execute(query, false), indexed = e.execute(query, true);
            if (!new HashSet<>(scan.rows).equals(new HashSet<>(indexed.rows))) throw new AssertionError("Разные результаты");
            for (int i = 0; i < 200; i++) { consume(e.execute(query, false)); consume(e.execute(query, true)); }
            double[] scans = new double[7], indexes = new double[7];
            for (int round = 0; round < 7; round++) {
                if (round % 2 == 0) { scans[round] = batch(e, query, false); indexes[round] = batch(e, query, true); }
                else { indexes[round] = batch(e, query, true); scans[round] = batch(e, query, false); }
            }
            Arrays.sort(scans); Arrays.sort(indexes);
            System.out.printf(Locale.ROOT, "%d,%d,%.3f,%.3f,%.2f,%d,%d,%.3f%n", size, scan.rows.size(), scans[3], indexes[3], scans[3]/indexes[3], scan.examined, indexed.examined, buildMs);
        } finally { IntegrationTest.deleteTree(dir); }
    }
    private static double batch(Engine e, String sql, boolean index) throws Exception {
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) consume(e.execute(sql, index));
        return (System.nanoTime() - start) / 100.0 / 1000.0;
    }
    private static void consume(Engine.Result r) {
        long total = r.rows.size();
        for (List<Object> row : r.rows) total += (Integer) row.get(0);
        sink = total;
    }
}
