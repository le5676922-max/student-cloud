package io.studentcloud.drive.application;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;

/** Exact local accounting with a fixed scan budget; unreadable/unknown usage never means zero. */
final class LocalUsageMeter {
    private LocalUsageMeter() { }
    static long bytes(Path root) throws IOException {
        long started = System.nanoTime();
        long[] state = {0, 0};
        Files.walkFileTree(root, EnumSet.noneOf(java.nio.file.FileVisitOption.class), 129, new SimpleFileVisitor<>() {
            private void check(Path path, BasicFileAttributes attributes) throws IOException {
                if (++state[0] > MountService.MAX_ENTRIES || System.nanoTime() - started >= 15_000_000_000L
                        || attributes.isSymbolicLink() || (!attributes.isDirectory() && !attributes.isRegularFile())) {
                    throw new IOException("Storage usage cannot be determined within its safety budget");
                }
            }
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                check(path, attributes); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                check(path, attributes);
                if (!attributes.isRegularFile()) throw new IOException("Unsupported storage entry");
                try { state[1] = Math.addExact(state[1], attributes.size()); }
                catch (ArithmeticException invalid) { throw new IOException("Storage usage overflow", invalid); }
                return FileVisitResult.CONTINUE;
            }
        });
        return state[1];
    }
}
