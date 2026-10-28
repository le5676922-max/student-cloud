package io.studentcloud.gateway;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Signed transport context for per-client budgets, never an authentication or authorization claim. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SignedClientContextFilter implements WebFilter {
    private final byte[] key;
    private final Set<String> trustedProxies;
    private final Clock clock = Clock.systemUTC();

    public SignedClientContextFilter(@Value("${student-cloud.services.internal-key}") String secret,
            @Value("${student-cloud.gateway.trusted-proxy-ips:}") String configuredProxies) {
        if (secret == null || secret.length() < 32 || secret.length() > 200
                || secret.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Configure an internal service key with at least 32 characters");
        }
        key = secret.getBytes(StandardCharsets.UTF_8);
        Set<String> configured = new HashSet<>();
        for (String entry : Arrays.stream(configuredProxies.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList()) {
            String address = numericIp(entry);
            if (address == null || configured.size() >= 16) throw new IllegalArgumentException("Configure exact trusted proxy IP addresses");
            configured.add(address);
        }
        trustedProxies = Set.copyOf(configured);
    }

    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        InetSocketAddress peer = exchange.getRequest().getRemoteAddress();
        String socketIp = peer == null || peer.getAddress() == null ? null : peer.getAddress().getHostAddress();
        if (socketIp != null && socketIp.indexOf('%') >= 0) socketIp = socketIp.substring(0, socketIp.indexOf('%'));
        String client = numericIp(socketIp);
        if (client == null) return GatewayReplies.failure(exchange, HttpStatus.SERVICE_UNAVAILABLE, "50300", "请求来源暂不可用");
        if (trustedProxies.contains(client)) {
            List<String> values = exchange.getRequest().getHeaders().get("X-Real-IP");
            if (values != null && values.size() == 1) {
                String forwarded = numericIp(values.getFirst());
                if (forwarded != null) client = forwarded;
            }
        }
        String ip = client;
        String at = Long.toString(clock.millis());
        String payload = at + '\n' + ip + '\n' + exchange.getRequest().getMethod().name()
                + '\n' + exchange.getRequest().getURI().getRawPath();
        String signature = sign(payload);
        var request = exchange.getRequest().mutate().headers(headers -> {
            headers.set("X-StudentCloud-Client-Ip", ip);
            headers.set("X-StudentCloud-Client-At", at);
            headers.set("X-StudentCloud-Client-Signature", signature);
        }).build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    private String sign(String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException unavailable) { throw new IllegalStateException("Client context signing is unavailable", unavailable); }
    }

    /** Numeric literals only: getByName never receives a hostname and therefore cannot perform DNS lookups. */
    private static String numericIp(String text) {
        if (text == null || text.isBlank() || text.length() > 64 || !text.equals(text.trim())) return null;
        if (text.indexOf('%') >= 0) return null;
        if (text.indexOf(':') < 0) {
            if (!text.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}")) return null;
            for (String part : text.split("\\.")) if (Integer.parseInt(part) > 255) return null;
        } else if (!text.matches("[A-Fa-f0-9:.]{2,45}")) return null;
        try { return InetAddress.getByName(text).getHostAddress(); }
        catch (Exception invalid) { return null; }
    }
}
