package io.aura.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class IpSafetyTest {

    @Test
    void rejectsIpv4Loopback() throws Exception {
        assertThat(IpSafety.isPublic(InetAddress.getByName("127.0.0.1"))).isFalse();
        assertThat(IpSafety.isPublic(InetAddress.getByName("127.1.2.3"))).isFalse();
    }

    @Test
    void rejectsCgnatRange() throws Exception {
        // 100.64.0.0/10 — previously missed by both duplicated blacklists (AWS IMDSv2 path).
        assertThat(IpSafety.isPublic(InetAddress.getByName("100.64.1.1"))).isFalse();
        assertThat(IpSafety.isPublic(InetAddress.getByName("100.64.0.0"))).isFalse();
        assertThat(IpSafety.isPublic(InetAddress.getByName("100.127.255.255"))).isFalse();
        // Boundaries just outside the range stay public.
        assertThat(IpSafety.isPublic(InetAddress.getByName("100.63.255.255"))).isTrue();
        assertThat(IpSafety.isPublic(InetAddress.getByName("100.128.0.1"))).isTrue();
    }

    @Test
    void rejectsLinkLocalImdsAddress() throws Exception {
        assertThat(IpSafety.isPublic(InetAddress.getByName("169.254.169.254"))).isFalse();
        assertThat(IpSafety.isPublic(InetAddress.getByName("169.254.1.1"))).isFalse();
    }

    @Test
    void rejectsOtherInternalIpv4Ranges() throws Exception {
        assertThat(IpSafety.isPublic(InetAddress.getByName("0.0.0.0"))).isFalse();
        assertThat(IpSafety.isPublic(InetAddress.getByName("0.1.2.3"))).isFalse();      // 0.0.0.0/8
        assertThat(IpSafety.isPublic(InetAddress.getByName("10.0.0.1"))).isFalse();
        assertThat(IpSafety.isPublic(InetAddress.getByName("172.16.5.5"))).isFalse();
        assertThat(IpSafety.isPublic(InetAddress.getByName("172.31.255.255"))).isFalse();
        assertThat(IpSafety.isPublic(InetAddress.getByName("192.168.1.1"))).isFalse();
        assertThat(IpSafety.isPublic(InetAddress.getByName("224.0.0.1"))).isFalse();    // multicast
        assertThat(IpSafety.isPublic(InetAddress.getByName("240.0.0.1"))).isFalse();    // reserved
        assertThat(IpSafety.isPublic(InetAddress.getByName("255.255.255.255"))).isFalse();
        assertThat(IpSafety.isPublic(InetAddress.getByName("172.32.0.1"))).isTrue();
    }

    @Test
    void acceptsPublicIpv4() throws Exception {
        assertThat(IpSafety.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(IpSafety.isPublic(InetAddress.getByName("93.184.216.34"))).isTrue();
    }

    @Test
    void rejectsInternalIpv6Ranges() throws Exception {
        assertThat(IpSafety.isPublic(InetAddress.getByName("::1"))).isFalse();          // loopback
        assertThat(IpSafety.isPublic(InetAddress.getByName("::"))).isFalse();           // unspecified
        assertThat(IpSafety.isPublic(InetAddress.getByName("fd00::1"))).isFalse();      // ULA (missed before)
        assertThat(IpSafety.isPublic(InetAddress.getByName("fc00::1"))).isFalse();      // fc00::/7
        assertThat(IpSafety.isPublic(InetAddress.getByName("fe80::1"))).isFalse();      // link-local
        assertThat(IpSafety.isPublic(InetAddress.getByName("fec0::1"))).isFalse();      // legacy site-local
        assertThat(IpSafety.isPublic(InetAddress.getByName("ff02::1"))).isFalse();      // multicast
        assertThat(IpSafety.isPublic(InetAddress.getByName("2002::1"))).isFalse();      // 6to4
        assertThat(IpSafety.isPublic(InetAddress.getByName("2001:db8::1"))).isFalse();  // documentation
    }

    @Test
    void acceptsPublicIpv6() throws Exception {
        assertThat(IpSafety.isPublic(InetAddress.getByName("2606:4700:4700::1111"))).isTrue();
    }

    @Test
    void unwrapsIpv4MappedIpv6() throws Exception {
        assertThat(IpSafety.isPublic(InetAddress.getByName("::ffff:127.0.0.1"))).isFalse();
        assertThat(IpSafety.isPublic(InetAddress.getByName("::ffff:100.64.1.1"))).isFalse();
    }

    @Test
    void nullAddressIsNotPublic() {
        assertThat(IpSafety.isPublic(null)).isFalse();
    }

    @Test
    void requirePublic_acceptsAndRejects() throws Exception {
        assertThatCode(() -> IpSafety.requirePublic(InetAddress.getByName("8.8.8.8"), "dns.example"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> IpSafety.requirePublic(InetAddress.getByName("10.0.0.1"), "internal.example"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("internal.example");
    }

    @Test
    void resolvePublic_rejectsLocalhost() {
        assertThatThrownBy(() -> IpSafety.resolvePublic("localhost"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void resolvePublic_rejectsLiteralPrivateHost() {
        // Literal addresses go through the same path — no DNS needed, safe offline.
        assertThatThrownBy(() -> IpSafety.resolvePublic("169.254.169.254"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> IpSafety.resolvePublic("127.0.0.1"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void resolvePublic_acceptsLiteralPublicHost() throws Exception {
        List<InetAddress> resolved = IpSafety.resolvePublic("8.8.8.8");
        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0)).isEqualTo(InetAddress.getByName("8.8.8.8"));
    }

    @Test
    void pinToPublic_failsClosedOnMixedSet() throws Exception {
        // A mixed [public, private] answer is the rebinding signature: reject the whole set.
        InetAddress[] mixed = {
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("127.0.0.1"),
        };
        assertThatThrownBy(() -> IpSafety.pinToPublic(mixed))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void pinToPublic_returnsFirstValidatedCandidate() throws Exception {
        InetAddress[] allPublic = {
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("1.1.1.1"),
        };
        assertThat(IpSafety.pinToPublic(allPublic)).isEqualTo(allPublic[0]);
    }

    @Test
    void pinToPublic_rejectsEmptySet() {
        assertThatThrownBy(() -> IpSafety.pinToPublic(new InetAddress[0]))
                .isInstanceOf(SecurityException.class);
    }
}
