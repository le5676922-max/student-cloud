package im.tlgxy.cloud.module.storage.controller.helper;

import im.tlgxy.cloud.core.exception.core.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class S3HelperEndpointPolicyTest {

    private final InetAddress publicAddress = ipv4(8, 8, 8, 8);
    private final S3HelperEndpointPolicy policy = new S3HelperEndpointPolicy(host -> new InetAddress[]{publicAddress});

    @ParameterizedTest
    @ValueSource(strings = {"https://objects.example.com", "objects.example.com", "http://objects.example.com:9000/",
            "https://s3.cn-north-1.amazonaws.com.cn"})
    void acceptsPublicCustomEndpointWithoutContactingTheProvider(String endpoint) {
        var result = policy.validate(endpoint);
        assertArrayEquals(new InetAddress[]{publicAddress}, result.addresses());
    }

    @Test
    void bareHostDefaultsToHttps() {
        assertEquals("https", policy.validate("objects.example.com").uri().getScheme());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "file:///etc/passwd", "ftp://objects.example.com", "https://name:secret@objects.example.com",
            "https://objects.example.com/private", "https://objects.example.com?redirect=1", "https://objects.example.com#fragment",
            "https://objects.example.com:0", "https://objects.example.com:65536", "http://localhost", "http://host.local",
            "http://metadata.google.internal", "http://objects.example.com\\@127.0.0.1", "//127.0.0.1"})
    void rejectsUnsafeSyntaxBeforeDns(String endpoint) {
        AtomicInteger dnsCalls = new AtomicInteger();
        S3HelperEndpointPolicy observed = new S3HelperEndpointPolicy(host -> {
            dnsCalls.incrementAndGet();
            return new InetAddress[]{publicAddress};
        });
        assertThrows(BizException.class, () -> observed.validate(endpoint));
        assertEquals(0, dnsCalls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "10.1.2.3", "100.64.0.1", "127.0.0.1", "169.254.169.254", "172.16.0.1",
            "192.168.1.1", "192.0.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1",
            "224.0.0.1", "255.255.255.255", "::", "::1", "fe80::1", "fc00::1", "2001:db8::1", "2002:7f00:1::1",
            "2001::1", "3fff::1", "::ffff:127.0.0.1"})
    void rejectsNonPublicDnsRecords(String literal) throws Exception {
        // Numeric literals only: getByName does not contact an external DNS resolver.
        InetAddress denied = InetAddress.getByName(literal);
        S3HelperEndpointPolicy observed = new S3HelperEndpointPolicy(host -> new InetAddress[]{denied});
        assertThrows(BizException.class, () -> observed.validate("https://objects.example.com"));
    }

    @Test
    void rejectsMixedDnsAnswers() {
        S3HelperEndpointPolicy observed = new S3HelperEndpointPolicy(host ->
                new InetAddress[]{publicAddress, ipv4(127, 0, 0, 1)});
        assertThrows(BizException.class, () -> observed.validate("https://objects.example.com"));
    }

    @Test
    void pinsExactlyTheValidatedHostAndDefensivelyCopiesDnsResults() throws Exception {
        AtomicInteger dnsCalls = new AtomicInteger();
        InetAddress[] original = {publicAddress};
        S3HelperEndpointPolicy observed = new S3HelperEndpointPolicy(host -> {
            dnsCalls.incrementAndGet();
            return original;
        });
        var endpoint = observed.validate("https://objects.example.com");
        original[0] = ipv4(127, 0, 0, 1);
        InetAddress[] resolved = endpoint.pinnedResolver().resolve("OBJECTS.EXAMPLE.COM");
        assertEquals(publicAddress, resolved[0]);
        resolved[0] = ipv4(10, 0, 0, 1);
        assertEquals(publicAddress, endpoint.pinnedResolver().resolve("objects.example.com")[0]);
        assertThrows(UnknownHostException.class, () -> endpoint.pinnedResolver().resolve("redirect.example.com"));
        assertEquals(1, dnsCalls.get());
    }

    @Test
    void dnsFailuresAreSafeClientErrors() {
        S3HelperEndpointPolicy observed = new S3HelperEndpointPolicy(host -> { throw new UnknownHostException(host); });
        BizException exception = assertThrows(BizException.class, () -> observed.validate("https://objects.example.com"));
        assertFalse(exception.getMessage().contains("objects.example.com"));
    }

    @Test
    void acceptsPublicIpv6() throws Exception {
        assertTrue(S3HelperEndpointPolicy.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }

    private static InetAddress ipv4(int a, int b, int c, int d) {
        try {
            return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
        } catch (UnknownHostException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
