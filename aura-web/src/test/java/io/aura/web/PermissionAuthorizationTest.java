package io.aura.web;

import io.aura.AuraApp;
import io.aura.contract.Route;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionAuthorizationTest {
    @Test
    void routeAuthorizationUsesSharedFunctions() {
        AuraApp app = TestApplications.app(
                        "permission-allowed",
                        Route.of("GET", "/orders/list",
                                (BaseHandler) ctx -> ctx.text("ok")).requireAuth("orders.read"))
                .authenticate(ctx -> "user-1")
                .authorize((user, permission) -> user.equals("user-1") && permission.equals("orders.read"));

        TestClient.Response response = TestClient.of(app).get("/orders/list").execute();
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("ok");

        TestClient client = TestClient.of(app);
        assertThat(SchemaWire.route(client, "/orders/list"))
                .containsEntry("authenticated", true)
                .containsEntry("permission", "orders.read");
        // The same two facts in the OpenAPI operation, which is where a tool that speaks
        // OpenAPI reads them -- not a substring search over the whole document.
        assertThat(SchemaWire.operation(client, "/orders/list", "GET"))
                .containsEntry("x-aura-authenticated", true)
                .containsEntry("x-aura-permission", "orders.read");
    }

    @Test
    void deniedPermissionAbortsWithForbidden() {
        AuraApp app = TestApplications.app(
                        "permission-denied",
                        Route.of("GET", "/orders/list",
                                (BaseHandler) ctx -> ctx.text("must-not-run"))
                                .requireAuth("orders.read"))
                .authenticate(ctx -> "user-1")
                .authorize((user, permission) -> false);

        TestClient.Response response = TestClient.of(app).get("/orders/list").execute();
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body()).contains("AURA_PERMISSION_DENIED");
    }

    @Test
    void authenticatedRouteRejectsMissingIdentity() {
        AuraApp app = TestApplications.app("auth-required",
                Route.of("GET", "/me",
                        (BaseHandler) ctx -> ctx.text(ctx.userId())).requireAuth());

        TestClient.Response response = TestClient.of(app).get("/me").execute();

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body()).contains("AURA_AUTHENTICATION_REQUIRED");
    }
}
