package im.tlgxy.cloud.core.util;

import cn.hutool.cache.Cache;
import cn.hutool.cache.CacheUtil;
import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;

/** A separate, short-lived credential for Document Server fetching an authenticated preview. */
public final class OnlyOfficeDownloadTokenUtils {
    public static final String PARAMETER = "officeDownloadToken";
    private static final long LIFETIME_MILLIS = 30 * 60 * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Cache<String, Ticket> TICKETS = CacheUtil.newLRUCache(10000, LIFETIME_MILLIS);

    private OnlyOfficeDownloadTokenUtils() { }

    private record Ticket(String resource, int viewer, long expiresAt) { }

    public static String documentUrl(String url, int verifiedViewer) {
        if (verifiedViewer <= 0 || verifiedViewer == 2) throw new IllegalArgumentException("A login viewer is required");
        URI uri = URI.create(url);
        // Third-party storage URLs already have their own provider credentials.
        if (!uri.getPath().startsWith("/pd/")) return url;
        if (uri.getFragment() != null || uri.getRawQuery() != null && uri.getRawQuery().contains(PARAMETER + "=")) {
            throw new IllegalArgumentException("Invalid document download URL");
        }
        byte[] entropy = new byte[32];
        RANDOM.nextBytes(entropy);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        String resource = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        TICKETS.put(token, new Ticket(resource, verifiedViewer, System.currentTimeMillis() + LIFETIME_MILLIS));
        return url + (uri.getRawQuery() == null ? "?" : "&") + PARAMETER + "=" + token;
    }

    /** Does not create a session or grant access to any other endpoint; fresh account checks follow. */
    public static Integer authenticatedViewer(HttpServletRequest request) {
        String token = request.getParameter(PARAMETER);
        if (token == null || token.length() != 43) return null;
        Ticket ticket = TICKETS.get(token, false);
        if (ticket == null || System.currentTimeMillis() >= ticket.expiresAt()) return null;
        String query = request.getQueryString();
        String suffix = PARAMETER + "=" + token;
        if (query == null || !query.endsWith(suffix)) return null;
        String originalQuery = query.substring(0, query.length() - suffix.length());
        if (originalQuery.endsWith("&")) originalQuery = originalQuery.substring(0, originalQuery.length() - 1);
        if (originalQuery.contains(PARAMETER + "=")) return null;
        String resource = request.getRequestURI() + (originalQuery.isEmpty() ? "" : "?" + originalQuery);
        return ticket.resource().equals(resource) ? ticket.viewer() : null;
    }
}
