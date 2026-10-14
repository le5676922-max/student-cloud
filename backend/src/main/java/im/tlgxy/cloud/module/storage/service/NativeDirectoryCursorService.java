package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.model.request.base.FileListRequest;
import im.tlgxy.cloud.module.storage.model.result.FileInfoResult;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Opaque, actor/root/driver/generation-bound tickets; never stores grants, URLs, credentials or file lists. */
@Component
public final class NativeDirectoryCursorService {
    private static final int MAX_TICKETS = 4096, MAX_TOKEN_CHARS = 1_048_576;
    private final Map<String, Ticket> tickets = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final LongSupplier clock;
    private final long ttl;

    public NativeDirectoryCursorService() { this(Duration.ofMinutes(5), System::nanoTime); }
    NativeDirectoryCursorService(Duration ttl, LongSupplier clock) { this.ttl = ttl.toNanos(); this.clock = clock; }

    public record Position(String cursor, String providerToken, String previousCursor, int pageIndex) { }

    public synchronized Position resolve(FileListRequest request, Object driver, int actor, String root, long generation) {
        validate(request);
        removeExpired();
        Binding binding = new Binding(driver, actor, root, request.getStorageKey(), request.getPath(), request.getPageSize(), generation);
        String cursor = request.getPageCursor();
        if (cursor == null || cursor.isBlank()) {
            cursor = add(new Ticket(binding, null, null, 0, clock.getAsLong() + ttl));
        } else if (!cursor.matches("np_[A-Za-z0-9_-]{43}")) throw changed();
        Ticket ticket = tickets.get(cursor);
        if (ticket == null || !ticket.binding.matches(binding)) throw changed();
        return position(cursor, ticket);
    }

    public synchronized FileInfoResult complete(Position position, FileListRequest request, Object driver, int actor,
                                                 String root, long generation, List<FileItemResult> authorized, String passwordPattern,
                                                 String nextProviderToken) {
        removeExpired();
        Ticket current = tickets.get(position.cursor());
        Binding binding = new Binding(driver, actor, root, request.getStorageKey(), request.getPath(), request.getPageSize(), generation);
        if (current == null || !current.binding.matches(binding) || !Objects.equals(current.token, position.providerToken())) throw changed();
        if (authorized.size() > binding.pageSize) throw new BizException("存储源分页响应异常");
        String nextCursor = null;
        if (nextProviderToken != null) {
            if (nextProviderToken.isBlank() || nextProviderToken.length() > 8192) throw new BizException("存储源分页游标异常");
            if (current.nextCursor != null) {
                Ticket existing = tickets.get(current.nextCursor);
                if (existing == null || !nextProviderToken.equals(existing.token)) throw changed();
                nextCursor = current.nextCursor;
            } else {
                // Returning an ancestor token would repeat pages forever. Bounded history retains previous-page support.
                Ticket ancestor = current;
                while (ancestor != null) {
                    if (nextProviderToken.equals(ancestor.token)) throw new BizException("存储源分页游标循环，请刷新或切换普通浏览");
                    ancestor = ancestor.previous == null ? null : tickets.get(ancestor.previous);
                }
                nextCursor = add(new Ticket(binding, nextProviderToken, position.cursor(), current.index + 1, current.expires));
                current.nextCursor = nextCursor;
            }
        } else if (current.nextCursor != null) throw changed();
        FileInfoResult result = new FileInfoResult(List.copyOf(authorized), passwordPattern);
        result.setPaged(true); result.setNativePagination(true); result.setNativePaginationAvailable(true);
        result.setTotalKnown(false); result.setTotal(null); result.setPageSize(binding.pageSize);
        result.setPageIndex(current.index); result.setOffset(0);
        result.setNextCursor(nextCursor); result.setPreviousCursor(current.previous);
        return result;
    }

    public static void validate(FileListRequest request) {
        if (request.getPageSize() == null || request.getPageSize() < 1 || request.getPageSize() > 200
                || request.getNameQuery() != null && !request.getNameQuery().isBlank()
                || request.getOrderBy() != null && !request.getOrderBy().isBlank()
                || request.getOrderDirection() != null && !request.getOrderDirection().isBlank()) {
            throw new BizException("快速浏览不支持全局排序或搜索，请切换普通浏览");
        }
    }

    private String add(Ticket ticket) {
        int chars = tickets.values().stream().mapToInt(value -> value.token == null ? 0 : value.token.length()).sum();
        if (tickets.size() >= MAX_TICKETS || chars + (ticket.token == null ? 0 : ticket.token.length()) > MAX_TOKEN_CHARS) {
            throw new BizException("快速浏览会话已满，请稍后重试或切换普通浏览");
        }
        byte[] bytes = new byte[32];
        String cursor;
        do { random.nextBytes(bytes); cursor = "np_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
        while (tickets.containsKey(cursor));
        tickets.put(cursor, ticket);
        return cursor;
    }

    private void removeExpired() { long now = clock.getAsLong(); tickets.values().removeIf(ticket -> now >= ticket.expires); }
    private static Position position(String cursor, Ticket ticket) { return new Position(cursor, ticket.token, ticket.previous, ticket.index); }
    private static BizException changed() { return new BizException(ErrorCode.BIZ_DIRECTORY_CHANGED); }

    private record Binding(Object driver, int actor, String root, String storage, String path, int pageSize, long generation) {
        boolean matches(Binding other) {
            return driver == other.driver && actor == other.actor && pageSize == other.pageSize && generation == other.generation
                    && Objects.equals(root, other.root) && Objects.equals(storage, other.storage) && Objects.equals(path, other.path);
        }
    }
    private static final class Ticket {
        final Binding binding;
        final String token, previous;
        final int index;
        final long expires;
        String nextCursor;
        Ticket(Binding binding, String token, String previous, int index, long expires) {
            this.binding = binding; this.token = token; this.previous = previous; this.index = index; this.expires = expires;
        }
    }
}
