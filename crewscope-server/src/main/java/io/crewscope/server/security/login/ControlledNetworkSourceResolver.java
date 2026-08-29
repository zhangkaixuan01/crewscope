package io.crewscope.server.security.login;

import io.crewscope.application.identity.ControlledNetworkResource;
import io.crewscope.application.identity.LoginDefenseUnavailableException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;

/** Resolves a bounded client network while trusting forwarded headers only from configured CIDRs. */
public final class ControlledNetworkSourceResolver {

    private static final int MAX_FORWARDED_BYTES = 1_024;
    private static final int MAX_FORWARDED_HOPS = 16;

    private final List<Cidr> trustedProxies;

    public ControlledNetworkSourceResolver(List<String> trustedProxies) {
        List<String> configured = trustedProxies == null ? List.of() : trustedProxies;
        if (configured.size() > 64) {
            throw new IllegalStateException("too many trusted proxy CIDRs");
        }
        this.trustedProxies = configured.stream().map(Cidr::parse).toList();
    }

    public ControlledNetworkResource resolve(ServerWebExchange exchange) {
        ServerWebExchange required = Objects.requireNonNull(exchange, "exchange");
        return resolve(required.getRequest().getRemoteAddress(), required.getRequest().getHeaders());
    }

    public ControlledNetworkResource resolve(InetSocketAddress remote, HttpHeaders headers) {
        InetSocketAddress requiredRemote = Objects.requireNonNull(remote, "remote");
        InetAddress direct = requiredRemote.getAddress();
        // ForwardedHeaderTransformer deliberately creates an unresolved socket address from the
        // already validated X-Forwarded-For literal. Parse only numeric literals here; never let an
        // authentication request trigger DNS resolution.
        byte[] selected = direct == null
                ? normalizeAddress(parseForwardedLiteral(requiredRemote.getHostString()))
                : normalizeAddress(direct.getAddress());
        if (isTrusted(selected)) {
            selected = forwardedClient(selected, Objects.requireNonNull(headers, "headers"));
        }
        return ControlledNetworkResource.ofCanonical(networkPrefix(selected));
    }

    private byte[] forwardedClient(byte[] direct, HttpHeaders headers) {
        List<String> values = headers.getOrEmpty("X-Forwarded-For");
        if (values.isEmpty()) {
            return direct;
        }
        String joined = String.join(",", values);
        if (joined.length() > MAX_FORWARDED_BYTES) {
            throw unavailable();
        }
        String[] hops = joined.split(",", -1);
        if (hops.length > MAX_FORWARDED_HOPS) {
            throw unavailable();
        }
        List<byte[]> parsed = new ArrayList<>(hops.length);
        for (String hop : hops) {
            parsed.add(normalizeAddress(parseForwardedLiteral(hop.strip())));
        }
        byte[] selected = direct;
        for (int index = parsed.size() - 1; index >= 0 && isTrusted(selected); index--) {
            selected = parsed.get(index);
        }
        return selected;
    }

    private boolean isTrusted(byte[] address) {
        return trustedProxies.stream().anyMatch(cidr -> cidr.matches(address));
    }

    private static String networkPrefix(byte[] address) {
        byte[] prefix = address.clone();
        int bits;
        if (prefix.length == 4) {
            prefix[3] = 0;
            bits = 24;
        } else if (prefix.length == 16) {
            Arrays.fill(prefix, 8, 16, (byte) 0);
            bits = 64;
        } else {
            throw unavailable();
        }
        return (prefix.length == 4 ? "ipv4:" : "ipv6:")
                + HexFormat.of().formatHex(prefix)
                + "/"
                + bits;
    }

    private static byte[] normalizeAddress(byte[] address) {
        if (address.length == 16) {
            boolean mapped = true;
            for (int index = 0; index < 10; index++) {
                mapped &= address[index] == 0;
            }
            mapped &= address[10] == (byte) 0xff && address[11] == (byte) 0xff;
            if (mapped) {
                return Arrays.copyOfRange(address, 12, 16);
            }
        }
        return address;
    }

