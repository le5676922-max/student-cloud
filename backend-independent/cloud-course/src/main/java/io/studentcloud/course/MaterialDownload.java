package io.studentcloud.course;

import java.io.IOException;
import java.io.InputStream;

/**
 * 封装课程资料元数据与下载流，并负责关闭下载内容。
 * HTTP adapter must close content after streaming, including failed/cancelled responses.
 */
public record MaterialDownload(MaterialView material, InputStream content) implements AutoCloseable {
    @Override public void close() throws IOException { content.close(); }
}
