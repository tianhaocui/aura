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
                        new RouteContribution("GET", "/orders/list", protectedHandler("orders.read", "ok")))
                .authenticate(ctx -> "user-1")
                .authorize((user, permission) -> user.equals("user-1") && permission.equals("orders.read"));

        TestClient.Response response = TestClient.of(app).get("/orders/list").execute();
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("ok");
    }

    @Test
    void deniedPermissionAbortsWithForbidden() {
        AuraApp app = TestApplications.app(
                        "permission-denied",
                        new RouteContribution("GET", "/orders/list",
                                protectedHandler("orders.read", "must-not-run")))
                .authenticate(ctx -> "user-1")
                .authorize((user, permission) -> false);

        TestClient.Response response = TestClient.of(app).get("/orders/list").execute();
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body()).contains("Forbidden");
    }

    private static BaseHandler protectedHandler(String permission, String response) {
        return ctx -> {
            String userId = ctx.app().authFunction().apply(ctx);
            if (userId == null || userId.isBlank()) {
                ctx.status(401).json(java.util.Map.of("error", "Unauthorized"));
                ctx.abort();
                return;
            }
            ctx.set("_userId", userId);
            if (ctx.app().permissionChecker() == null
                    || !ctx.app().permissionChecker().test(userId, permission)) {
                ctx.status(403).json(java.util.Map.of("error", "Forbidden", "permission", permission));
                ctx.abort();
                return;
            }
            ctx.text(response);
        };
    }
}
