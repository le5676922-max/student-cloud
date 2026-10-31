package io.studentcloud.content.learning;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * 启动独立 JVM 提取 PDF 文本，监控执行时间与暂存用量并回收进程资源。
 * One disposable PDF JVM across both assistant attachments and library indexing.
 */
final class PdfTextProcess {
    static final Duration TIMEOUT = Duration.ofMillis(PdfTextProtocol.MAX_WORKER_MILLIS);
    static final long MAX_TEMP_BYTES = 32L * 1024 * 1024;
    private static final Semaphore SLOT = new Semaphore(1);
    private static final String WORKER = PdfTextWorker.class.getName();
    private static final String BOOT_LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher";

    static LearningTextExtractor.Extracted extract(byte[] bytes) throws IOException {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return run(bytes, TIMEOUT, Path.of(System.getProperty("java.io.tmpdir")), directory -> workerCommand(classpath, directory));
    }

    @FunctionalInterface interface WorkerCommand { List<String> create(Path directory) throws IOException; }

    static LearningTextExtractor.Extracted run(byte[] bytes, Duration timeout, Path temporaryBase, WorkerCommand command) throws IOException {
        if (bytes == null || bytes.length < 8 || bytes.length > PdfTextProtocol.MAX_INPUT_BYTES || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F' || bytes[4] != '-') throw new IOException("Invalid PDF input");
        if (!SLOT.tryAcquire()) throw new LearningTextExtractor.ExtractionBusyException();
        Path directory = null; Process process = null;
        try {
            directory = Files.createTempDirectory(temporaryBase.toAbsolutePath().normalize(), "student-cloud-pdf-");
            Path input = directory.resolve("input.pdf"), output = directory.resolve("result.bin");
            Files.write(input, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            List<String> arguments = new ArrayList<>(command.create(directory)); arguments.add(input.toString()); arguments.add(output.toString());
            var builder = new ProcessBuilder(arguments).directory(directory.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            // Do not inherit database/model keys, JAVA_TOOL_OPTIONS, agents or loader overrides.
            String systemRoot = builder.environment().get("SystemRoot"); builder.environment().clear();
            if (systemRoot != null) builder.environment().put("SystemRoot", systemRoot);
            process = builder.start(); process.getOutputStream().close();
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("PDF extraction exceeded its time budget");
                if (process.waitFor(Math.min(100, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))), TimeUnit.MILLISECONDS)) break;
                checkTemporaryBudget(directory);
            }
            checkTemporaryBudget(directory);
            if (process.exitValue() != 0) throw new IOException("PDF could not be extracted within the resource budget");
            var result = PdfTextProtocol.read(output);
            return new LearningTextExtractor.Extracted(result.text(), result.truncated(), result.indexedPages(), result.totalPages());
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt(); throw new IOException("PDF extraction cancelled", cancelled);
        } finally {
            finish(process, directory);
        }
    }

    static List<String> workerCommand(String classpath, Path directory) throws IOException {
        if (classpath == null || classpath.isBlank()) throw new IOException("PDF worker classpath unavailable");
        String absoluteClasspath = Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator))).map(value -> Path.of(value.isEmpty() ? "." : value).toAbsolutePath().normalize().toString()).reduce((left, right) -> left + File.pathSeparator + right).orElseThrow();
        var arguments = new ArrayList<>(List.of(javaExecutable(), "-Xms16m", "-Xmx64m", "-Xss256k", "-XX:MaxMetaspaceSize=64m", "-XX:MaxDirectMemorySize=8m", "-XX:ReservedCodeCacheSize=32m", "-XX:ActiveProcessorCount=1", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", "-XX:-CreateCoredumpOnCrash", "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=" + directory, "-Duser.home=" + directory, "-Dpdfbox.fontcache=" + directory));
        boolean boot = isBootClasspath(absoluteClasspath);
        if (boot) arguments.add("-Dloader.main=" + WORKER);
        arguments.add("-cp"); arguments.add(absoluteClasspath); arguments.add(boot ? BOOT_LAUNCHER : WORKER);
        return List.copyOf(arguments);
    }

    private static boolean isBootClasspath(String classpath) throws IOException {
        String workerEntry = WORKER.replace('.', '/') + ".class";
        List<Path> entries = Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator))).map(Path::of).toList();
        if (entries.stream().anyMatch(path -> Files.isDirectory(path) && Files.isRegularFile(path.resolve(workerEntry)))) return false;
        for (Path path : entries) if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
            try (var archive = new JarFile(path.toFile())) {
                if (archive.getEntry("BOOT-INF/classes/" + workerEntry) != null && archive.getEntry(BOOT_LAUNCHER.replace('.', '/') + ".class") != null) return true;
            }
        }
        return false;
    }

    static String javaExecutable() { return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(); }

    private static void checkTemporaryBudget(Path directory) throws IOException {
        long[] total = {0};
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink() || (total[0] += attributes.size()) > MAX_TEMP_BYTES) throw new IOException("PDF temporary storage budget exceeded");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                // PDFBox removes scratch files while the supervisor checks the directory.
                if (failure instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
                throw failure;
            }
        });
    }

    private static void finish(Process process, Path directory) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            boolean interrupted = Thread.interrupted();
            try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { interrupted = true; }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            if (process.isAlive()) {
                // Retain the slot and files until the OS confirms death; never overlap another JVM.
                process.onExit().thenRun(() -> cleanAndRelease(directory)); return;
            }
        }
        cleanAndRelease(directory);
    }

    private static void cleanAndRelease(Path directory) {
        boolean interrupted = Thread.interrupted();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (directory != null) {
                try {
                    Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException { Files.deleteIfExists(file); return FileVisitResult.CONTINUE; }
                        @Override public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException { if (failure instanceof NoSuchFileException) return FileVisitResult.CONTINUE; throw failure; }
                        @Override public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException { if (failure != null) throw failure; Files.deleteIfExists(path); return FileVisitResult.CONTINUE; }
                    });
                    break;
                } catch (FileSystemException transientLock) {
                    // On Windows the terminated JVM can briefly retain its working directory.
                    if (System.nanoTime() >= deadline) throw transientLock;
                    try { Thread.sleep(50); } catch (InterruptedException cancelled) { interrupted = true; }
                }
            }
            SLOT.release();
        } catch (IOException failure) {
            // Keep one bounded abandoned directory and fail closed instead of accumulating leftovers.
            System.getLogger(PdfTextProcess.class.getName()).log(System.Logger.Level.WARNING, "PDF worker cleanup incomplete; parser remains unavailable until restart");
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    private PdfTextProcess() { }
}
