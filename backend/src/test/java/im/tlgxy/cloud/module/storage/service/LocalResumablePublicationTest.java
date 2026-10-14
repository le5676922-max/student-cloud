package im.tlgxy.cloud.module.storage.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Synthetic files in a dedicated temporary directory; never a real cloud or staging volume. */
class LocalResumablePublicationTest {
    @TempDir Path work;

    @Test
    void existingTargetIsPreservedWithoutChangingTheSource() throws Exception {
        byte[] sourceBytes = payload(8192 + 23, 17);
        byte[] original = "existing cloud file".getBytes(StandardCharsets.UTF_8);
        Path assembled = Files.write(work.resolve("assembled.pending"), sourceBytes);
        Path target = Files.write(work.resolve("published.bin"), original);

        assertThrows(FileAlreadyExistsException.class,
                () -> LocalResumablePublication.publish(assembled, target, sourceBytes.length));

        assertArrayEquals(original, Files.readAllBytes(target));
        assertArrayEquals(sourceBytes, Files.readAllBytes(assembled));
    }

    @Test
    void concurrentPublicationsCreateExactlyOneCompleteTarget() throws Exception {
        byte[] firstBytes = payload(8192 * 3 + 17, 29);
        byte[] secondBytes = payload(8192 * 2 + 91, 73);
        Path firstSource = Files.write(work.resolve("first.pending"), firstBytes);
        Path secondSource = Files.write(work.resolve("second.pending"), secondBytes);
        Path target = work.resolve("published.bin");
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> attempt(firstSource, target, firstBytes.length, ready, start));
            var second = executor.submit(() -> attempt(secondSource, target, secondBytes.length, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Both competing writers must be ready");
            start.countDown();
            boolean firstWon = first.get(5, TimeUnit.SECONDS);
            boolean secondWon = second.get(5, TimeUnit.SECONDS);

            assertNotEquals(firstWon, secondWon, "Exactly one CREATE_NEW writer must succeed");
            assertArrayEquals(firstWon ? firstBytes : secondBytes, Files.readAllBytes(target));
            assertArrayEquals(firstBytes, Files.readAllBytes(firstSource));
            assertArrayEquals(secondBytes, Files.readAllBytes(secondSource));
        }
    }

    @Test
    void completeSourceLargerThanOneBufferIsCopiedExactly() throws Exception {
        byte[] bytes = payload(8192 * 3 + 37, 41);
        Path assembled = Files.write(work.resolve("assembled.pending"), bytes);
        Path target = work.resolve("published.bin");

        LocalResumablePublication.publish(assembled, target, bytes.length);

        assertArrayEquals(bytes, Files.readAllBytes(target));
        assertArrayEquals(bytes, Files.readAllBytes(assembled));
    }

    @Test
    void oversizedSourceFailsAndRetainsItsPartialTarget() throws Exception {
        long expectedSize = 8192 + 17;
        byte[] bytes = payload((int) expectedSize + 1, 53);
        Path assembled = Files.write(work.resolve("assembled.pending"), bytes);
        Path target = work.resolve("published.bin");

        assertThrows(IOException.class,
                () -> LocalResumablePublication.publish(assembled, target, expectedSize));

        assertTrue(Files.exists(target), "Uncertain targets must remain available for manual verification");
        byte[] partial = Files.readAllBytes(target);
        assertTrue(partial.length > 0 && partial.length <= expectedSize);
        assertArrayEquals(Arrays.copyOf(bytes, partial.length), partial);
        assertArrayEquals(bytes, Files.readAllBytes(assembled));
        assertThrows(FileAlreadyExistsException.class,
                () -> LocalResumablePublication.publish(assembled, target, bytes.length));
        assertArrayEquals(partial, Files.readAllBytes(target));
    }

    @Test
    void shortSourceFailsAndRetainsItsPartialTarget() throws Exception {
        byte[] bytes = payload(8192 + 11, 67);
        Path assembled = Files.write(work.resolve("assembled.pending"), bytes);
        Path target = work.resolve("published.bin");

        assertThrows(IOException.class,
                () -> LocalResumablePublication.publish(assembled, target, bytes.length + 1L));

        assertTrue(Files.exists(target), "A failed copy must not delete any destination path");
        assertArrayEquals(bytes, Files.readAllBytes(target));
        assertArrayEquals(bytes, Files.readAllBytes(assembled));
        assertThrows(FileAlreadyExistsException.class,
                () -> LocalResumablePublication.publish(assembled, target, bytes.length));
        assertArrayEquals(bytes, Files.readAllBytes(target));
    }

    @Test
    void symbolicLinkTargetIsRejectedWithoutTouchingItsDestination() throws Exception {
        byte[] bytes = payload(8192 + 19, 83);
        byte[] original = "protected existing file".getBytes(StandardCharsets.UTF_8);
        Path assembled = Files.write(work.resolve("assembled.pending"), bytes);
        Path destination = Files.write(work.resolve("original.bin"), original);
        Path target = work.resolve("published.bin");
        try {
            Files.createSymbolicLink(target, destination);
        } catch (IOException | UnsupportedOperationException unavailable) {
            if (System.getProperty("os.name", "").startsWith("Windows")) {
                assumeTrue(false, "Windows symlink privileges unavailable: " + unavailable.getClass().getSimpleName());
            }
            throw unavailable;
        }

        assertThrows(IOException.class,
                () -> LocalResumablePublication.publish(assembled, target, bytes.length));

        assertTrue(Files.isSymbolicLink(target));
        assertArrayEquals(original, Files.readAllBytes(destination));
        assertArrayEquals(bytes, Files.readAllBytes(assembled));
    }

    private static boolean attempt(Path assembled, Path target, long expectedSize,
                                   CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IOException("Publication test start timed out");
        try {
            LocalResumablePublication.publish(assembled, target, expectedSize);
            return true;
        } catch (FileAlreadyExistsException collision) {
            return false;
        }
    }

    private static byte[] payload(int length, int salt) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31 + salt);
        return bytes;
    }
}
