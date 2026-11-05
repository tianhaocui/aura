package io.aura.security;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared SSRF / DNS-rebinding guard for every Aura module that opens outbound HTTP connections.
 *
 * <p>Centralizes the IP-range blacklist that was previously copy-pasted into individual HTTP
 * callers (which is how new clients kept re-introducing the same gap) and exposes the
 * resolve-then-pin primitives used to close the time-of-check/time-of-use window between a
 * DNS validation and the actual socket connection.</p>
 *
 * <p>Rejected (non-public) ranges:</p>
 * <ul>
 *   <li>IPv4: {@code 0.0.0.0/8}, {@code 10.0.0.0/8}, {@code 100.64.0.0/10} (CGNAT, commonly
 *       used by cloud metadata endpoints), {@code 127.0.0.0/8}, {@code 169.254.0.0/16}
 *       (link-local, includes {@code 169.254.169.254}), {@code 172.16.0.0/12},
 *       {@code 192.168.0.0/16}, {@code 224.0.0.0/4} (multicast), {@code 240.0.0.0/4} (reserved)</li>
 *   <li>IPv6: {@code ::} (unspecified), {@code ::1} (loopback), {@code fc00::/7} (unique local,
 *       includes {@code fd00::/8}), {@code fe80::/10} (link-local), {@code fec0::/10} (legacy
 *       site-local), {@code ff00::/8} (multicast), {@code 2002::/16} (6to4, often tunnels back
 *       to private IPv4), {@code 2001::/32} (Teredo, tunnels IPv4), {@code 2001:db8::/32}
 *       (documentation), and {@code 64:ff9b::/96} (NAT64, evaluated through its embedded IPv4)</li>
 * </ul>
 *
 * <p>All checks are pure byte-range comparisons and never consult local network interfaces,
 * so results are deterministic across environments. IPv4-embedded IPv6 addresses
 * ({@code ::ffff:a.b.c.d}, deprecated compatible forms, and the NAT64 well-known prefix) are
 * unwrapped and evaluated against the IPv4 blacklist.</p>
 *
 * <p>No third-party dependencies; uses only {@link InetAddress}, {@link Inet4Address} and
 * {@link Inet6Address}.</p>
 */
public final class IpSafety {

    private IpSafety() {
    }

    /**
     * Returns {@code true} only when the address cannot reach internal services, metadata
     * endpoints, or multicast/reserved ranges. A {@code null} address is treated as unsafe.
     */
    public static boolean isPublic(InetAddress address) {
        if (address == null) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes == null) {
            return false;
        }
        if (bytes.length == 4) {
            return isPublicIpv4(bytes);
        }
        if (bytes.length == 16) {
            if (isIpv4Embedded(bytes) || isNat64(bytes)) {
                return isPublicIpv4(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
            }
            return isPublicIpv6(bytes);
        }
        return false;
    }

    /**
     * Throws {@link SecurityException} unless {@code address} is public.
     *
     * @param context human-readable description (e.g. the host being validated) used in the message
     */
    public static void requirePublic(InetAddress address, String context) {
        if (!isPublic(address)) {
            throw new SecurityException("Address for '" + context + "' is not public: "
                    + (address == null ? "null" : address.getHostAddress())
                    + " (private, loopback, link-local, CGNAT, multicast, reserved, or documentation range)");
        }
    }

    /**
     * Resolves {@code host} and validates every returned address. This is the check-time half
     * of the DNS-rebinding defence; callers MUST pin the connection to one of the returned
     * addresses (see {@link #pinToPublic(InetAddress[])}) so that no second DNS lookup can
     * silently swap in a private address between validation and connect.
     *
     * @return the full resolved list, unmodifiable; never empty
     * @throws IOException          if the host cannot be resolved
     * @throws SecurityException    if ANY resolved result is non-public
     */
    public static List<InetAddress> resolvePublic(String host) throws IOException {
        InetAddress[] resolved = InetAddress.getAllByName(host);
        List<InetAddress> validated = new ArrayList<>(resolved.length);
        for (InetAddress address : resolved) {
            requirePublic(address, host);
            validated.add(address);
        }
        return Collections.unmodifiableList(validated);
    }

