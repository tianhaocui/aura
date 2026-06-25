package io.aura;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PropsResolveTest {

    @Test
    void propsPrefixResolvesFromSystemProperty() {
        Aura app = Aura.create();
        app.set("db.url", "file-value");
        app.set("db.user", "admin");

        System.setProperty("db.url", "sysprop-value");
        try {
            var result = app.props("db.");
            assertEquals("sysprop-value", result.get("db.url"));
            assertEquals("admin", result.get("db.user"));
        } finally {
            System.clearProperty("db.url");
        }
    }

    @Test
    void propsPrefixReturnsOnlyMatchingKeys() {
        Aura app = Aura.create();
        app.set("db.url", "jdbc:h2:mem");
        app.set("server.port", "8080");

        var result = app.props("db.");
        assertTrue(result.containsKey("db.url"));
        assertFalse(result.containsKey("server.port"));
    }
}
