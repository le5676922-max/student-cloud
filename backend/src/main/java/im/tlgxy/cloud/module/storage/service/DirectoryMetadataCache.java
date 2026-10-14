package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Small per-instance snapshot cache; authorization/filtering are deliberately outside this cache. */
@Component
public class DirectoryMetadataCache {
    private static final int MAX_DIRECTORIES = 32, MAX_ITEMS = 50_000, MAX_DIRECTORY_ITEMS = 20_000;
    private static final long MAX_TEXT_CHARS = 5_000_000;
    private final Map<Key, Entry> entries = new LinkedHashMap<>();
    private final LongSupplier clock;
    private final long ttl;
    private long generation;

    public DirectoryMetadataCache() { this(Duration.ofSeconds(5), System::nanoTime); }
    DirectoryMetadataCache(Duration ttl, LongSupplier clock) { this.ttl = ttl.toNanos(); this.clock = clock; }

    public List<FileItemResult> list(Object driver, int userId, String root, String directory,
                                     boolean refresh, Supplier<List<FileItemResult>> loader) {
        Key key = new Key(driver, userId, root, directory);
        long before;
        synchronized (entries) {
            removeExpired();
            Entry hit = entries.get(key);
            if (!refresh && hit != null) return copy(hit.files);
            // An in-flight old read must not refill the cache after an explicit refresh.
            if (refresh) { entries.remove(key); generation++; }
            before = generation;
        }
        List<FileItemResult> loaded = loader.get(); // No remote I/O while holding the global monitor.
        List<FileItemResult> snapshot = copy(loaded);
        long chars = textWeight(snapshot);
        synchronized (entries) {
            removeExpired();
            if (generation == before && snapshot.size() <= MAX_DIRECTORY_ITEMS && chars <= MAX_TEXT_CHARS) {
                entries.remove(key);
                while (!entries.isEmpty() && (entries.size() >= MAX_DIRECTORIES
                        || itemCount() + snapshot.size() > MAX_ITEMS || textCount() + chars > MAX_TEXT_CHARS)) {
                    entries.remove(entries.keySet().iterator().next());
                }
                entries.put(key, new Entry(snapshot, clock.getAsLong() + ttl, chars));
            }
        }
        return copy(snapshot);
    }

    public void invalidate() { synchronized (entries) { generation++; entries.clear(); } }
    /** Mutation fence only; no metadata/grants are exposed to cursor consumers. */
    public long generation() { synchronized (entries) { return generation; } }
    private void removeExpired() { long now = clock.getAsLong(); entries.values().removeIf(e -> now >= e.expires); }
    private int itemCount() { return entries.values().stream().mapToInt(e -> e.files.size()).sum(); }
    private long textCount() { return entries.values().stream().mapToLong(e -> e.chars).sum(); }
    private static long textWeight(List<FileItemResult> files) {
        return files.stream().mapToLong(f -> (f.getName() == null ? 0 : f.getName().length())
                + (f.getPath() == null ? 0 : f.getPath().length())).sum();
    }
    private static List<FileItemResult> copy(List<FileItemResult> files) {
        List<FileItemResult> result = new ArrayList<>(files.size());
        for (FileItemResult file : files) {
            FileItemResult item = new FileItemResult();
            item.setName(file.getName()); item.setPath(file.getPath()); item.setType(file.getType()); item.setSize(file.getSize());
            item.setTime(file.getTime() == null ? null : new Date(file.getTime().getTime()));
            // Never retain a signed URL, including if a provider accidentally returns one.
            result.add(item);
        }
        return result;
    }
    private record Entry(List<FileItemResult> files, long expires, long chars) { }
    private static final class Key {
        private final Object driver;
        private final int user;
        private final String root, directory;
        private Key(Object driver, int user, String root, String directory) {
            this.driver = driver; this.user = user; this.root = root; this.directory = directory;
        }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && driver == key.driver && user == key.user
                    && Objects.equals(root, key.root) && Objects.equals(directory, key.directory);
        }
        @Override public int hashCode() { return Objects.hash(System.identityHashCode(driver), user, root, directory); }
    }
}
