package im.tlgxy.cloud.core.util;

import cn.hutool.cache.Cache;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.*;

public class OnlyOfficeDownloadTokenUtilsTest {
    @Test
    void credentialIsViewerAndExactSignedUrlBound() {
        String original = "http://localhost/pd/files/root/test%20file.docx?signature=synthetic&type=default";
        String document = OnlyOfficeDownloadTokenUtils.documentUrl(original, 6);
        assertEquals(6, OnlyOfficeDownloadTokenUtils.authenticatedViewer(request(document)));
        assertNull(OnlyOfficeDownloadTokenUtils.authenticatedViewer(request(original)));
        assertNull(OnlyOfficeDownloadTokenUtils.authenticatedViewer(request(document.replace("test%20file.docx", "other.docx"))));
        assertNull(OnlyOfficeDownloadTokenUtils.authenticatedViewer(request(document.replace("signature=synthetic", "signature=substituted"))));
        assertNull(OnlyOfficeDownloadTokenUtils.authenticatedViewer(request(document + "&type=download")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void expiresWithoutExtendingTheCredentialOnUse() throws Exception {
        String document = OnlyOfficeDownloadTokenUtils.documentUrl("http://localhost/pd/files/test.docx?signature=synthetic", 6);
        MockHttpServletRequest request = request(document);
        String token = request.getParameter(OnlyOfficeDownloadTokenUtils.PARAMETER);
        Cache<String, Object> cache = (Cache<String, Object>) ReflectionTestUtils.getField(OnlyOfficeDownloadTokenUtils.class, "TICKETS");
        assertNotNull(cache);
        Object ticket = cache.get(token);
        Constructor<?> constructor = ticket.getClass().getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        String resource = "/pd/files/test.docx?signature=synthetic";
        cache.put(token, constructor.newInstance(resource, 6, System.currentTimeMillis() - 1));
        assertNull(OnlyOfficeDownloadTokenUtils.authenticatedViewer(request));
    }

    @Test
    void rejectsGuestAndLeavesProviderUrlsToTheirOwnCredentials() {
        assertThrows(IllegalArgumentException.class, () -> OnlyOfficeDownloadTokenUtils.documentUrl("http://localhost/pd/files/test.docx", 2));
        String provider = "https://provider.example/content/test.docx?providerSignature=synthetic";
        assertEquals(provider, OnlyOfficeDownloadTokenUtils.documentUrl(provider, 6));
    }

    public static MockHttpServletRequest request(String url) {
        URI uri = URI.create(url);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri.getRawPath());
        request.setServletPath(uri.getPath());
        request.setQueryString(uri.getRawQuery());
        if (uri.getRawQuery() != null) {
            for (String parameter : uri.getRawQuery().split("&")) {
                String[] pair = parameter.split("=", 2);
                if (pair.length == 2) request.addParameter(pair[0], pair[1]);
            }
        }
        return request;
    }
}
