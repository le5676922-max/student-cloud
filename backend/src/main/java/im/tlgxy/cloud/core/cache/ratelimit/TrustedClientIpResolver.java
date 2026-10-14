package im.tlgxy.cloud.core.cache.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/** Trust only explicit proxy CIDRs and walk XFF from the socket peer towards the first untrusted hop. */
public final class TrustedClientIpResolver {
    private final List<Network> proxies;

    public TrustedClientIpResolver(List<String> cidrs) {
        List<Network> networks = new ArrayList<>();
        if (cidrs != null) for (String cidr : cidrs) {
            if (cidr == null || cidr.isBlank()) continue;
            String[] parts = cidr.trim().split("/", -1);
            byte[] address = literal(parts[0]);
            if (address == null || parts.length > 2) throw new IllegalArgumentException("Invalid trusted proxy CIDR");
            int bits;
            try { bits = parts.length == 1 ? address.length * 8 : Integer.parseInt(parts[1]); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid trusted proxy CIDR"); }
            if (bits < 1 || bits > address.length * 8) throw new IllegalArgumentException("Invalid trusted proxy CIDR");
            networks.add(new Network(address, bits));
        }
        proxies = List.copyOf(networks);
    }

    public String resolve(HttpServletRequest request) {
        String peer = normalize(request.getRemoteAddr());
        if (peer == null) return "unknown-peer";
        if (!trusted(peer)) return peer;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank() || forwarded.length() > 1024) return peer;
        String[] hops = forwarded.split(",", -1);
        if (hops.length > 32) return peer;
        List<String> valid = new ArrayList<>(hops.length);
        for (String hop : hops) {
            String normalized = normalize(hop.trim());
            if (normalized == null) return peer;
            valid.add(normalized);
        }
        String current = peer;
        for (int index = valid.size() - 1; index >= 0 && trusted(current); index--) current = valid.get(index);
        return current;
    }

    private boolean trusted(String address) {
        byte[] value = literal(address);
        return value != null && proxies.stream().anyMatch(network -> network.matches(value));
    }

    private static String normalize(String address) {
        byte[] value = literal(address);
        if (value == null) return null;
        try { return InetAddress.getByAddress(value).getHostAddress(); }
        catch (Exception impossible) { return null; }
    }

    private static byte[] literal(String address) {
        if (address == null || address.length() > 64 || address.isBlank()) return null;
        // DNS names, port suffixes, scoped IPv6 and header control characters are never resolved.
        if (address.indexOf(':') >= 0) {
            if (!address.matches("[0-9a-fA-F:.]+")) return null;
            try { return InetAddress.getByName(address).getAddress(); }
            catch (Exception invalid) { return null; }
        }
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] result = new byte[4];
        try {
            for (int index = 0; index < 4; index++) {
                if (!parts[index].matches("0|[1-9][0-9]{0,2}")) return null;
                int value = Integer.parseInt(parts[index]);
                if (value > 255) return null;
                result[index] = (byte) value;
            }
            return result;
        } catch (NumberFormatException invalid) { return null; }
    }

    private record Network(byte[] address, int bits) {
        boolean matches(byte[] other) {
            if (address.length != other.length) return false;
            int whole = bits / 8;
            for (int index = 0; index < whole; index++) if (address[index] != other[index]) return false;
            int remaining = bits % 8;
            return remaining == 0 || ((address[whole] ^ other[whole]) & (0xff << (8 - remaining))) == 0;
        }
    }
}
