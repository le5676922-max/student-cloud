package im.tlgxy.cloud.core.cache.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrustedClientIpResolverTest {
    @Test
    void untrustedAndDefaultPeersIgnoreSpoofedForwardingHeaders() {
        var request = request("203.0.113.8", "192.0.2.1");
        request.addHeader("X-Real-IP", "192.0.2.99");
        assertEquals("203.0.113.8", new TrustedClientIpResolver(List.of()).resolve(request));
        assertEquals("203.0.113.8", new TrustedClientIpResolver(List.of("172.30.0.0/24")).resolve(request));
    }

    @Test
    void trustedChainStopsAtFirstUntrustedHopInsteadOfAttackerControlledLeftmost() {
        var resolver = new TrustedClientIpResolver(List.of("172.30.0.0/24", "10.1.0.2/32"));
        assertEquals("203.0.113.8", resolver.resolve(request("172.30.0.5", "192.0.2.99, 203.0.113.8, 10.1.0.2")));
        assertEquals("203.0.113.8", resolver.resolve(request("172.30.0.5", "203.0.113.8")));
    }

    @Test
    void ipv6IsCanonicalAndMalformedHeaderFallsBackToSocketPeerWithoutDns() {
        var resolver = new TrustedClientIpResolver(List.of("2001:db8:1::/64"));
        assertEquals("2001:db8:2:0:0:0:0:3", resolver.resolve(request("2001:db8:1::1", "2001:db8:2::3")));
        for (String invalid : List.of("private.internal", "1.2.3.4:80", "1.2.3.4,", "001.2.3.4", "fe80::1%eth0")) {
            assertEquals("2001:db8:1:0:0:0:0:1", resolver.resolve(request("2001:db8:1::1", invalid)));
        }
        assertThrows(IllegalArgumentException.class, () -> new TrustedClientIpResolver(List.of("0.0.0.0/0")));
        assertThrows(IllegalArgumentException.class, () -> new TrustedClientIpResolver(List.of("proxy.example.invalid")));
    }

    private MockHttpServletRequest request(String peer, String forwarded) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        request.addHeader("X-Forwarded-For", forwarded);
        return request;
    }
}
