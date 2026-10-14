package im.tlgxy.cloud.core.cache.overview;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.module.admin.service.GlobalOverviewService.DailyLogin;
import im.tlgxy.cloud.module.admin.service.GlobalOverviewService.LoginSummary;
import im.tlgxy.cloud.module.admin.service.GlobalOverviewService.RecentLogin;
import im.tlgxy.cloud.module.admin.service.GlobalOverviewService.Statuses;
import im.tlgxy.cloud.module.admin.service.GlobalOverviewService.Workspace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Only explicitly typed aggregate statistics enter Redis. Authorization and JVM status are never cached here. */
@Slf4j
public final class GlobalOverviewCache {
    public record Statistics(long workspaces, long members, long workspaceAdmins, long storageSources,
                             long shares, long pendingAgentTasks, long generatedAt, String timeZone,
                             Statuses statuses, LoginSummary loginSummary, List<DailyLogin> dailyLogins) {
        public Statistics { dailyLogins = List.copyOf(dailyLogins); }
    }
    /** Account IDs, workspace names and recent usernames remain inside the current process only. */
    public record Details(List<Workspace> preview, List<RecentLogin> recentLogins) {
        public Details { preview = List.copyOf(preview); recentLogins = List.copyOf(recentLogins); }
    }
    private record Payload(int schema, Statistics statistics) { }
    private record Entry<T>(T value, long expiresAt) { }
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final long ttlMillis;
    private final StringRedisTemplate redis;
    private final String key;
    private final LongSupplier clock;
    private final Object statisticsMonitor = new Object();
    private final Object detailsMonitor = new Object();
    private volatile Entry<Statistics> statistics;
    private volatile Entry<Details> details;
    private volatile long retryRedisAt = Long.MIN_VALUE;

    public GlobalOverviewCache(Duration ttl, StringRedisTemplate redis, String prefix) {
        this(ttl, redis, prefix, System::currentTimeMillis);
    }

    GlobalOverviewCache(Duration ttl, StringRedisTemplate redis, String prefix, LongSupplier clock) {
        this.ttlMillis = ttl.toMillis();
        this.redis = redis;
        this.key = prefix + "statistics";
        this.clock = clock;
    }

    /** For lightweight service unit construction only; production configuration validates a 1-30 second TTL. */
    public static GlobalOverviewCache uncached() { return new GlobalOverviewCache(Duration.ZERO, null, ""); }

    public Statistics statistics(Supplier<Statistics> loader) {
        long now = clock.getAsLong();
        Entry<Statistics> hit = statistics;
        if (validLocal(hit, now)) return hit.value();
        synchronized (statisticsMonitor) {
            now = clock.getAsLong();
            hit = statistics;
            if (validLocal(hit, now)) return hit.value();
            Statistics shared = readShared(now);
            if (shared != null) {
                statistics = new Entry<>(shared, shared.generatedAt() + ttlMillis);
                return shared;
            }
            // Loader failure propagates, never cached as an empty or stale-success response.
            Statistics loaded = loader.get();
            long finished = clock.getAsLong();
            if (validStatistics(loaded, finished)) {
                statistics = new Entry<>(loaded, loaded.generatedAt() + ttlMillis);
                writeShared(loaded, finished);
            }
            return loaded;
        }
    }

    public Details details(Supplier<Details> loader) {
        long now = clock.getAsLong();
        Entry<Details> hit = details;
        if (hit != null && now < hit.expiresAt()) return hit.value();
        synchronized (detailsMonitor) {
            now = clock.getAsLong();
            hit = details;
            if (hit != null && now < hit.expiresAt()) return hit.value();
            Details loaded = loader.get();
            if (ttlMillis > 0) details = new Entry<>(loaded, now + ttlMillis);
            return loaded;
        }
    }

    private boolean validLocal(Entry<Statistics> hit, long now) {
        return hit != null && now < hit.expiresAt() && validStatistics(hit.value(), now);
    }

    private boolean validStatistics(Statistics value, long now) {
        return value != null && ttlMillis > 0 && value.generatedAt() <= now
                && now - value.generatedAt() < ttlMillis
                && ZoneId.systemDefault().getId().equals(value.timeZone())
                && value.statuses() != null && value.loginSummary() != null && value.dailyLogins().size() == 7;
    }

    private Statistics readShared(long now) {
        if (redis == null || ttlMillis == 0 || now < retryRedisAt) return null;
        try {
            String value = redis.opsForValue().get(key);
            if (value == null) return null;
            if (value.length() > 16_384) throw new IllegalArgumentException("Oversized overview cache payload");
            Payload payload = JSON.readValue(value, Payload.class);
            return payload.schema() == 1 && validStatistics(payload.statistics(), now) ? payload.statistics() : null;
        } catch (Exception failure) { unavailable(now, failure); return null; }
    }

    private void writeShared(Statistics value, long now) {
        if (redis == null || now < retryRedisAt) return;
        try {
            // A slow older loader must not overwrite a newer snapshot from another process.
            redis.opsForValue().setIfAbsent(key, JSON.writeValueAsString(new Payload(1, value)),
                    Duration.ofMillis(Math.max(1, ttlMillis - (now - value.generatedAt()))));
        } catch (Exception failure) { unavailable(now, failure); }
    }

    private void unavailable(long now, Exception failure) {
        if (now >= retryRedisAt) log.warn("Overview cache unavailable; using bounded local cache ({}).", failure.getClass().getSimpleName());
        retryRedisAt = now + 1_000;
    }
}
