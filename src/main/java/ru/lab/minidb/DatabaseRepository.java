package ru.lab.minidb;

import java.io.*;
public interface DatabaseRepository extends AutoCloseable {
    Model.Database load() throws IOException;
    void save(Model.Database database) throws IOException;
    @Override void close() throws IOException;
}
