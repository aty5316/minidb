package ru.lab.minidb;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
public final class FileRepository implements DatabaseRepository {
    private static final int MAGIC = 0x4D444231;
    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock lock;
    public FileRepository(Path directory) throws IOException {
        this.directory = directory; Files.createDirectories(directory);
        lockChannel = FileChannel.open(directory.resolve("database.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (RuntimeException | IOException e) { lockChannel.close(); throw new IOException("База уже открыта или блокировка недоступна", e); }
        if (acquired == null) { lockChannel.close(); throw new IOException("База уже открыта другим процессом"); }
        lock = acquired;
    }
    private Path slot(int n) { return directory.resolve("snapshot-" + n + ".bin"); }
    public Model.Database load() throws IOException {
        Model.Database best = null; boolean exists = false;
        for (int i = 0; i < 2; i++) {
            Path file = slot(i);
            if (!Files.exists(file)) continue;
            exists = true;
            try {
                Model.Database candidate = read(file);
                if (best == null || candidate.revision > best.revision) best = candidate;
            } catch (IOException | RuntimeException e) {
                System.err.println("Предупреждение: не читается " + file.getFileName() + ": " + e.getMessage());
            }
        }
        if (best == null && exists) throw new IOException("Нет корректного снимка; файлы сохранены для диагностики");
        return best == null ? new Model.Database() : best;
    }
    private Model.Database read(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            if (in.readInt() != MAGIC) throw new IOException("Неверный формат");
            int length = in.readInt();
            if (length < 0 || length != Files.size(file) - 40) throw new IOException("Неверная длина");
            byte[] expected = new byte[32]; in.readFully(expected);
            byte[] payload = new byte[length]; in.readFully(payload);
            if (!MessageDigest.isEqual(expected, digest(payload))) throw new IOException("Не совпала SHA-256");
            return decode(payload);
        }
    }
    public void save(Model.Database db) throws IOException {
        byte[] payload = encode(db);
        Path temp = directory.resolve("snapshot.tmp");
        try {
            try (FileOutputStream file = new FileOutputStream(temp.toFile());
                 DataOutputStream out = new DataOutputStream(file)) {
                out.writeInt(MAGIC); out.writeInt(payload.length); out.write(digest(payload)); out.write(payload);
                out.flush(); file.getFD().sync();
            }
            Files.move(temp, slot((int) (db.revision & 1)), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    static byte[] encode(Model.Database db) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(db); }
        return bytes.toByteArray();
    }
    static Model.Database decode(byte[] data) throws IOException {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (Model.Database) in.readObject();
        } catch (ClassNotFoundException | ClassCastException e) { throw new IOException("Несовместимый снимок", e); }
    }
    private static byte[] digest(byte[] data) {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public void close() throws IOException { try { lock.release(); } finally { lockChannel.close(); } }
}
