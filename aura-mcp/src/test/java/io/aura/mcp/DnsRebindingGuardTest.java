package io.aura.mcp;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression guard for the DNS-rebinding TOCTOU: the address answer that passes validation must
 * also be the answer that is live when the socket connects. The JDK exposes no supported
 * per-client socket pinning (SocketResolver never shipped), so the defence is a fail-closed
 * re-validation immediately before each request. Uses a scripted DNS seam so the attacker
 * scenario (first answer public, later answer loopback) is deterministic and offline.
 */
class DnsRebindingGuardTest {

    /** Feeds canned DNS answers in order; any unexpected extra lookup fails as UnknownHost. */
    private static final class ScriptedDns implements McpBridge.DnsResolver {
        private final Deque<List<InetAddress>> answers = new ArrayDeque<>();
        int calls;

        ScriptedDns answer(List<InetAddress> addresses) {
            answers.add(addresses);
            return this;
        }

        @Override
        public List<InetAddress> resolve(String host) throws java.net.UnknownHostException {
            calls++;
            List<InetAddress> next = answers.poll();
            if (next == null) {
                throw new java.net.UnknownHostException("script exhausted for " + host);
            }
            return next;
        }
    }

    private static McpBridge bridge(String baseUrl, ScriptedDns dns, boolean allowPrivate) {
        return new McpBridge(baseUrl, new PrintStream(new ByteArrayOutputStream()), null, allowPrivate, dns);
    }

    @Test
    void rejectsMixedPublicAndPrivateAnswerSet() throws Exception {
        // Attacker's first resolve: [8.8.8.8, 127.0.0.1]. Fail closed on the whole set.
        ScriptedDns dns = new ScriptedDns()
                .answer(List.of(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")));

        assertThatThrownBy(() -> bridge("https://api.example.com", dns, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private or local");
        assertThat(dns.calls).isEqualTo(1);
    }

    @Test
    void rebindingToLoopbackAfterConstructionIsCaughtAtConnectTime() throws Exception {
        // The classic TOCTOU attack: validation passes with a public answer, then DNS is
        // rebound to 127.0.0.1 before the bridge ever opens a socket.
        ScriptedDns dns = new ScriptedDns()
                .answer(List.of(InetAddress.getByName("8.8.8.8")))
                .answer(List.of(InetAddress.getByName("127.0.0.1")));

        McpBridge bridge = bridge("https://api.example.com", dns, false);
        assertThat(dns.calls).as("validated once at construction").isEqualTo(1);

        // The request path re-validates right before connecting: the rebinding answer must
        // abort the request instead of dialling 127.0.0.1.
        assertThatThrownBy(() -> bridge.requirePublicAtConnect("api.example.com"))
                .isInstanceOf(SecurityException.class);
        assertThat(dns.calls).isEqualTo(2);
    }

    @Test
    void legitimatePublicRotationStillConnects() throws Exception {
        ScriptedDns dns = new ScriptedDns()
                .answer(List.of(InetAddress.getByName("8.8.8.8")))
                .answer(List.of(InetAddress.getByName("1.1.1.1")));

        McpBridge bridge = bridge("https://api.example.com", dns, false);
        assertThatCode(() -> bridge.requirePublicAtConnect("api.example.com"))
                .doesNotThrowAnyException();
        assertThat(dns.calls).isEqualTo(2);
    }

    @Test
    void literalPrivateHostIsRejectedWithoutRetry() throws Exception {
        ScriptedDns dns = new ScriptedDns()
                .answer(List.of(InetAddress.getByName("169.254.169.254")));

        assertThatThrownBy(() -> bridge("http://metadata.example", dns, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private or local");
    }

    @Test
    void previouslyMissedCgnatRangeIsRejected() throws Exception {
        ScriptedDns dns = new ScriptedDns()
                .answer(List.of(InetAddress.getByName("100.64.1.1")));

        assertThatThrownBy(() -> bridge("http://cgnat.example", dns, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private or local");
    }

    @Test
    void explicitPrivateOptInSkipsDnsEnforcement() throws Exception {
        ScriptedDns dns = new ScriptedDns();
        McpBridge bridge = bridge("http://localhost:8080", dns, true);

        assertThat(dns.calls).isZero();
        assertThat(bridge.requirePublicAtConnect("localhost")).isEmpty();
        assertThat(dns.calls).as("no DNS consulted when private targets are allowed").isZero();
    }

    @Test
    void unresolvableHostFailsFast() {
        ScriptedDns dns = new ScriptedDns(); // no answers -> UnknownHostException
        assertThatThrownBy(() -> bridge("https://api.example.com", dns, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be resolved");
    }

    @Test
    void validPublicUrlIsAccepted() throws Exception {
        ScriptedDns dns = new ScriptedDns()
                .answer(List.of(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("1.1.1.1")));
        assertThatCode(() -> bridge("https://api.example.com", dns, false)).doesNotThrowAnyException();
    }
}
