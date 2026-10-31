package io.studentcloud.content.learning;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import java.util.zip.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.loader.launch.PropertiesLauncher;

class PdfTextProcessTest {
    @TempDir Path temporary;
    private static final byte[] VALID_HEADER = "%PDF-1.4\nfixture".getBytes(StandardCharsets.US_ASCII);
    private static String classpath() { return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")); }

    @Test void normalPdfIsExtractedInASeparateProcessAndTemporaryFilesAreRemoved() throws Exception {
        var result = PdfTextProcess.run(pdf("local document evidence", 1), PdfTextProcess.TIMEOUT, temporary, dir -> PdfTextProcess.workerCommand(classpath(), dir));
        assertTrue(result.text().contains("local document evidence")); assertFalse(result.truncated());
        assertEquals(1, result.indexedPages()); assertEquals(1, result.totalPages()); assertEmptyTemporary();
    }

    @Test void pageBudgetAndTextBudgetAreReportedWithoutReturningUnboundedText() throws Exception {
        var pages = PdfTextProcess.run(pdf("first page", 101), PdfTextProcess.TIMEOUT, temporary, dir -> PdfTextProcess.workerCommand(classpath(), dir));
        assertEquals(101, pages.totalPages()); assertEquals(100, pages.indexedPages()); assertTrue(pages.truncated());
        // Keep each page within the worker heap while crossing the aggregate output limit.
        var text = PdfTextProcess.run(pdf("repeated document text ".repeat(100), 50), PdfTextProcess.TIMEOUT, temporary, dir -> PdfTextProcess.workerCommand(classpath(), dir));
        assertEquals(PdfTextProtocol.MAX_TEXT_CHARS, text.text().length()); assertTrue(text.truncated()); assertEmptyTemporary();
    }

    @Test void encryptedAndMalformedPdfFailClosedWithoutReturningPartialText() throws Exception {
        byte[] encrypted;
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage()); document.protect(new StandardProtectionPolicy("owner-password", "", new AccessPermission())); document.save(output); encrypted = output.toByteArray();
        }
        assertThrows(IOException.class, () -> LearningTextExtractor.extract("encrypted.pdf", encrypted));
        assertThrows(IOException.class, () -> PdfTextProcess.run(VALID_HEADER, PdfTextProcess.TIMEOUT, temporary, dir -> PdfTextProcess.workerCommand(classpath(), dir)));
        assertThrows(IOException.class, () -> LearningTextExtractor.extract("large.pdf", new byte[PdfTextProtocol.MAX_INPUT_BYTES + 1]));
        assertEmptyTemporary();
    }

    @Test void compressedNameExpansionCannotExhaustTheHostAndAHealthyPdfStillWorksAfterward() throws Exception {
        byte[] malicious = compressedTokenPdf(); assertTrue(malicious.length < 2 * 1024 * 1024);
        assertThrows(IOException.class, () -> PdfTextProcess.run(malicious, PdfTextProcess.TIMEOUT, temporary, dir -> PdfTextProcess.workerCommand(classpath(), dir)));
        var healthy = PdfTextProcess.run(pdf("parent remains available", 1), PdfTextProcess.TIMEOUT, temporary, dir -> PdfTextProcess.workerCommand(classpath(), dir));
        assertTrue(healthy.text().contains("parent remains available")); assertEmptyTemporary();
    }

    @Test void deadlineKillsTheChildAndReleasesTheGlobalSlot() throws Exception {
        assertThrows(IOException.class, () -> PdfTextProcess.run(VALID_HEADER, Duration.ofMillis(250), temporary, dir -> fixture(dir, "sleep")));
        var result = PdfTextProcess.run(VALID_HEADER, PdfTextProcess.TIMEOUT, temporary, dir -> fixture(dir, "spam"));
        assertEquals("stdout was discarded", result.text()); assertEmptyTemporary();
    }

    @Test void cancellationKillsTheChildAndNoSecondPdfProcessCanOverlap() throws Exception {
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var preservedInterrupt = new java.util.concurrent.atomic.AtomicBoolean();
        Thread parent = new Thread(() -> {
            try { PdfTextProcess.run(VALID_HEADER, Duration.ofSeconds(20), temporary, dir -> fixture(dir, "sleep")); }
            catch (Throwable caught) { failure.set(caught); preservedInterrupt.set(Thread.currentThread().isInterrupted()); }
        });
        parent.start(); long pid = awaitPid();
        assertThrows(IOException.class, () -> PdfTextProcess.run(VALID_HEADER, PdfTextProcess.TIMEOUT, temporary, dir -> fixture(dir, "spam")));
        parent.interrupt(); parent.join(5000);
        assertFalse(parent.isAlive()); assertInstanceOf(IOException.class, failure.get()); assertTrue(preservedInterrupt.get());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)); assertEmptyTemporary();
    }

    @Test void workerOutputIsBoundedValidatedAndDoesNotExposeInheritedConfiguration() throws Exception {
        var secret = PdfTextProcess.run(VALID_HEADER, PdfTextProcess.TIMEOUT, temporary, dir -> fixture(dir, "secret"));
        assertEquals("false", secret.text());
        assertThrows(IOException.class, () -> PdfTextProcess.run(VALID_HEADER, PdfTextProcess.TIMEOUT, temporary, dir -> fixture(dir, "invalid")));
        Path output = temporary.resolve("oversized-result.bin");
        try (var stream = Files.newOutputStream(output)) { stream.write(new byte[PdfTextProtocol.MAX_OUTPUT_BYTES + 1]); }
        assertThrows(IOException.class, () -> PdfTextProtocol.read(output)); Files.delete(output); assertEmptyTemporary();
    }

    @Test void nestedExecutableBootJarStartsOnlyThePdfWorkerAndExtractsNormally() throws Exception {
        Path artifact = bootJar();
        Path work = Files.createDirectory(temporary.resolve("runs"));
        var command = PdfTextProcess.workerCommand(artifact.toString(), work);
        assertTrue(command.contains("-Dloader.main=" + PdfTextWorker.class.getName()));
        assertEquals(PropertiesLauncher.class.getName(), command.getLast());
        var result = PdfTextProcess.run(pdf("nested boot worker evidence", 1), PdfTextProcess.TIMEOUT, work, dir -> PdfTextProcess.workerCommand(artifact.toString(), dir));
        assertTrue(result.text().contains("nested boot worker evidence"));
        try (var remaining = Files.list(work)) { assertEquals(0, remaining.count()); }
    }

    private List<String> fixture(Path directory, String mode) throws IOException {
        var command = new ArrayList<>(PdfTextProcess.workerCommand(classpath(), directory));
        command.set(command.size() - 1, PdfWorkerFixture.class.getName()); command.add(mode); return command;
    }

    private long awaitPid() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (System.nanoTime() < deadline) {
            try (var paths = Files.walk(temporary)) {
                var pid = paths.filter(path -> path.getFileName().toString().equals("started.pid")).findFirst();
                if (pid.isPresent()) { try { return Long.parseLong(Files.readString(pid.get())); } catch (NumberFormatException notWrittenYet) { } }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Child did not start");
    }
    private void assertEmptyTemporary() throws IOException { try (var entries = Files.list(temporary)) { assertEquals(0, entries.count()); } }

    private static byte[] pdf(String text, int count) throws IOException {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (int i = 0; i < count; i++) {
                var page = new PDPage(); document.addPage(page);
                try (var content = new PDPageContentStream(document, page)) {
                    content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12); content.newLineAtOffset(20, 700); content.showText(text); content.endText();
                }
            }
            document.save(output); return output.toByteArray();
        }
    }

    /** Builds the historical amplification shape without allocating the expanded token in the host. */
    private static byte[] compressedTokenPdf() throws IOException {
        var compressed = new ByteArrayOutputStream();
        try (var deflate = new DeflaterOutputStream(compressed)) {
            deflate.write('/'); byte[] repeated = new byte[65536]; Arrays.fill(repeated, (byte) 'A');
            for (int i = 0; i < 2048; i++) deflate.write(repeated);
            deflate.write(" Do\n".getBytes(StandardCharsets.US_ASCII));
        }
        byte[] payload = compressed.toByteArray();
        List<byte[]> objects = List.of("<< /Type /Catalog /Pages 2 0 R >>".getBytes(StandardCharsets.US_ASCII), "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".getBytes(StandardCharsets.US_ASCII), "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << >> /Contents 4 0 R >>".getBytes(StandardCharsets.US_ASCII), join(("<< /Length " + payload.length + " /Filter /FlateDecode >>\nstream\n").getBytes(StandardCharsets.US_ASCII), payload, "\nendstream".getBytes(StandardCharsets.US_ASCII)));
        var output = new ByteArrayOutputStream(); output.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII)); List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) { offsets.add(output.size()); output.write((i + 1 + " 0 obj\n").getBytes(StandardCharsets.US_ASCII)); output.write(objects.get(i)); output.write("\nendobj\n".getBytes(StandardCharsets.US_ASCII)); }
        int xref = output.size(); output.write("xref\n0 5\n0000000000 65535 f \n".getBytes(StandardCharsets.US_ASCII));
        for (int offset : offsets) output.write(String.format(Locale.ROOT, "%010d 00000 n \n", offset).getBytes(StandardCharsets.US_ASCII));
        output.write(("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII)); return output.toByteArray();
    }
    private static byte[] join(byte[]... chunks) throws IOException { var bytes = new ByteArrayOutputStream(); for (byte[] part : chunks) bytes.write(part); return bytes.toByteArray(); }

    private Path bootJar() throws Exception {
        Path artifact = temporary.resolve("nested-content.jar"); var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0"); manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "org.springframework.boot.loader.launch.JarLauncher");
        manifest.getMainAttributes().putValue("Start-Class", PdfTextWorker.class.getName());
        try (var archive = new JarOutputStream(Files.newOutputStream(artifact), manifest)) {
            // Boot discovers the classes root by its directory entry, as emitted by repackage.
            put(archive, "BOOT-INF/classes/", new byte[0], false);
            Path loader = Path.of(PropertiesLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            try (var source = new JarFile(loader.toFile())) {
                for (var entry : source.stream().filter(entry -> !entry.isDirectory() && entry.getName().startsWith("org/springframework/boot/loader/")).toList()) {
                    put(archive, entry.getName(), source.getInputStream(entry).readAllBytes(), false);
                }
            }
            for (Class<?> type : List.of(PdfTextWorker.class, PdfTextProtocol.class)) addClasses(archive, type);
            for (Class<?> dependency : List.of(Loader.class, RandomAccessReadBufferedFile.class, org.apache.fontbox.util.BoundingBox.class, org.apache.commons.logging.LogFactory.class)) {
                Path file = Path.of(dependency.getProtectionDomain().getCodeSource().getLocation().toURI());
                put(archive, "BOOT-INF/lib/" + file.getFileName(), Files.readAllBytes(file), true);
            }
        }
        return artifact;
    }
    private static void addClasses(JarOutputStream archive, Class<?> type) throws IOException {
        String name = type.getName().replace('.', '/') + ".class";
        try (var resource = type.getClassLoader().getResourceAsStream(name)) { if (resource == null) throw new IOException("Class unavailable"); put(archive, "BOOT-INF/classes/" + name, resource.readAllBytes(), false); }
        for (Class<?> child : type.getDeclaredClasses()) addClasses(archive, child);
    }
    private static void put(JarOutputStream archive, String name, byte[] data, boolean stored) throws IOException {
        var entry = new JarEntry(name);
        if (stored) { var crc = new CRC32(); crc.update(data); entry.setMethod(ZipEntry.STORED); entry.setSize(data.length); entry.setCompressedSize(data.length); entry.setCrc(crc.getValue()); }
        archive.putNextEntry(entry); archive.write(data); archive.closeEntry();
    }
}