    /**
     * Validates a previously resolved address set and returns the address a connection MUST be
     * pinned to. Fails closed: if any candidate is non-public the whole set is rejected rather
     * than silently falling back to another answer, because a mixed set is the signature of an
     * in-flight rebinding attempt.
     *
     * @throws SecurityException if the set is empty or contains any non-public address
     */
    public static InetAddress pinToPublic(InetAddress[] resolved) {
        if (resolved == null || resolved.length == 0) {
            throw new SecurityException("No resolved address available to pin the connection to");
        }
        for (InetAddress address : resolved) {
            requirePublic(address, "pinned candidate");
        }
        return resolved[0];
    }

    /** List-shaped convenience for {@link #pinToPublic(InetAddress[])}. */
    public static InetAddress pinToPublic(List<? extends InetAddress> resolved) {
        if (resolved == null || resolved.isEmpty()) {
            throw new SecurityException("No resolved address available to pin the connection to");
        }
        for (InetAddress address : resolved) {
            requirePublic(address, "pinned candidate");
        }
        return resolved.get(0);
    }

    private static boolean isPublicIpv4(byte[] b) {
        int o0 = b[0] & 0xff;
        int o1 = b[1] & 0xff;
        if (o0 == 0) return false;                                   // 0.0.0.0/8
        if (o0 == 10) return false;                                  // 10.0.0.0/8
        if (o0 == 100 && o1 >= 64 && o1 <= 127) return false;        // 100.64.0.0/10 CGNAT
        if (o0 == 127) return false;                                 // 127.0.0.0/8 loopback
        if (o0 == 169 && o1 == 254) return false;                    // 169.254.0.0/16 link-local (IMDS)
        if (o0 == 172 && o1 >= 16 && o1 <= 31) return false;         // 172.16.0.0/12
        if (o0 == 192 && o1 == 168) return false;                    // 192.168.0.0/16
        if (o0 >= 224 && o0 <= 239) return false;                    // 224.0.0.0/4 multicast
        if (o0 >= 240) return false;                                 // 240.0.0.0/4 reserved (incl. 255.255.255.255)
        return true;
    }

    private static boolean isPublicIpv6(byte[] b) {
        int b0 = b[0] & 0xff;
        int b1 = b[1] & 0xff;
        if (isAllZero(b)) return false;                              // :: unspecified
        if (isLoopbackV6(b)) return false;                           // ::1
        if ((b0 & 0xfe) == 0xfc) return false;                       // fc00::/7 unique local (includes fd00::/8)
        if (b0 == 0xfe && (b1 & 0xc0) == 0x80) return false;         // fe80::/10 link-local
        if (b0 == 0xfe && (b1 & 0xc0) == 0xc0) return false;         // fec0::/10 legacy site-local
        if (b0 == 0xff) return false;                                // ff00::/8 multicast
        if (b0 == 0x20 && b1 == 0x02) return false;                  // 2002::/16 6to4
        if (b0 == 0x20 && b1 == 0x01 && b[2] == 0 && b[3] == 0) return false; // 2001::/32 Teredo
        if (b0 == 0x20 && b1 == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) return false; // 2001:db8::/32
        return true;
    }

    /** True for {@code ::ffff:a.b.c.d} (mapped) and the deprecated {@code ::a.b.c.d} (compatible) forms. */
    private static boolean isIpv4Embedded(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) return false;
        }
        boolean mapped = (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
        boolean compatible = b[10] == 0 && b[11] == 0;
        return mapped || compatible;
    }

    /** True for the NAT64 well-known prefix {@code 64:ff9b::/96} with an IPv4 suffix. */
    private static boolean isNat64(byte[] b) {
        if ((b[0] & 0xff) != 0x00 || (b[1] & 0xff) != 0x64
                || (b[2] & 0xff) != 0xff || (b[3] & 0xff) != 0x9b) return false;
        for (int i = 4; i < 12; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }

    private static boolean isAllZero(byte[] b) {
        for (byte value : b) {
            if (value != 0) return false;
        }
        return true;
    }

    private static boolean isLoopbackV6(byte[] b) {
        for (int i = 0; i < 15; i++) {
            if (b[i] != 0) return false;
        }
        return b[15] == 1;
    }
}
