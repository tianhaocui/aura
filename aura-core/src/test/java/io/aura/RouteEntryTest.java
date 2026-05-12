package io.aura;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class RouteEntryTest {

    @Test
    void constructor_setsAllFields() {
        Object handler = new Object();
        RouteEntry entry = new RouteEntry("GET", "/api/users", handler, "list users");

        assertThat(entry.method()).isEqualTo("GET");
        assertThat(entry.path()).isEqualTo("/api/users");
        assertThat(entry.handler()).isSameAs(handler);
        assertThat(entry.description()).isEqualTo("list users");
    }

    @Test
    void constructor_allowsNullHandlerAndDescription() {
        RouteEntry entry = new RouteEntry("POST", "/submit", null, null);

        assertThat(entry.method()).isEqualTo("POST");
        assertThat(entry.path()).isEqualTo("/submit");
        assertThat(entry.handler()).isNull();
        assertThat(entry.description()).isNull();
    }

    @Test
    void recordEquality_sameFieldsAreEqual() {
        Object handler = "handler";
        RouteEntry a = new RouteEntry("DELETE", "/item/1", handler, null);
        RouteEntry b = new RouteEntry("DELETE", "/item/1", handler, null);

        assertThat(a).isEqualTo(b);
    }
}
