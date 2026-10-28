package io.studentcloud.drive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LocalFileStoreTest {
    @Test void recycleIsHiddenAndRestoreNeverOverwritesAnotherFile() throws Exception {
        String id="a".repeat(32);store.write(DrivePath.of("/notes.txt"),new ByteArrayInputStream("original".getBytes()),8,false);
        store.recycle(DrivePath.of("/notes.txt"),id);
        assertTrue(store.list(DrivePath.of("/")).isEmpty());
        assertTrue(store.hasRecycled(id));
        store.write(DrivePath.of("/notes.txt"),new ByteArrayInputStream("new".getBytes()),3,false);
        assertThrows(java.nio.file.FileAlreadyExistsException.class,()->store.restoreRecycled(id,DrivePath.of("/notes.txt")));
        store.delete(DrivePath.of("/notes.txt"));store.restoreRecycled(id,DrivePath.of("/notes.txt"));
        try(var input=store.read(DrivePath.of("/notes.txt"))){assertEquals("original",new String(input.readAllBytes()));}
        assertFalse(store.hasRecycled(id));
    }
    @Test void recycledFoldersCanBePurgedWithoutOpeningPrivatePathsToNormalOperations() throws Exception {
        store.createDirectory(DrivePath.of("/folder"));String id="b".repeat(32);
        store.recycle(DrivePath.of("/folder"),id);store.purgeRecycled(id);assertFalse(store.hasRecycled(id));
        assertThrows(IOException.class,()->new LocalFileStore(root.resolve(".studentcloud-trash"),100,1024));
        assertThrows(IllegalArgumentException.class,()->DrivePath.of("/.studentcloud-trash/"+id));
        assertThrows(IllegalArgumentException.class,()->store.hasRecycled("../unsafe"));
    }
    @TempDir Path temporary;
    private Path root;
    private LocalFileStore store;

    @BeforeEach void createStore() throws IOException {
        root = Files.createDirectory(temporary.resolve("root"));
        store = new LocalFileStore(root, 100, 1024);
    }

    @Test void constructorRequiresExistingDirectoryAndPositiveBudgets() throws IOException {
        assertThrows(IOException.class, () -> new LocalFileStore(temporary.resolve("missing"), 1, 1));
        var file = Files.writeString(temporary.resolve("file"), "x");
        assertThrows(IOException.class, () -> new LocalFileStore(file, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new LocalFileStore(root, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new LocalFileStore(root, 1, 0));
    }

    @Test void createsSingleDirectoryAndListsPublicMetadata() throws IOException {
        var directory = store.createDirectory(p("/课程"));
        assertTrue(directory.directory());
        assertEquals(0, directory.size());
        assertEquals("课程", directory.name());
        assertNotNull(directory.modifiedAt());
        assertEquals(List.of(directory), store.list(p("/")));
    }

    @Test void creatingExistingDirectoryIsIdempotent() throws IOException {
        store.createDirectory(p("/folder"));
        assertTrue(store.createDirectory(p("/folder")).directory());
    }

    @Test void requiresExistingParentsWithoutCreatingAnUnrequestedTree() {
        assertThrows(IOException.class, () -> store.createDirectory(p("/missing/folder")));
        assertThrows(IOException.class, () -> write("/missing/note.txt", "hello", false));
        assertFalse(Files.exists(root.resolve("missing")));
    }

    @Test void writesReadsAndStatsExactUtf8Bytes() throws IOException {
        var bytes = "课程资料".getBytes(StandardCharsets.UTF_8);
        var file = store.write(p("/notes.txt"), new ByteArrayInputStream(bytes), bytes.length, false);
        assertEquals(bytes.length, file.size());
        assertFalse(file.directory());
        assertEquals(file, store.stat(p("/notes.txt")).orElseThrow());
        try (var stream = store.read(p("/notes.txt"))) { assertArrayEquals(bytes, stream.readAllBytes()); }
    }

    @Test void emptyFileIsAnActualFile() throws IOException {
        assertEquals(0, write("/empty", "", false).size());
        try (var stream = store.read(p("/empty"))) { assertEquals(-1, stream.read()); }
    }

    @Test void statMissingReturnsEmptyRatherThanCreatingAnything() throws IOException {
        assertTrue(store.stat(p("/missing")).isEmpty());
        assertEquals(0, store.list(p("/")).size());
    }

    @Test void listOrdersDirectoriesBeforeFiles() throws IOException {
        write("/a.txt", "a", false);
        store.createDirectory(p("/z-folder"));
        assertEquals(List.of("z-folder", "a.txt"), store.list(p("/")).stream().map(FileRecord::name).toList());
    }

    @Test void readDirectoryAndCreateDirectoryOverFileAreRejected() throws IOException {
        write("/a", "a", false);
        assertThrows(IOException.class, () -> store.read(p("/")));
        assertThrows(IOException.class, () -> store.createDirectory(p("/a")));
    }

    @Test void defaultWriteNeverOverwritesExistingFile() throws IOException {
        write("/a", "old", false);
        assertThrows(FileAlreadyExistsException.class, () -> write("/a", "new", false));
        assertEquals("old", Files.readString(root.resolve("a")));
        assertOnlyRootNames("a");
    }

    @Test void explicitReplacementPublishesCompleteContent() throws IOException {
        write("/a", "old", false);
        write("/a", "new-value", true);
        assertEquals("new-value", Files.readString(root.resolve("a")));
        assertOnlyRootNames("a");
    }

    @Test void shortTransferLeavesOldTargetAndNoTemporaryFile() throws IOException {
        write("/a", "old", false);
        assertThrows(IOException.class, () -> store.write(p("/a"), bytes("short"), 7, true));
        assertEquals("old", Files.readString(root.resolve("a")));
        assertOnlyRootNames("a");
    }

    @Test void trailingBytesCannotPublishNewTarget() throws IOException {
        assertThrows(IOException.class, () -> store.write(p("/a"), bytes("extra"), 2, false));
        assertOnlyRootNames();
    }

    @Test void upstreamFailureDoesNotDamageReplacementTarget() throws IOException {
        write("/a", "old", false);
        var broken = new InputStream() {
            private int count;
            @Override public int read() throws IOException {
                if (count++ == 2) throw new IOException("Synthetic network interruption");
                return 'a';
            }
        };
        assertThrows(IOException.class, () -> store.write(p("/a"), broken, 4, true));
        assertEquals("old", Files.readString(root.resolve("a")));
        assertOnlyRootNames("a");
    }

    @Test void writingDoesNotCloseCallerOwnedInput() throws IOException {
        boolean[] closed = {false};
        var input = new ByteArrayInputStream(new byte[] {1}) {
            @Override public void close() { closed[0] = true; }
        };
        store.write(p("/a"), input, 1, false);
        assertFalse(closed[0]);
    }

    @Test void oversizeDeclarationFailsBeforeReadingInput() throws IOException {
        var tiny = new LocalFileStore(root, 100, 2);
        var input = new InputStream() {
            @Override public int read() { fail("An oversized declaration must not consume input"); return -1; }
        };
        assertThrows(IOException.class, () -> tiny.write(p("/a"), input, 3, false));
        assertThrows(IOException.class, () -> tiny.write(p("/a"), input, -1, false));
        assertOnlyRootNames();
    }

    @Test void oversizedExistingFileCannotBeRead() throws IOException {
        Files.writeString(root.resolve("a"), "123");
        assertThrows(IOException.class, () -> new LocalFileStore(root, 100, 2).read(p("/a")));
    }

    @Test void aFileGrowingAfterOpenStillCannotExceedReadBudget() throws IOException {
        Files.writeString(root.resolve("a"), "12");
        try (var input = new LocalFileStore(root, 100, 2).read(p("/a"))) {
            Files.writeString(root.resolve("a"), "1234");
            assertThrows(IOException.class, input::readAllBytes);
        }
    }

    @Test void fileCopyKeepsOriginalAndRefusesConflictingTarget() throws IOException {
        write("/a", "original", false);
        store.copy(p("/a"), p("/b"));
        assertEquals("original", Files.readString(root.resolve("a")));
        assertEquals("original", Files.readString(root.resolve("b")));
        assertThrows(IOException.class, () -> store.copy(p("/a"), p("/b")));
    }

    @Test void directoryCopyIncludesNestedEmptyDirectoriesAndFiles() throws IOException {
        store.createDirectory(p("/source"));
        store.createDirectory(p("/source/nested"));
        store.createDirectory(p("/source/empty"));
        write("/source/nested/a", "payload", false);
        store.copy(p("/source"), p("/destination"));
        assertEquals("payload", Files.readString(root.resolve("destination/nested/a")));
        assertTrue(Files.isDirectory(root.resolve("destination/empty")));
        assertOnlyRootNames("source", "destination");
    }

    @Test void copyAndMoveCannotEnterTheirOwnSourceDirectory() throws IOException {
        store.createDirectory(p("/source"));
        assertThrows(IOException.class, () -> store.copy(p("/source"), p("/source/nested")));
        assertThrows(IOException.class, () -> store.move(p("/source"), p("/source/nested")));
        assertThrows(IOException.class, () -> store.copy(p("/source"), p("/source")));
        assertOnlyRootNames("source");
    }

    @Test void movingDirectoryPreservesContentAndRemovesOnlySourceName() throws IOException {
        store.createDirectory(p("/source"));
        write("/source/a", "a", false);
        store.move(p("/source"), p("/destination"));
        assertFalse(Files.exists(root.resolve("source")));
        assertEquals("a", Files.readString(root.resolve("destination/a")));
    }

    @Test void moveConflictLeavesBothExistingFilesUntouched() throws IOException {
        write("/a", "a", false);
        write("/b", "b", false);
        assertThrows(IOException.class, () -> store.move(p("/a"), p("/b")));
        assertEquals("a", Files.readString(root.resolve("a")));
        assertEquals("b", Files.readString(root.resolve("b")));
    }

    @Test void recursiveDeleteRemovesOnlyRequestedTree() throws IOException {
        store.createDirectory(p("/folder"));
        write("/folder/a", "a", false);
        write("/keep", "b", false);
        store.delete(p("/folder"));
        assertOnlyRootNames("keep");
    }

    @Test void rootCannotBeWrittenDeletedCopiedOrMoved() {
        assertThrows(IOException.class, () -> write("/", "bad", true));
        assertThrows(IOException.class, () -> store.delete(p("/")));
        assertThrows(IOException.class, () -> store.copy(p("/"), p("/a")));
        assertThrows(IOException.class, () -> store.move(p("/"), p("/a")));
    }

    @Test void recursiveBudgetFailsBeforeDeletingAnyFileOrPublishingCopy() throws IOException {
        store.createDirectory(p("/folder"));
        write("/folder/a", "a", false);
        write("/folder/b", "b", false);
        var tiny = new LocalFileStore(root, 2, 1024);
        assertThrows(IOException.class, () -> tiny.delete(p("/folder")));
        assertThrows(IOException.class, () -> tiny.copy(p("/folder"), p("/copy")));
        assertTrue(Files.exists(root.resolve("folder/a")));
        assertTrue(Files.exists(root.resolve("folder/b")));
        assertFalse(Files.exists(root.resolve("copy")));
    }

    @Test void aggregateCopyBytesAreBoundedAcrossFiles() throws IOException {
        store.createDirectory(p("/folder"));
        write("/folder/a", "12", false);
        write("/folder/b", "34", false);
        var tiny = new LocalFileStore(root, 10, 3);
        assertThrows(IOException.class, () -> tiny.copy(p("/folder"), p("/copy")));
        assertFalse(Files.exists(root.resolve("copy")));
        assertEquals("12", Files.readString(root.resolve("folder/a")));
    }

    @Test void directoryListFailsRatherThanReturningAFalseCompletePage() throws IOException {
        write("/a", "a", false);
        write("/b", "b", false);
        assertThrows(IOException.class, () -> new LocalFileStore(root, 1, 1024).list(p("/")));
    }

    @Test void linksAreNotAcceptedAsDriveRoots() throws IOException {
        Path alias = temporary.resolve("alias");
        symlink(alias, root);
        assertThrows(IOException.class, () -> new LocalFileStore(alias, 100, 1024));
    }

    @Test void symlinkFilesAndIntermediateDirectoriesCannotBeReadOrWritten() throws IOException {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("secret"), "private");
        symlink(root.resolve("alias"), outside);
        assertThrows(IOException.class, () -> store.read(p("/alias/secret")));
        assertThrows(IOException.class, () -> write("/alias/new", "bad", false));
        assertThrows(IOException.class, () -> store.list(p("/")));
        assertFalse(Files.exists(outside.resolve("new")));
        assertEquals("private", Files.readString(outside.resolve("secret")));
    }

    @Test void nestedSymlinkMakesDeletePreflightFailWithoutRemovingEarlierFiles() throws IOException {
        store.createDirectory(p("/folder"));
        write("/folder/a", "keep", false);
        Path outside = Files.writeString(temporary.resolve("outside-file"), "private");
        symlink(root.resolve("folder/link"), outside);
        assertThrows(IOException.class, () -> store.delete(p("/folder")));
        assertThrows(IOException.class, () -> store.copy(p("/folder"), p("/copy")));
        assertEquals("keep", Files.readString(root.resolve("folder/a")));
        assertEquals("private", Files.readString(outside));
        assertFalse(Files.exists(root.resolve("copy")));
    }

    @Test void competingStoreInstancesCannotReplaceAnExistingPublishedFile() throws Exception {
        var other = new LocalFileStore(root, 100, 1024);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> actions = List.of(
                    () -> publish(store, "one"), () -> publish(other, "two"));
            List<Future<Boolean>> results = pool.invokeAll(actions);
            int successes = 0;
            for (var result : results) if (result.get()) successes++;
            assertEquals(1, successes);
            assertTrue(List.of("one", "two").contains(Files.readString(root.resolve("winner"))));
        }
        assertOnlyRootNames("winner");
    }

    private static boolean publish(LocalFileStore target, String text) throws IOException {
        try {
            target.write(p("/winner"), bytes(text), 3, false);
            return true;
        } catch (FileAlreadyExistsException expected) { return false; }
    }

    private FileRecord write(String path, String value, boolean replace) throws IOException {
        byte[] content = value.getBytes(StandardCharsets.UTF_8);
        return store.write(p(path), new ByteArrayInputStream(content), content.length, replace);
    }

    private void assertOnlyRootNames(String... expected) throws IOException {
        try (var paths = Files.list(root)) {
            assertEquals(java.util.Set.of(expected), paths.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
    }

    private static DrivePath p(String path) { return DrivePath.of(path); }
    private static ByteArrayInputStream bytes(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }

    private static void symlink(Path link, Path target) throws IOException {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic-link privilege is unavailable in this environment");
        }
    }
}
