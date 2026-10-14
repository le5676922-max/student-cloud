package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.module.storage.model.enums.FileTypeEnum;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class DirectoryMetadataCacheTest {
    @Test void copiedSnapshotNeverRetainsUrlsOrMutableDatesAndExpires() {
        AtomicLong clock = new AtomicLong(); AtomicInteger calls = new AtomicInteger(); Object driver = new Object();
        var cache = new DirectoryMetadataCache(Duration.ofSeconds(5), clock::get);
        var row = item("original"); row.setUrl("https://signed.example.test/secret");
        var loader = (java.util.function.Supplier<List<FileItemResult>>) () -> { calls.incrementAndGet(); return List.of(row); };
        var first = cache.list(driver, 1, "/user", "/", false, loader);
        assertNull(first.getFirst().getUrl()); first.getFirst().setName("changed"); first.getFirst().getTime().setTime(0);
        row.setName("database changed"); row.getTime().setTime(8);
        var second = cache.list(driver, 1, "/user", "/", false, loader);
        assertEquals("original", second.getFirst().getName()); assertEquals(7, second.getFirst().getTime().getTime());
        assertEquals(1, calls.get()); clock.set(Duration.ofSeconds(6).toNanos());
        assertEquals("database changed", cache.list(driver, 1, "/user", "/", false, loader).getFirst().getName());
        assertEquals(2, calls.get());
    }
    @Test void accountRootDirectoryAndDriverReplacementAreSeparateKeys() {
        var cache = new DirectoryMetadataCache(); Object driver = new Object(); AtomicInteger reads = new AtomicInteger();
        var load = (java.util.function.Supplier<List<FileItemResult>>) () -> { reads.incrementAndGet(); return List.of(item("row")); };
        cache.list(driver, 1, "/one", "/", false, load); cache.list(driver, 2, "/one", "/", false, load);
        cache.list(driver, 1, "/two", "/", false, load); cache.list(driver, 1, "/one", "/nested", false, load);
        cache.list(new Object(), 1, "/one", "/", false, load); assertEquals(5, reads.get());
    }
    @Test void explicitRefreshAndMutationInvalidateSnapshots() {
        var cache = new DirectoryMetadataCache(); Object driver = new Object(); AtomicInteger reads = new AtomicInteger();
        var load = (java.util.function.Supplier<List<FileItemResult>>) () -> List.of(item("row" + reads.incrementAndGet()));
        cache.list(driver, 1, "/", "/", false, load);
        assertEquals("row2", cache.list(driver, 1, "/", "/", true, load).getFirst().getName());
        cache.invalidate(); assertEquals("row3", cache.list(driver, 1, "/", "/", false, load).getFirst().getName());
    }
    @Test void inflightReadCannotRefillAfterInvalidationAndUnrelatedHitsDoNotWaitForIo() throws Exception {
        var cache = new DirectoryMetadataCache(); Object driver = new Object();
        cache.list(driver, 1, "/", "/warm", false, () -> List.of(item("warm")));
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var old = workers.submit(() -> cache.list(driver, 1, "/", "/cold", false, () -> {
                started.countDown(); try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new IllegalStateException(e); } return List.of(item("old"));
            }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var hit = workers.submit(() -> cache.list(driver, 1, "/", "/warm", false, () -> { throw new AssertionError(); }));
            assertEquals("warm", hit.get(1, TimeUnit.SECONDS).getFirst().getName());
            cache.invalidate(); release.countDown(); old.get(2, TimeUnit.SECONDS);
            assertEquals("new", cache.list(driver, 1, "/", "/cold", false, () -> List.of(item("new"))).getFirst().getName());
        } finally { release.countDown(); }
    }
    @Test void hugeDirectoriesAreReturnedButNotRetainedAndExceptionsAreNotCached() {
        var cache = new DirectoryMetadataCache(); Object driver = new Object(); AtomicInteger reads = new AtomicInteger();
        var load = (java.util.function.Supplier<List<FileItemResult>>) () -> { reads.incrementAndGet(); return Collections.nCopies(20_001, item("large")); };
        assertEquals(20_001, cache.list(driver, 1, "/", "/", false, load).size());
        cache.list(driver, 1, "/", "/", false, load); assertEquals(2, reads.get());
        assertThrows(IllegalStateException.class, () -> cache.list(driver, 1, "/", "/bad", false, () -> { throw new IllegalStateException(); }));
        assertEquals("ok", cache.list(driver, 1, "/", "/bad", false, () -> List.of(item("ok"))).getFirst().getName());
    }
    @Test void directoryCountAndTextBudgetAreBounded() {
        var cache = new DirectoryMetadataCache(); Object driver = new Object();
        for (int i = 0; i < 33; i++) cache.list(driver, 1, "/", "/" + i, false, () -> List.of(item("row")));
        assertEquals("evicted", cache.list(driver, 1, "/", "/0", false, () -> List.of(item("evicted"))).getFirst().getName());
        String largeName = "x".repeat(5_000_001); AtomicInteger reads = new AtomicInteger();
        var load = (java.util.function.Supplier<List<FileItemResult>>) () -> { reads.incrementAndGet(); return List.of(item(largeName)); };
        cache.list(driver, 1, "/", "/long", false, load); cache.list(driver, 1, "/", "/long", false, load); assertEquals(2, reads.get());
    }
    static FileItemResult item(String name) {
        FileItemResult item = new FileItemResult(); item.setName(name); item.setPath("/");
        item.setSize(1L); item.setTime(new Date(7)); item.setType(FileTypeEnum.FILE); return item;
    }
}
