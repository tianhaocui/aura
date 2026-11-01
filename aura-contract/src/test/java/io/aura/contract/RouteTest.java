package io.aura.contract;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteTest {

    @Test
    void minimalFactoryUppercasesMethodAndTrimsPath() {
        Route r = Route.of("get", "  /user/{id} ", new Object());
        assertThat(r.method()).isEqualTo("GET");
        assertThat(r.path()).isEqualTo("/user/{id}");
        assertThat(r.params()).isEmpty();
        assertThat(r.authenticated()).isFalse();
    }

    @Test
    void nullHandlerFailsFast() {
        assertThatThrownBy(() -> new Route("GET", "/x", null,
                List.of(), null, Map.of(), null, false, null, false))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void pathMustStartWithSlash() {
        assertThatThrownBy(() -> Route.of("GET", "user", new Object()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start with");
    }

    @Test
    void permissionImpliesAuthenticated() {
        Route r = Route.of("GET", "/x", new Object()).requireAuth("admin.write");
        assertThat(r.authenticated()).isTrue();
        assertThat(r.permission()).isEqualTo("admin.write");
    }

    @Test
    void blankPermissionFailsInsteadOfSilentlyDroppingProtection() {
        assertThatThrownBy(() -> new Route("GET", "/x", new Object(), List.of(), null, Map.of(),
                null, true, "  ", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permission must not be blank");
    }

    @Test
    void requireAuthDoesNotImplyStreaming() {
        Route r = Route.of("GET", "/x", new Object()).requireAuth();
        assertThat(r.authenticated()).isTrue();
        assertThat(r.streaming()).isFalse();
    }

    @Test
    void fluentSettersCarryEveryDeclaredFact() {
        Route r = Route.of("POST", "/items", new Object())
                .describedAs("create item")
                .withReturnType("Item")
                .withStreaming(true)
                .requireAuth();
        assertThat(r.description()).isEqualTo("create item");
        assertThat(r.returnType()).isEqualTo("Item");
        assertThat(r.streaming()).isTrue();
        assertThat(r.authenticated()).isTrue();
    }
}
