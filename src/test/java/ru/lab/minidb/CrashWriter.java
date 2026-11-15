package ru.lab.minidb;
import java.nio.file.Paths;
public final class CrashWriter {
    private CrashWriter() { }
    public static void main(String[] args) throws Exception {
        FileRepository r = new FileRepository(Paths.get(args[0]));
        new Engine(r).execute("INSERT INTO t VALUES (3)");
        Runtime.getRuntime().halt(23);
    }
}
