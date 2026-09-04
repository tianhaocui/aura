package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionAuthorizationTest {
    @Test
    void permissionMiddlewareUsesSharedAuthorizationFunction() {
        Aura app = Aura.create()
                .auth(ctx -> "user-1")
                .authorize((user, permission) -> user.equals("user-1") && permission.equals("orders.read"))
                .routes(routes -> routes.group("/orders", group -> {
                    group.before(Aura.requireAuth());
                    group.before(Aura.requirePermission("orders.read"));
                    group.get("/list", ctx -> ctx.text("ok"));
                }));

        TestClient.Response response = TestClient.of(app).get("/orders/list").execute();
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("ok");
    }

    @Test
    void deniedPermissionAbortsWithForbidden() {
        Aura app = Aura.create()
                .auth(ctx -> "user-1")
                .authorize((user, permission) -> false)
                .routes(routes -> routes.group("/orders", group -> {
                    group.before(Aura.requireAuth());
                    group.before(Aura.requirePermission("orders.read"));
                    group.get("/list", ctx -> ctx.text("must-not-run"));
                }));

        TestClient.Response response = TestClient.of(app).get("/orders/list").execute();
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body()).contains("Forbidden");
    }
}
