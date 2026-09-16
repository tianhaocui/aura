package io.aura.mcp;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class McpSessionManagerActiveTest {
    @Test
    void activeChecksTtlWithoutTouchingLastAccess() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        McpSessionManager manager = new McpSessionManager(2, Duration.ofSeconds(30), clock);
        McpSessionManager.Session session = manager.create("alice");
        assertThat(manager.active(session.id(), "alice")).isTrue();
        assertThat(manager.active(session.id(), "bob")).isFalse();
        assertThat(manager.active("missing", "alice")).isFalse();
    }
}