    private static byte[] parseForwardedLiteral(String value) {
        if (value.isEmpty() || value.length() > 64 || value.indexOf('%') >= 0) {
            throw unavailable();
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.indexOf(':') >= 0 ? parseIpv6(value) : parseIpv4(value);
    }

    private static byte[] parseIpv4(String value) {
        String[] components = value.split("\\.", -1);
        if (components.length != 4) {
            throw unavailable();
        }
        byte[] result = new byte[4];
        for (int index = 0; index < components.length; index++) {
            String component = components[index];
            if (component.isEmpty()
                    || component.length() > 3
                    || component.chars().anyMatch(character -> !Character.isDigit(character))) {
                throw unavailable();
            }
            int number;
            try {
                number = Integer.parseInt(component);
            } catch (NumberFormatException invalid) {
                throw unavailable();
            }
            if (number > 255) {
                throw unavailable();
            }
            result[index] = (byte) number;
        }
        return result;
    }

    private static byte[] parseIpv6(String value) {
        int compression = value.indexOf("::");
        if (compression >= 0 && compression != value.lastIndexOf("::")) {
            throw unavailable();
        }
        List<Integer> left = parseIpv6Side(compression < 0 ? value : value.substring(0, compression));
        List<Integer> right = compression < 0
                ? List.of()
                : parseIpv6Side(value.substring(compression + 2));
        if ((compression < 0 && left.size() != 8)
                || (compression >= 0 && left.size() + right.size() >= 8)) {
            throw unavailable();
        }
        List<Integer> groups = new ArrayList<>(8);
        groups.addAll(left);
        while (groups.size() + right.size() < 8) {
            groups.add(0);
        }
        groups.addAll(right);
        byte[] result = new byte[16];
        for (int index = 0; index < groups.size(); index++) {
            int group = groups.get(index);
            result[index * 2] = (byte) (group >>> 8);
            result[index * 2 + 1] = (byte) group;
        }
        return result;
    }

    private static List<Integer> parseIpv6Side(String side) {
        if (side.isEmpty()) {
            return List.of();
        }
        String[] components = side.split(":", -1);
        List<Integer> groups = new ArrayList<>(components.length);
        for (int index = 0; index < components.length; index++) {
            String component = components[index];
            if (component.indexOf('.') >= 0) {
                if (index != components.length - 1) {
                    throw unavailable();
                }
                byte[] ipv4 = parseIpv4(component);
                groups.add(((ipv4[0] & 255) << 8) | (ipv4[1] & 255));
                groups.add(((ipv4[2] & 255) << 8) | (ipv4[3] & 255));
            } else {
                if (component.isEmpty()
                        || component.length() > 4
                        || component.chars().anyMatch(character -> Character.digit(character, 16) < 0)) {
                    throw unavailable();
                }
                groups.add(Integer.parseInt(component, 16));
            }
        }
        return groups;
    }

    private static LoginDefenseUnavailableException unavailable() {
        return new LoginDefenseUnavailableException();
    }

    private record Cidr(byte[] network, int prefixBits) {

        static Cidr parse(String value) {
            String required = Objects.requireNonNull(value, "trusted proxy CIDR").strip();
            int separator = required.lastIndexOf('/');
            if (separator < 1) {
                throw new IllegalStateException("trusted proxy CIDR is invalid");
            }
            byte[] address;
            int bits;
            try {
                address = normalizeAddress(parseForwardedLiteral(required.substring(0, separator)));
                bits = Integer.parseInt(required.substring(separator + 1));
            } catch (RuntimeException invalid) {
                throw new IllegalStateException("trusted proxy CIDR is invalid");
            }
            if (bits < 0 || bits > address.length * 8) {
                throw new IllegalStateException("trusted proxy CIDR is invalid");
            }
            byte[] network = address.clone();
            clearHostBits(network, bits);
            return new Cidr(network, bits);
        }

        boolean matches(byte[] candidate) {
            if (candidate.length != network.length) {
                return false;
            }
            int completeBytes = prefixBits / 8;
            int remainingBits = prefixBits % 8;
            for (int index = 0; index < completeBytes; index++) {
                if (candidate[index] != network[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xff << (8 - remainingBits);
            return (candidate[completeBytes] & mask) == (network[completeBytes] & mask);
        }

        private static void clearHostBits(byte[] value, int prefixBits) {
            int completeBytes = prefixBits / 8;
            int remainingBits = prefixBits % 8;
            if (remainingBits > 0) {
                value[completeBytes] &= (byte) (0xff << (8 - remainingBits));
                completeBytes++;
            }
            Arrays.fill(value, completeBytes, value.length, (byte) 0);
        }
    }
}
