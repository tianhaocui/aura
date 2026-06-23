package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class AuthTest {

    @Test
    void jwt_requireAuth_blocksWithoutToken() {
        Aura app = Aura.create().jwt("secret");
        Router router = new Router();
        router.before(Aura.requireAuth());
        router.get("/protected", ctx -> ctx.json(Map.of("userId", ctx.userId())));
        TestClient client = new TestClient(app, router);

        var resp = client.get("/protected").execute();
        assertThat(resp.status()).isEqualTo(401);
        assertThat(resp.body()).contains("Unauthorized");
    }

    @Test
    void jwt_requireAuth_allowsWithValidToken() {
        Aura app = Aura.create().jwt("secret");
        Router router = new Router();
        router.before(Aura.requireAuth());
        router.get("/protected", ctx -> ctx.json(Map.of("userId", ctx.userId())));
        TestClient client = new TestClient(app, router);

        String token = app.signJwt(42L);
        var resp = client.get("/protected").header("Authorization", "Bearer " + token).execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).contains("42");
    }

    @Test
    void jwt_invalidToken_returns401() {
        Aura app = Aura.create().jwt("secret");
        Router router = new Router();
        router.before(Aura.requireAuth());
        router.get("/protected", ctx -> ctx.text("ok"));
        TestClient client = new TestClient(app, router);

        var resp = client.get("/protected").header("Authorization", "Bearer invalid.token.here").execute();
        assertThat(resp.status()).isEqualTo(401);
    }

    @Test
    void customAuth_works() {
        Aura app = Aura.create().auth(ctx -> {
            String token = ctx.header("X-Token");
            if ("valid-token".equals(token)) return "99";
            return null;
        });
        Router router = new Router();
        router.before(Aura.requireAuth());
        router.get("/me", ctx -> ctx.json(Map.of("id", ctx.userId())));
        TestClient client = new TestClient(app, router);

        var resp = client.get("/me").header("X-Token", "valid-token").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).contains("99");

        var resp2 = client.get("/me").header("X-Token", "wrong").execute();
        assertThat(resp2.status()).isEqualTo(401);
    }

    @Test
    void signJwt_withoutConfig_throws() {
        Aura app = Aura.create();
        assertThatThrownBy(() -> app.signJwt(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT not configured");
    }

    @Test
    void userId_withoutAuth_throws() {
        Aura app = Aura.create();
        Router router = new Router();
        router.get("/test", ctx -> ctx.json(Map.of("id", ctx.userId())));
        TestClient client = new TestClient(app, router);

        var resp = client.get("/test").execute();
        assertThat(resp.status()).isEqualTo(500);
    }
}
