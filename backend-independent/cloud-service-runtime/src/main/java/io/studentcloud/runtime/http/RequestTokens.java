package io.studentcloud.runtime.http;

import io.studentcloud.common.ApplicationFault;
import jakarta.servlet.http.HttpServletRequest;

/** No URL or request-body tokens; browser compatibility is kept in a single HTTP adapter. */
public final class RequestTokens {
    public static final String BROWSER_HEADER = "tongligong-student-cloud-token";
    private RequestTokens() { }

    public static String extract(HttpServletRequest request) {
        String browser = request.getHeader(BROWSER_HEADER);
        String authorization = request.getHeader("Authorization");
        String bearer = null;
        if (authorization != null && !authorization.isBlank()) {
            if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) throw invalid();
            bearer = authorization.substring(7);
        }
        if (browser != null && browser.isBlank()) browser = null;
        if (browser != null && bearer != null && !browser.equals(bearer)) throw invalid();
        String token = browser != null ? browser : bearer;
        if (token != null && (token.length() > 512 || token.isBlank() || !token.equals(token.trim()))) throw invalid();
        return token;
    }

    private static ApplicationFault invalid() { return new ApplicationFault("42000", 401, "登录凭据无效，请重新登录"); }
}
