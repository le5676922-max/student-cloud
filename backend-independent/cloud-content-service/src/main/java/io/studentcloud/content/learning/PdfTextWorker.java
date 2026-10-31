package io.studentcloud.content.learning;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.io.ScratchFile;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * 在子进程中读取 PDF、提取限定页数的文本并写出提取结果。
 * Entry point only for a resource-limited child JVM. Never called inside the content service.
 */
public final class PdfTextWorker {
    public static void main(String[] arguments) {
        if (arguments.length != 2) System.exit(2);
        startLifetimeGuard();
        try {
            Path input = Path.of(arguments[0]), output = Path.of(arguments[1]);
            if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS) || Files.size(input) > PdfTextProtocol.MAX_INPUT_BYTES) throw new IOException("PDF input budget exceeded");
            var cache = MemoryUsageSetting.setupTempFileOnly(PdfTextProtocol.MAX_SCRATCH_BYTES).setTempDir(output.getParent().toFile());
            try (var source = new RandomAccessReadBufferedFile(input); var document = Loader.loadPDF(source, () -> new ScratchFile(cache))) {
                if (document.isEncrypted()) throw new IOException("Encrypted documents are unsupported");
                int total = document.getNumberOfPages(), pages = Math.min(total, PdfTextProtocol.MAX_PAGES);
                var stripper = new PageStripper(); stripper.setEndPage(pages);
                var writer = new LimitedWriter(); boolean truncated = total > pages;
                try { stripper.writeText(document, writer); } catch (TextLimit full) { truncated = true; }
                PdfTextProtocol.write(output, new PdfTextProtocol.Result(writer.text.toString(), truncated, Math.min(pages, stripper.currentPage()), total));
            }
        } catch (Exception rejected) {
            // The parent reports an intentionally generic message; no document, paths or secrets in logs.
            System.exit(2);
        }
    }
    private static void startLifetimeGuard() {
        ProcessHandle parent = ProcessHandle.current().parent().orElse(null);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(PdfTextProtocol.MAX_WORKER_MILLIS);
        Thread.ofPlatform().daemon(true).name("pdf-worker-lifetime").start(() -> {
            try {
                while (System.nanoTime() < deadline && (parent == null || parent.isAlive())) Thread.sleep(100);
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            // Still terminate if the supervising service was killed or document parsing is stuck.
            Runtime.getRuntime().halt(3);
        });
    }
    private static final class TextLimit extends IOException { }
    private static final class PageStripper extends PDFTextStripper { int currentPage() { return getCurrentPageNo(); } }
    private static final class LimitedWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        @Override public void write(char[] chars, int offset, int count) throws IOException {
            int remaining = PdfTextProtocol.MAX_TEXT_CHARS - text.length(); text.append(chars, offset, Math.min(remaining, count));
            if (count > remaining) throw new TextLimit();
        }
        @Override public void flush() { }
        @Override public void close() { }
    }
    private PdfTextWorker() { }
}
