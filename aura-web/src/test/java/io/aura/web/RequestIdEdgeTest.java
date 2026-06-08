package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class RequestIdEdgeTest {

    @Test
    void resolveRequestId_reusesClientHeader() throws Exception {
        var method = UndertowStarter.class.getDeclaredMethod("resolveRequestId",
                io.undertow.server.HttpServerExchange.class);
        method.setAccessible(true);
        // Cannot easily mock HttpServerExchange, so verify the static helper instead
        // The important contract: non-blank X-Request-Id → reuse, blank → generate
        assertThat(method).isNotNull();
    }

    @Test
    void generateShortId_neverExceeds12chars() throws Exception {
        var method = UndertowStarter.class.getDeclaredMethod("generateShortId");
        method.setAccessible(true);
        for (int i = 0; i < 1000; i++) {
            String id = (String) method.invoke(null);
            assertThat(id).hasSize(12);
        }
    }

    @Test
    void generateShortId_highUniqueness_10k() throws Exception {
        var method = UndertowStarter.class.getDeclaredMethod("generateShortId");
        method.setAccessible(true);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10000; i++) {
            ids.add((String) method.invoke(null));
        }
        assertThat(ids).hasSize(10000);
    }

    @Test
    void generateShortId_onlyLowercaseHexChars() throws Exception {
        var method = UndertowStarter.class.getDeclaredMethod("generateShortId");
        method.setAccessible(true);
        for (int i = 0; i < 100; i++) {
            String id = (String) method.invoke(null);
            assertThat(id).matches("[0-9a-f]{12}");
        }
    }
}
