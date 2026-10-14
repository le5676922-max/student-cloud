package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.controller.helper.S3HelperEndpointPolicy;
import im.tlgxy.cloud.module.storage.model.dto.StorageSourceAllParamDTO;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fake WebDAV transport/public DNS result only, never a real endpoint or account. */
class WebDavReadOnlyProbeTest {
    private static final String COLLECTION = "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>";
    private final S3HelperEndpointPolicy policy = mock(S3HelperEndpointPolicy.class);

    @Test void validatesOriginAndReadsOnlyTargetDirectoryWithoutFollowingRedirects() throws Exception {
        var endpoint = new S3HelperEndpointPolicy.ValidatedEndpoint(URI.create("https://dav.example.test"),
                new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})});
        when(policy.validate("https://dav.example.test")).thenReturn(endpoint);
        var probe = new WebDavReadOnlyProbe(policy, (validated, directory, username, password) -> {
            assertSame(endpoint, validated);
            assertEquals("https://dav.example.test/dav/documents/", directory.toString());
            assertEquals("synthetic-user", username); assertEquals("synthetic-password", password);
            assertArrayEquals(endpoint.addresses(), validated.pinnedResolver().resolve("dav.example.test"));
            assertThrows(java.net.UnknownHostException.class, () -> validated.pinnedResolver().resolve("another.test"));
            return new WebDavReadOnlyProbe.Response(207, COLLECTION.getBytes(StandardCharsets.UTF_8));
        });
        probe.assertReadable(parameters());
        verify(policy).validate("https://dav.example.test");
    }

    @Test void privateOrRebindingEndpointIsRejectedBeforeTransport() {
        when(policy.validate(anyString())).thenThrow(new BizException("Public only"));
        var probe = new WebDavReadOnlyProbe(policy, (e, d, u, p) -> fail("Must not connect"));
        assertThrows(BizException.class, () -> probe.assertReadable(parameters()));
    }

    @Test void unsafeUrlCredentialsQueryFragmentAndTraversalAreRejectedBeforeDns() {
        var probe = new WebDavReadOnlyProbe(policy, (e, d, u, p) -> fail("Must not connect"));
        for (String unsafe : new String[]{"http://dav.example.test", "https://user:pass@dav.example.test",
                "https://dav.example.test?token=bad", "https://dav.example.test#fragment",
                "https://dav.example.test/../private", "https://dav.example.test/%2e%2e/private"}) {
            var parameters = parameters(); parameters.setUrl(unsafe);
            assertThrows(BizException.class, () -> probe.assertReadable(parameters));
        }
        var parameters = parameters(); parameters.setBasePath("/../private");
        assertThrows(BizException.class, () -> probe.assertReadable(parameters));
        verifyNoInteractions(policy);
    }

    @Test void providerRedirectAndErrorsNeverRetryOrEchoCredentials() throws Exception {
        availableEndpoint(); AtomicInteger calls = new AtomicInteger();
        var probe = new WebDavReadOnlyProbe(policy, (e, d, u, p) -> {
            calls.incrementAndGet(); return new WebDavReadOnlyProbe.Response(302, "synthetic-password".getBytes());
        });
        var error = assertThrows(BizException.class, () -> probe.assertReadable(parameters()));
        assertEquals(1, calls.get()); assertFalse(error.getMessage().contains("synthetic-password"));
    }

    @Test void oversizedResponseIsRejectedAndXxeCannotReadFilesOrConnect() throws Exception {
        availableEndpoint();
        var probe = new WebDavReadOnlyProbe(policy, (e, d, u, p) ->
                new WebDavReadOnlyProbe.Response(207, new byte[WebDavReadOnlyProbe.MAX_RESPONSE_BYTES + 1]));
        assertThrows(BizException.class, () -> probe.assertReadable(parameters()));
        String xxe = "<!DOCTYPE root [<!ENTITY external SYSTEM 'file:///must-not-read'>]><d:multistatus xmlns:d='DAV:'>&external;</d:multistatus>";
        assertThrows(BizException.class, () -> WebDavReadOnlyProbe.requireCollection(xxe.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void unreadableNonDirectoryAndForgedPropertyStatusAreRejected() {
        assertThrows(BizException.class, () -> WebDavReadOnlyProbe.requireCollection("<html/>".getBytes()));
        assertThrows(BizException.class, () -> WebDavReadOnlyProbe.requireCollection(COLLECTION.replace("200 OK", "403 Forbidden").getBytes()));
        assertThrows(BizException.class, () -> WebDavReadOnlyProbe.requireCollection(COLLECTION.replace("<d:collection/>", "").getBytes()));
    }

    private void availableEndpoint() throws Exception {
        when(policy.validate(anyString())).thenReturn(new S3HelperEndpointPolicy.ValidatedEndpoint(
                URI.create("https://dav.example.test"), new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})}));
    }
    private StorageSourceAllParamDTO parameters() {
        var parameters = new StorageSourceAllParamDTO(); parameters.setUrl("https://dav.example.test/dav/");
        parameters.setBasePath("/documents/"); parameters.setUsername("synthetic-user"); parameters.setPassword("synthetic-password");
        return parameters;
    }
}
