package io.studentcloud.content.learning;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 定义 PDF 提取子进程的文本结果格式，并校验结果长度与页码信息。
 * Small inert result shared with the disposable process; no Java serialization or document objects.
 */
final class PdfTextProtocol {
    static final int MAX_INPUT_BYTES = 10 * 1024 * 1024;
    static final int MAX_TEXT_CHARS = 100_000;
    static final int MAX_PAGES = 100;
    static final int MAX_OUTPUT_BYTES = 400_024;
    static final int MAX_SCRATCH_BYTES = 16 * 1024 * 1024;
    static final int MAX_WORKER_MILLIS = 10_000;
    private static final int MAGIC = 0x53435044;
    record Result(String text, boolean truncated, int indexedPages, int totalPages) { }

    static void write(Path file, Result result) throws IOException {
        byte[] text = result.text().getBytes(StandardCharsets.UTF_8);
        if (result.text().length() > MAX_TEXT_CHARS || text.length > MAX_OUTPUT_BYTES - 24) throw new IOException("PDF text budget exceeded");
        try (var output = new DataOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            output.writeInt(MAGIC); output.writeInt(1); output.writeBoolean(result.truncated());
            output.writeInt(result.indexedPages()); output.writeInt(result.totalPages()); output.writeInt(text.length); output.write(text);
        }
    }

    static Result read(Path file) throws IOException {
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_OUTPUT_BYTES) throw new IOException("Invalid PDF worker result");
        try (var input = new DataInputStream(Files.newInputStream(file))) {
            if (input.readInt() != MAGIC || input.readInt() != 1) throw new IOException("Invalid PDF worker result");
            boolean truncated = input.readBoolean(); int indexed = input.readInt(), total = input.readInt(), length = input.readInt();
            if (indexed < 0 || indexed > MAX_PAGES || total < indexed || length < 0 || length > MAX_OUTPUT_BYTES - 24) throw new IOException("Invalid PDF worker result");
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length || input.read() != -1) throw new IOException("Invalid PDF worker result");
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.length() > MAX_TEXT_CHARS) throw new IOException("PDF text budget exceeded");
            return new Result(text, truncated, indexed, total);
        }
    }
    private PdfTextProtocol() { }
}
