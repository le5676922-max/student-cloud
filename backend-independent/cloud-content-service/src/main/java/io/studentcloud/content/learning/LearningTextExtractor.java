package io.studentcloud.content.learning;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipInputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;

/**
 * 从纯文本、PDF 与 DOCX 资料中提取受长度限制的学习文本。
 * Text-only extraction never renders images, runs macros, or follows linked resources.
 */
public final class LearningTextExtractor {
    private static final int MAX_TEXT = 100_000;
    public record Extracted(String text, boolean truncated, Integer indexedPages, Integer totalPages) { }
    public static final class ExtractionBusyException extends IOException {
        ExtractionBusyException() { super("PDF extraction is busy; retry later"); }
    }

    public static Extracted extract(String filename, byte[] bytes) throws IOException {
        String name = filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".txt") || name.endsWith(".md")) return bounded(new String(bytes, StandardCharsets.UTF_8));
        if (name.endsWith(".pdf")) return PdfTextProcess.extract(bytes);
        if (name.endsWith(".docx")) {
            validateArchive(bytes);
            try (var document = new XWPFDocument(new ByteArrayInputStream(bytes)); var extractor = new XWPFWordExtractor(document)) {
                extractor.setFetchHyperlinks(false);
                return bounded(extractor.getText());
            }
        }
        throw new IOException("Unsupported format");
    }

    /** Bound actual expansion before allowing POI to build its XML model. */
    private static void validateArchive(byte[] bytes) throws IOException {
        int entries = 0; long expanded = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[8192];
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > 1000) throw new IOException("Too many ZIP entries");
                long entryBytes = 0;
                for (int read; (read = zip.read(buffer)) != -1;) {
                    expanded += read; entryBytes += read;
                    if (expanded > 20L * 1024 * 1024 || entryBytes > 4L * 1024 * 1024) throw new IOException("ZIP expansion budget exceeded");
                }
            }
        }
        if (entries == 0) throw new IOException("Invalid DOCX");
    }
    private static Extracted bounded(String text) { return new Extracted(text.substring(0, Math.min(text.length(), MAX_TEXT)), text.length() > MAX_TEXT, null, null); }
}
