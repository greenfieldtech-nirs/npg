package cloud.cloudonix.npg.security;

import cloud.cloudonix.npg.config.Env;
import io.vertx.core.http.HttpServerRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the real client address. Forwarding headers are only believed when the direct peer is
 * a configured trusted proxy (our NGINX); from anyone else they are attacker-controlled and ignored.
 *
 * <p>TRUSTED_PROXIES is a comma-separated list of CIDRs; the keyword {@code private} expands to
 * loopback plus RFC 1918 / unique-local ranges (the usual Docker-network case). Default: none.
 */
public final class ClientAddress {

    private static final List<String> PRIVATE_RANGES = List.of(
        "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "::1/128", "fc00::/7");

    private final List<Cidr> trusted;

    public ClientAddress(List<String> trustedCidrs) {
        List<Cidr> parsed = new ArrayList<>();
        for (String entry : trustedCidrs) {
            String value = entry.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (value.equalsIgnoreCase("private")) {
                PRIVATE_RANGES.forEach(r -> parsed.add(Cidr.parse(r)));
            } else {
                parsed.add(Cidr.parse(value));
            }
        }
        this.trusted = List.copyOf(parsed);
    }

    public static ClientAddress fromEnvironment() {
        return new ClientAddress(List.of(Env.get("TRUSTED_PROXIES", "").split(",")));
    }

    public String resolve(HttpServerRequest request) {
        String peer = request.remoteAddress() != null ? request.remoteAddress().host() : null;
        return resolve(peer, request.getHeader("X-Real-IP"), request.getHeader("X-Forwarded-For"));
    }

    String resolve(String peer, String realIpHeader, String forwardedForHeader) {
        if (peer == null || !isTrusted(peer)) {
            return peer;
        }
        if (realIpHeader != null && !realIpHeader.isBlank()) {
            return realIpHeader.trim();
        }
        if (forwardedForHeader != null && !forwardedForHeader.isBlank()) {
            // our proxy appends the address it saw last; earlier entries are client-supplied
            String[] hops = forwardedForHeader.split(",");
            return hops[hops.length - 1].trim();
        }
        return peer;
    }

    boolean isTrusted(String address) {
        for (Cidr cidr : trusted) {
            if (cidr.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private record Cidr(byte[] network, int prefix) {

        static Cidr parse(String cidr) {
            String[] parts = cidr.split("/");
            byte[] network = literal(parts[0]);
            if (network == null) {
                throw new IllegalStateException("TRUSTED_PROXIES entry is not an IP/CIDR: " + cidr);
            }
            int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : network.length * 8;
            return new Cidr(network, prefix);
        }

        boolean contains(String address) {
            byte[] candidate = literal(address);
            if (candidate == null || candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = 0xFF << (8 - rest) & 0xFF;
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }

        /** Parses an IP literal without DNS; returns null for anything that is not a literal. */
        private static byte[] literal(String address) {
            String value = address.trim();
            if (value.startsWith("[") && value.endsWith("]")) {
                value = value.substring(1, value.length() - 1);
            }
            // strict literal shapes only, so getByName never falls back to a DNS lookup
            boolean ipv4 = value.matches("\\d{1,3}(\\.\\d{1,3}){3}");
            boolean ipv6 = value.contains(":") && value.matches("[0-9a-fA-F:.]+");
            if (!ipv4 && !ipv6) {
                return null;
            }
            try {
                return InetAddress.getByName(value).getAddress();
            } catch (UnknownHostException e) {
                return null;
            }
        }
    }
}
