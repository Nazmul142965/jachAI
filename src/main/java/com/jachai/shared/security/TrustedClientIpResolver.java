package com.jachai.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties.ForwardHeadersStrategy;
import org.springframework.stereotype.Component;

/** Resolves a rate-limit identity without treating client-supplied forwarding data as authority. */
@Component
public class TrustedClientIpResolver {
    private static final int MAX_HEADER_LENGTH = 2048;
    private static final int MAX_HOPS = 32;
    private final List<Network> trusted;

    public TrustedClientIpResolver(@Value("${app.security.trusted-proxy-cidrs:}") String cidrs,
                                   ServerProperties serverProperties) {
        if (serverProperties.getForwardHeadersStrategy() != ForwardHeadersStrategy.NONE) {
            throw new IllegalStateException("server.forward-headers-strategy must be none so the raw socket peer remains available");
        }
        trusted = parseNetworks(cidrs);
    }

    public String resolve(HttpServletRequest request) {
        String raw = request.getRemoteAddr();
        byte[] peer = literal(raw);
        if (peer == null) return raw == null || raw.isBlank() ? "unknown" : raw;
        if (!isTrusted(peer)) return normalized(peer);

        Enumeration<String> headers = request.getHeaders("X-Forwarded-For");
        if (headers == null || !headers.hasMoreElements()) return normalized(peer);
        String chain = headers.nextElement();
        if (headers.hasMoreElements() || chain == null || chain.length() > MAX_HEADER_LENGTH) {
            return normalized(peer);
        }
        String[] hops = chain.split(",", -1);
        if (hops.length == 0 || hops.length > MAX_HOPS) return normalized(peer);
        byte[][] addresses = new byte[hops.length][];
        for (int index = 0; index < hops.length; index++) {
            addresses[index] = literal(hops[index].trim());
            if (addresses[index] == null) return normalized(peer);
        }
        for (int index = addresses.length - 1; index >= 0; index--) {
            if (!isTrusted(addresses[index])) return normalized(addresses[index]);
        }
        // Every reported hop is a configured proxy. This relies on the edge proxy replacing,
        // never preserving, client-supplied X-Forwarded-For (documented deployment invariant).
        return normalized(addresses[0]);
    }

    private boolean isTrusted(byte[] address) {
        return trusted.stream().anyMatch(network -> network.contains(address));
    }

    private static List<Network> parseNetworks(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<Network> networks = new ArrayList<>();
        for (String entry : value.split(",", -1)) {
            String[] parts = entry.trim().split("/", -1);
            if (parts.length > 2 || parts[0].isBlank()) throw invalidProxy(entry);
            byte[] address = literal(parts[0]);
            if (address == null) throw invalidProxy(entry);
            int prefix = address.length * 8;
            if (parts.length == 2) {
                try {
                    if (!parts[1].matches("[0-9]{1,3}")) throw invalidProxy(entry);
                    prefix = Integer.parseInt(parts[1]);
                } catch (NumberFormatException exception) {
                    throw invalidProxy(entry);
                }
                if (prefix < 0 || prefix > address.length * 8) throw invalidProxy(entry);
            }
            networks.add(new Network(address, prefix));
        }
        return List.copyOf(networks);
    }

    private static IllegalArgumentException invalidProxy(String entry) {
        return new IllegalArgumentException("Invalid trusted proxy IP/CIDR: " + entry);
    }

    private static byte[] literal(String value) {
        if (value == null || value.isBlank()) return null;
        String address = value;
        if (address.startsWith("[") && address.endsWith("]")) {
            address = address.substring(1, address.length() - 1);
        }
        if (address.indexOf(':') >= 0) return ipv6(address);
        return ipv4(address);
    }

    private static byte[] ipv4(String address) {
        String[] octets = address.split("\\.", -1);
        if (octets.length != 4) return null;
        byte[] bytes = new byte[4];
        for (int index = 0; index < 4; index++) {
            String octet = octets[index];
            if (octet.isEmpty() || octet.length() > 3 || !octet.matches("[0-9]+")
                    || (octet.length() > 1 && octet.charAt(0) == '0')) return null;
            int number = Integer.parseInt(octet);
            if (number > 255) return null;
            bytes[index] = (byte) number;
        }
        return bytes;
    }

    private static byte[] ipv6(String address) {
        if (!address.matches("[0-9a-fA-F:]+") || address.indexOf(':') < 0) return null;
        int compressed = address.indexOf("::");
        if (compressed != address.lastIndexOf("::")) return null;
        String[] left = (compressed < 0 ? address : address.substring(0, compressed)).split(":", -1);
        String[] right = compressed < 0 ? new String[0] : address.substring(compressed + 2).split(":", -1);
        if (compressed >= 0 && left.length == 1 && left[0].isEmpty()) left = new String[0];
        if (compressed >= 0 && right.length == 1 && right[0].isEmpty()) right = new String[0];
        int count = left.length + right.length;
        if (count > 8 || (compressed < 0 && count != 8) || (compressed >= 0 && count >= 8)) return null;
        byte[] bytes = new byte[16];
        if (!putHextets(bytes, left, 0) || !putHextets(bytes, right, 8 - right.length)) return null;
        return bytes;
    }

    private static boolean putHextets(byte[] target, String[] hextets, int offset) {
        for (String hextet : hextets) {
            if (hextet.isEmpty() || hextet.length() > 4) return false;
            int number = Integer.parseInt(hextet, 16);
            target[offset * 2] = (byte) (number >>> 8);
            target[offset * 2 + 1] = (byte) number;
            offset++;
        }
        return true;
    }

    private static String normalized(byte[] address) {
        if (address.length == 4) {
            return (address[0] & 255) + "." + (address[1] & 255) + "."
                    + (address[2] & 255) + "." + (address[3] & 255);
        }
        // Fixed hexadecimal form avoids DNS and makes equivalent IPv6 spellings one rate-limit key.
        StringBuilder result = new StringBuilder(39);
        for (int index = 0; index < 16; index += 2) {
            if (index > 0) result.append(':');
            result.append(Integer.toHexString(((address[index] & 255) << 8) | (address[index + 1] & 255)));
        }
        return result.toString();
    }

    private record Network(byte[] address, int prefix) {
        boolean contains(byte[] candidate) {
            if (candidate.length != address.length) return false;
            int whole = prefix / 8;
            int bits = prefix % 8;
            if (!Arrays.equals(Arrays.copyOf(candidate, whole), Arrays.copyOf(address, whole))) return false;
            return bits == 0 || ((candidate[whole] ^ address[whole]) & (255 << (8 - bits))) == 0;
        }
    }
}
