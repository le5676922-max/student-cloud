package io.studentcloud.drive;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded local storage with explicit physical roots. No sessions, database, HTTP DTOs,
 * framework services, or storage-source globals participate in file access.
 * The caller must authorize a root and path before invoking this class.
 */
public final class LocalFileStore {
    private static final String PRIVATE_PREFIX = ".studentcloud-";
    private static final int MAX_DEPTH = 128;
    private final Path root;
    private final int maxEntries;
    private final long maxTransferBytes;

    public LocalFileStore(Path physicalRoot, int maxEntries, long maxTransferBytes) throws IOException {
        if (maxEntries < 1 || maxTransferBytes < 1) throw new IllegalArgumentException("Positive budgets required");
        this.maxEntries = maxEntries;
        this.maxTransferBytes = maxTransferBytes;
        Path configured = Objects.requireNonNull(physicalRoot, "physicalRoot").toAbsolutePath().normalize();
        for(Path component:configured)if(component.toString().toLowerCase(Locale.ROOT).startsWith(PRIVATE_PREFIX))throw new IOException("A system-private path cannot be mounted as a drive");
        rejectRedirects(configured);
        if (!Files.isDirectory(configured, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Drive root must already be a real directory");
        root = configured.toRealPath();
    }

    public List<FileRecord> list(DrivePath directory) throws IOException {
        Path folder = resolve(directory);
        requireDirectory(folder);
        List<FileRecord> result = new ArrayList<>();
        try (var children = Files.newDirectoryStream(folder)) {
            for (Path child : children) {
                if (isPrivate(child)) continue;
                if (result.size() >= maxEntries) throw new IOException("Directory entry budget exceeded");
                DrivePath path = directory.child(child.getFileName().toString());
                result.add(record(path, resolve(path)));
            }
        }
        result.sort(Comparator.comparing(FileRecord::directory).reversed().thenComparing(FileRecord::name));
        return List.copyOf(result);
    }

    public Optional<FileRecord> stat(DrivePath path) throws IOException {
        Path physical = resolve(path);
        if (!Files.exists(physical, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        return Optional.of(record(path, physical));
    }

    public synchronized FileRecord createDirectory(DrivePath path) throws IOException {
        Path physical = resolve(path);
        if (Files.exists(physical, LinkOption.NOFOLLOW_LINKS)) {
            requireDirectory(physical);
        } else {
            requireDirectory(physical.getParent());
            Files.createDirectory(physical);
        }
        return record(path, physical);
    }

    /** Reads exactly expectedBytes, owns only its temporary output, and never closes caller input. */
    public synchronized FileRecord write(DrivePath path, InputStream input, long expectedBytes, boolean replace)
            throws IOException {
        Objects.requireNonNull(input, "input");
        requireNotRoot(path);
        checkSize(expectedBytes);
        Path target = resolve(path);
        requireDirectory(target.getParent());
        checkTarget(target, replace);
        Path temporary = Files.createTempFile(target.getParent(), PRIVATE_PREFIX + "write-", ".tmp");
        try {
            copyExact(input, temporary, expectedBytes);
            // Recheck every existing parent and the target after potentially slow network input.
            target = resolve(path);
            checkTarget(target, replace);
            publishFile(temporary, target, replace);
            return record(path, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Returned stream must be closed by its caller. Symlinks are never followed. */
    public InputStream read(DrivePath path) throws IOException {
        Path physical = resolve(path);
        BasicFileAttributes attributes = regularFile(physical);
        checkSize(attributes.size());
        return new BudgetInputStream(Files.newInputStream(physical, LinkOption.NOFOLLOW_LINKS), maxTransferBytes);
    }

    public synchronized FileRecord copy(DrivePath source, DrivePath destination) throws IOException {
        requireNotRoot(source);
        requireNotRoot(destination);
        Path from = resolve(source);
        Path to = resolve(destination);
        rejectNested(from, to);
        requireDirectory(to.getParent());
        checkTarget(to, false);
        List<TreeEntry> entries = inspectTree(from);
        BasicFileAttributes sourceAttributes = attributes(from);
        if (sourceAttributes.isRegularFile()) {
            try (InputStream input = Files.newInputStream(from, LinkOption.NOFOLLOW_LINKS)) {
                return write(destination, input, sourceAttributes.size(), false);
            }
        }
        Path staging = Files.createDirectory(to.getParent().resolve(PRIVATE_PREFIX + "copy-" + UUID.randomUUID()));
        try {
            for (TreeEntry entry : entries) {
                if (entry.relative().toString().isEmpty()) continue;
                Path sourceItem = from.resolve(entry.relative());
                rejectRedirects(sourceItem);
                Path stagedItem = staging.resolve(entry.relative());
                if (entry.directory()) {
                    requireDirectory(sourceItem);
                    Files.createDirectory(stagedItem);
                } else {
                    BasicFileAttributes current = regularFile(sourceItem);
                    if (current.size() != entry.bytes()) throw new IOException("Source changed during copy");
                    try (InputStream input = Files.newInputStream(sourceItem, LinkOption.NOFOLLOW_LINKS)) {
                        copyExact(input, stagedItem, entry.bytes());
                    }
                }
            }
            to = resolve(destination);
            checkTarget(to, false);
            // No ATOMIC_MOVE here: some providers replace existing directories with that option.
            Files.move(staging, to);
            return record(destination, to);
        } finally {
            removeOwnedStaging(staging);
        }
    }

    public synchronized FileRecord move(DrivePath source, DrivePath destination) throws IOException {
        requireNotRoot(source);
        requireNotRoot(destination);
        Path from = resolve(source);
        Path to = resolve(destination);
        rejectNested(from, to);
        inspectTree(from);
        requireDirectory(to.getParent());
        checkTarget(to, false);
        Files.move(from, to);
        return record(destination, to);
    }

    /** Preflights the complete bounded tree before deleting anything; filesystem failures may be partial. */
    public synchronized void delete(DrivePath path) throws IOException {
        requireNotRoot(path);
        Path target = resolve(path);
        List<TreeEntry> entries = inspectTree(target);
        for (int i = entries.size() - 1; i >= 0; i--) {
            Path item = target.resolve(entries.get(i).relative());
            rejectRedirects(item);
            Files.delete(item);
        }
    }

    /** System-only vault operations: callers authorize the original root; DrivePath rejects this prefix. */
    public synchronized void recycle(DrivePath path,String id) throws IOException {
        requireNotRoot(path);Path source=resolve(path);inspectTree(source);
        Path target=recycled(id,true);checkTarget(target,false);Files.move(source,target);
    }
    public synchronized boolean hasRecycled(String id)throws IOException{return Files.exists(recycled(id,false),LinkOption.NOFOLLOW_LINKS);}
    public synchronized FileRecord restoreRecycled(String id,DrivePath destination)throws IOException{
        requireNotRoot(destination);Path source=recycled(id,false);inspectTree(source);
        Path target=resolve(destination);requireDirectory(target.getParent());checkTarget(target,false);
        Files.move(source,target);return record(destination,target);
    }
    public synchronized void purgeRecycled(String id)throws IOException{
        Path source=recycled(id,false);if(!Files.exists(source,LinkOption.NOFOLLOW_LINKS))return;
        var entries=inspectTree(source);
        for(int i=entries.size()-1;i>=0;i--){Path item=source.resolve(entries.get(i).relative());rejectRedirects(item);Files.delete(item);}
    }
    private Path recycled(String id,boolean create)throws IOException{
        if(id==null||!id.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid recycle item");
        rejectRedirects(root);Path vault=root.resolve(".studentcloud-trash");
        if(create&&!Files.exists(vault,LinkOption.NOFOLLOW_LINKS)){
            try{Files.createDirectory(vault);}catch(FileAlreadyExistsException raced){/* validate below */}
        }
        rejectRedirects(vault);if(Files.exists(vault,LinkOption.NOFOLLOW_LINKS))requireDirectory(vault);
        Path item=vault.resolve(id);rejectRedirects(item);return item;
    }

    private Path resolve(DrivePath path) throws IOException {
        Objects.requireNonNull(path, "path");
        rejectRedirects(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Drive root is unavailable");
        Path result = root.resolve(path.relative()).normalize();
        if (!result.startsWith(root)) throw new IOException("Path leaves drive root");
        rejectRedirects(result);
        return result;
    }

    private static void rejectRedirects(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path component : path) {
            current = current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) break;
            BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Filesystem links are not permitted");
        }
    }

    private List<TreeEntry> inspectTree(Path source) throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException("Source does not exist");
        List<TreeEntry> entries = new ArrayList<>();
        long[] bytes = {0};
        Files.walkFileTree(source, java.util.Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
            private void add(Path item, BasicFileAttributes attributes) throws IOException {
                rejectRedirects(item);
                if (!attributes.isDirectory() && !attributes.isRegularFile()) throw new IOException("Unsupported filesystem entry");
                if (isPrivate(item)) throw new IOException("An in-progress transfer prevents this operation");
                if (entries.size() >= maxEntries) throw new IOException("Recursive entry budget exceeded");
                long size = attributes.isDirectory() ? 0 : attributes.size();
                if (size > maxTransferBytes - bytes[0]) throw new IOException("Recursive byte budget exceeded");
                bytes[0] += size;
                entries.add(new TreeEntry(source.relativize(item), attributes.isDirectory(), size));
            }
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                add(directory, attributes);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isDirectory()) throw new IOException("Recursive depth budget exceeded");
                add(file, attributes);
                return FileVisitResult.CONTINUE;
            }
        });
        return entries;
    }

    private void copyExact(InputStream input, Path output, long expectedBytes) throws IOException {
        checkSize(expectedBytes);
        long total = 0;
        byte[] buffer = new byte[8192];
        try (OutputStream stream = Files.newOutputStream(output, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) continue;
                if (count > expectedBytes - total || count > maxTransferBytes - total)
                    throw new IOException("Transfer exceeds declared size or byte budget");
                stream.write(buffer, 0, count);
                total += count;
            }
        }
        if (total != expectedBytes) throw new IOException("Transfer size differs from declaration");
    }

    private static void publishFile(Path temporary, Path target, boolean replace) throws IOException {
        if (replace) {
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Filesystem does not support atomic replacement", unsupported);
            }
        } else {
            // A hard link atomically claims a new name, unlike an exists check followed by rename.
            // Fail closed on filesystems without hard-link support; never fall back to replacing a target.
            try { Files.createLink(target, temporary); }
            catch (UnsupportedOperationException unsupported) {
                throw new IOException("Filesystem does not support safe file publication", unsupported);
            }
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() && !attributes.isRegularFile()) throw new IOException("Unsupported filesystem entry");
        return attributes;
    }

    private static BasicFileAttributes regularFile(Path path) throws IOException {
        BasicFileAttributes result = attributes(path);
        if (!result.isRegularFile()) throw new IOException("Expected a regular file");
        return result;
    }

    private static void requireDirectory(Path path) throws IOException {
        rejectRedirects(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Parent directory must already exist");
    }

    private static void checkTarget(Path target, boolean replace) throws IOException {
        rejectRedirects(target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!replace) throw new FileAlreadyExistsException("Destination already exists");
            regularFile(target);
        }
    }

    private void checkSize(long size) throws IOException {
        if (size < 0 || size > maxTransferBytes) throw new IOException("Transfer byte budget exceeded");
    }

    private static void requireNotRoot(DrivePath path) throws IOException {
        if (path.isRoot()) throw new IOException("The drive root cannot be changed");
    }

    private static void rejectNested(Path source, Path target) throws IOException {
        if (target.startsWith(source)) throw new IOException("Destination cannot be the source or its descendant");
    }

    private static FileRecord record(DrivePath path, Path physical) throws IOException {
        BasicFileAttributes attributes = attributes(physical);
        return new FileRecord(path, path.name(), attributes.isDirectory(), attributes.isDirectory() ? 0 : attributes.size(),
                attributes.lastModifiedTime().toInstant());
    }

    private static boolean isPrivate(Path path) {
        return path.getFileName() != null && path.getFileName().toString().toLowerCase(Locale.ROOT).startsWith(PRIVATE_PREFIX);
    }

    private static void removeOwnedStaging(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        // Only a freshly generated private directory is traversed; links are rejected, never followed.
        if (!isPrivate(path)) throw new IOException("Not an owned staging directory");
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private record TreeEntry(Path relative, boolean directory, long bytes) { }

    private static final class BudgetInputStream extends FilterInputStream {
        private long remaining;
        private BudgetInputStream(InputStream source, long limit) { super(source); remaining = limit; }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value == -1) return -1;
            if (remaining == 0) throw new IOException("Read byte budget exceeded");
            remaining--;
            return value;
        }
        @Override public int read(byte[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) return 0;
            if (remaining == 0) return read() == -1 ? -1 : 0;
            int count = in.read(target, offset, (int) Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
        @Override public long skip(long amount) throws IOException {
            if (amount <= 0) return 0;
            long total = 0;
            byte[] buffer = new byte[8192];
            while (total < amount) {
                int count = read(buffer, 0, (int) Math.min(buffer.length, amount - total));
                if (count < 0) break;
                total += count;
            }
            return total;
        }
        @Override public boolean markSupported() { return false; }
        @Override public synchronized void mark(int readlimit) { }
        @Override public synchronized void reset() throws IOException { throw new IOException("Mark/reset not supported"); }
    }
}
