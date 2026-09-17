package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraWebRuntime;
import io.aura.JwtSupport;
import io.aura.contract.RouteContribution;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

class AuthTest {

    private static final String JWT_SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void jwt_requireAuth_blocksWithoutToken() {
        JwtSupport jwt = new JwtSupport(JWT_SECRET, 3600);
        TestClient client = TestClient.of(authenticatedApp("jwt-missing", jwtAuth(jwt),
                "/protected", ctx -> ctx.json(Map.of("userId", ctx.userId()))));

        var resp = client.get("/protected").execute();
        assertThat(resp.status()).isEqualTo(401);
        assertThat(resp.body()).contains("Unauthorized");
    }

    @Test
    void jwt_requireAuth_allowsWithValidToken() {
        JwtSupport jwt = new JwtSupport(JWT_SECRET, 3600);
        TestClient client = TestClient.of(authenticatedApp("jwt-valid", jwtAuth(jwt),
                "/protected", ctx -> ctx.json(Map.of("userId", ctx.userId()))));

        String token = jwt.sign(42L);
        var resp = client.get("/protected").header("Authorization", "Bearer " + token).execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).contains("42");
    }

    @Test
    void jwt_invalidToken_returns401() {
        JwtSupport jwt = new JwtSupport(JWT_SECRET, 3600);
        TestClient client = TestClient.of(authenticatedApp("jwt-invalid", jwtAuth(jwt),
                "/protected", ctx -> ctx.text("ok")));

        var resp = client.get("/protected").header("Authorization", "Bearer invalid.token.here").execute();
        assertThat(resp.status()).isEqualTo(401);
    }

    @Test
    void customAuth_works() {
        Function<BaseContext, String> authenticator = ctx -> {
            String token = ctx.header("X-Token");
            if ("valid-token".equals(token)) return "99";
            return null;
        };
        TestClient client = TestClient.of(authenticatedApp("custom-auth", authenticator,
                "/me", ctx -> ctx.json(Map.of("id", ctx.userId()))));

        var resp = client.get("/me").header("X-Token", "valid-token").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).contains("99");

        var resp2 = client.get("/me").header("X-Token", "wrong").execute();
        assertThat(resp2.status()).isEqualTo(401);
    }

    @Test
    void userId_withoutAuth_throws() {
        AuraApp app = TestApplications.app("missing-auth",
                new RouteContribution("GET", "/test",
                        (BaseHandler) ctx -> ctx.json(Map.of("id", ctx.userId()))));
        TestClient client = TestClient.of(app);

        var resp = client.get("/test").execute();
        assertThat(resp.status()).isEqualTo(500);
    }

    private static Function<BaseContext, String> jwtAuth(JwtSupport jwt) {
        return ctx -> {
            String header = ctx.header("Authorization");
            if (header == null || !header.startsWith("Bearer ")) return null;
            return jwt.verify(header.substring(7));
        };
    }

    private static AuraApp authenticatedApp(String moduleId,
                                             Function<BaseContext, String> authenticator,
                                             String path,
                                             BaseHandler handler) {
        return TestApplications.app(moduleId,
                        new RouteContribution("GET", path, (BaseHandler) ctx -> {
                            requireAuth(ctx);
                            if (!ctx.isAborted()) handler.handle(ctx);
                        }))
                .authenticate(authenticator);
    }

    private static void requireAuth(BaseContext ctx) throws Exception {
        AuraWebRuntime app = (AuraWebRuntime) ctx.get("_app", Object.class);
        String userId = app.authFunction().apply(ctx);
        if (userId == null) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            ctx.abort();
            return;
        }
        ctx.set("_userId", userId);
    }
}
