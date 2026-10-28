package io.studentcloud.course;

import java.io.IOException;
import java.io.InputStream;

/** HTTP adapter must close content after streaming, including failed/cancelled responses. */
public record MaterialDownload(MaterialView material, InputStream content) implements AutoCloseable {
    @Override public void close() throws IOException { content.close(); }
}
