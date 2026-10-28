package io.studentcloud.content.learning;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;

/** Text-only extraction never renders images, runs macros, or follows linked resources. */
public final class LearningTextExtractor {
    private static final int MAX_TEXT = 100_000;
    private static final int MAX_PAGES = 100;
    public record Extracted(String text, boolean truncated, Integer indexedPages, Integer totalPages) { }

    public static Extracted extract(String filename, byte[] bytes) throws IOException {
        String name = filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".txt") || name.endsWith(".md")) return bounded(new String(bytes, StandardCharsets.UTF_8));
        if (name.endsWith(".pdf")) {
            try (var source = new RandomAccessReadBuffer(bytes); var document = Loader.loadPDF(source, IOUtils.createTempFileOnlyStreamCache())) {
                if (document.isEncrypted()) throw new IOException("Encrypted documents are unsupported");
                int pages = Math.min(document.getNumberOfPages(), MAX_PAGES);
                var stripper = new PageStripper(); stripper.setEndPage(pages);
                var writer = new LimitedWriter(); boolean truncated = document.getNumberOfPages() > pages;
                try { stripper.writeText(document, writer); } catch (TextLimit full) { truncated = true; }
                return new Extracted(writer.text.toString(), truncated, Math.min(pages, stripper.currentPage()), document.getNumberOfPages());
            }
        }
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
    private static final class TextLimit extends IOException { }
    private static final class PageStripper extends PDFTextStripper {
        int currentPage() { return getCurrentPageNo(); }
    }
    private static final class LimitedWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        @Override public void write(char[] chars, int offset, int count) throws IOException {
            int remaining = MAX_TEXT - text.length(); text.append(chars, offset, Math.min(remaining, count));
            if (count > remaining) throw new TextLimit();
        }
        @Override public void flush() { }
        @Override public void close() { }
    }
}
