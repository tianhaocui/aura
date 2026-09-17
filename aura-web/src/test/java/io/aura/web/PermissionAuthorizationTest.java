package io.aura.web;

import io.aura.AuraApp;
import io.aura.contract.RouteContribution;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionAuthorizationTest {
    @Test
    void routeAuthorizationUsesSharedFunctions() {
        AuraApp app = TestApplications.app(
                        "permission-allowed",
                        new RouteContribution("GET", "/orders/list",
                                (BaseHandler) ctx -> ctx.text("ok")).permission("orders.read"))
                .authenticate(ctx -> "user-1")
                .authorize((user, permission) -> user.equals("user-1") && permission.equals("orders.read"));

        TestClient.Response response = TestClient.of(app).get("/orders/list").execute();
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("ok");
        @SuppressWarnings("unchecked")
        java.util.List<io.aura.contract.ApiRoute> routes =
                (java.util.List<io.aura.contract.ApiRoute>) TestClient.of(app).schema().get("routes");
        assertThat(routes.get(0).authenticated()).isTrue();
        assertThat(routes.get(0).permission()).isEqualTo("orders.read");
        assertThat(TestClient.of(app).openApi().toString())
                .contains("x-aura-authenticated", "x-aura-permission", "orders.read");
    }

    @Test
    void deniedPermissionAbortsWithForbidden() {
        AuraApp app = TestApplications.app(
                        "permission-denied",
                        new RouteContribution("GET", "/orders/list",
                                (BaseHandler) ctx -> ctx.text("must-not-run"))
                                .permission("orders.read"))
                .authenticate(ctx -> "user-1")
                .authorize((user, permission) -> false);

        TestClient.Response response = TestClient.of(app).get("/orders/list").execute();
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body()).contains("AURA_PERMISSION_DENIED");
    }

    @Test
    void authenticatedRouteRejectsMissingIdentity() {
        AuraApp app = TestApplications.app("auth-required",
                new RouteContribution("GET", "/me",
                        (BaseHandler) ctx -> ctx.text(ctx.userId())).authenticated());

        TestClient.Response response = TestClient.of(app).get("/me").execute();

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body()).contains("AURA_AUTHENTICATION_REQUIRED");
    }
}
