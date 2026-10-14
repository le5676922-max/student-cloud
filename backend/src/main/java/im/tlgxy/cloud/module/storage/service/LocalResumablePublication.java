package im.tlgxy.cloud.module.storage.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Exclusive target creation; an uncertain or partial target is retained for manual verification. */
public final class LocalResumablePublication {
    private static final int BUFFER_SIZE = 8192;

    private LocalResumablePublication() { }

    /**
     * Copy through one CREATE_NEW output handle, never replacing or deleting a target.
     * The caller must durably claim publication before calling and must not replay a failure.
     * A target can be visible while its bytes are still being copied.
     */
    public static void publish(Path assembled, Path target, long expectedSize) throws IOException {
        if (expectedSize < 0) throw new IOException("Invalid publication length");
        try (InputStream input = Files.newInputStream(assembled, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long copied = 0;
            int count;
            while (true) {
                long remaining = expectedSize - copied;
                // At EOF allow exactly one probe byte; this also avoids overflow for very large lengths.
                int limit = remaining >= buffer.length ? buffer.length : (int) remaining + 1;
                count = input.read(buffer, 0, limit);
                if (count == -1) break;
                if (count > remaining) throw new IOException("Publication source exceeds expected length");
                output.write(buffer, 0, count);
                copied += count;
            }
            if (copied != expectedSize) throw new IOException("Publication source is shorter than expected length");
        }
        // Both close operations have succeeded before the caller can commit a completed receipt.
    }
}
