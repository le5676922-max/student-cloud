package im.tlgxy.cloud.module.storage.controller.helper;

import im.tlgxy.cloud.core.exception.core.BizException;
import jakarta.annotation.PreDestroy;
import org.apache.http.conn.DnsResolver;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Public-only endpoint policy for credential-bearing, user-supplied S3 helper requests. */
@Component
public class S3HelperEndpointPolicy {

    private final DnsResolver resolver;
    private final ThreadPoolExecutor dnsExecutor;

    public S3HelperEndpointPolicy() {
        dnsExecutor = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8), runnable -> {
                    Thread thread = new Thread(runnable, "s3-helper-dns");
                    thread.setDaemon(true);
                    return thread;
                });
        resolver = host -> {
            var future = dnsExecutor.submit(() -> InetAddress.getAllByName(host));
            try {
                return future.get(3, TimeUnit.SECONDS);
            } catch (Exception exception) {
                future.cancel(true);
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new UnknownHostException("S3 endpoint DNS unavailable");
            }
        };
    }

    // Tests supply synthetic DNS records; no real provider or external DNS is required.
    S3HelperEndpointPolicy(DnsResolver resolver) {
        this.resolver = resolver;
        this.dnsExecutor = null;
    }

    public ValidatedEndpoint validate(String suppliedEndpoint) {
        try {
            if (suppliedEndpoint == null || suppliedEndpoint.isBlank() || suppliedEndpoint.length() > 2048) {
                throw invalidEndpoint();
            }
            String input = suppliedEndpoint.trim();
            // Preserve bare-host configuration while avoiding default plaintext credential transport.
            URI endpoint = URI.create(input.contains("://") ? input : "https://" + input);
            String scheme = endpoint.getScheme();
            String host = endpoint.getHost();
            if ((!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))
                    || host == null || endpoint.getRawUserInfo() != null
                    || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                    || (endpoint.getRawPath() != null && !endpoint.getRawPath().isEmpty()
                    && !"/".equals(endpoint.getRawPath()))
                    || endpoint.getPort() == 0 || endpoint.getPort() > 65535) {
                throw invalidEndpoint();
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            String unbracketedHost = normalizedHost.replace("[", "").replace("]", "");
            if (unbracketedHost.equals("localhost") || unbracketedHost.endsWith(".localhost")
                    || unbracketedHost.endsWith(".local") || unbracketedHost.endsWith(".internal")) {
                throw invalidEndpoint();
            }
            InetAddress[] addresses = resolver.resolve(unbracketedHost);
            if (addresses == null || addresses.length == 0) throw invalidEndpoint();
            // Reject mixed public/private records rather than letting the transport choose a private one.
            for (InetAddress address : addresses) {
                if (address == null || !isPublicAddress(address)) throw invalidEndpoint();
            }
            return new ValidatedEndpoint(endpoint, addresses);
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            // Do not echo endpoints, DNS results or credentials to the browser/logs.
            throw invalidEndpoint();
        }
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        if (bytes.length == 4) {
            int third = Byte.toUnsignedInt(bytes[2]);
            return first != 0 && first != 10 && first != 127 && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254)
                    && !(first == 172 && second >= 16 && second <= 31)
                    && !(first == 192 && second == 168)
                    && !(first == 192 && second == 0 && (third == 0 || third == 2))
                    && !(first == 192 && second == 88 && third == 99)
                    && !(first == 198 && (second == 18 || second == 19))
                    && !(first == 198 && second == 51 && third == 100)
                    && !(first == 203 && second == 0 && third == 113);
        }
        if (bytes.length != 16 || (first & 0xe0) != 0x20) return false;
        int third = Byte.toUnsignedInt(bytes[2]);
        int fourth = Byte.toUnsignedInt(bytes[3]);
        // Global-unicast only; exclude special-purpose, documentation and IPv4-tunnel ranges.
        return !(first == 0x20 && second == 0x01 && third <= 1)
                && !(first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8)
                && !(first == 0x20 && second == 0x02)
                && !(first == 0x3f && second == 0xff && (third & 0xf0) == 0);
    }

    private static BizException invalidEndpoint() {
        return new BizException("S3 辅助接口仅允许有效的公网 HTTP/HTTPS 端点，不支持内网地址或路径参数");
    }

    @PreDestroy
    public void close() {
        if (dnsExecutor != null) dnsExecutor.shutdownNow();
    }

    public record ValidatedEndpoint(URI uri, InetAddress[] addresses) {
        public ValidatedEndpoint {
            addresses = addresses.clone();
        }

        @Override
        public InetAddress[] addresses() {
            return addresses.clone();
        }

        public DnsResolver pinnedResolver() {
            String expectedHost = uri.getHost().replace("[", "").replace("]", "");
            return host -> {
                if (!expectedHost.equalsIgnoreCase(host.replace("[", "").replace("]", ""))) {
                    throw new UnknownHostException("S3 helper endpoint changes are forbidden");
                }
                // Never resolve again: defeats DNS rebinding between validation and connection.
                return addresses();
            };
        }
    }
}
