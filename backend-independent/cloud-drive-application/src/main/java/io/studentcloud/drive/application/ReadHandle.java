package io.studentcloud.drive.application;

import io.studentcloud.drive.FileRecord;
import java.io.IOException;
import java.io.InputStream;

/** The HTTP adapter owns this handle and must close it, including when the client disconnects. */
public record ReadHandle(FileRecord metadata, InputStream input) implements AutoCloseable {
    @Override public void close() throws IOException { input.close(); }
}
