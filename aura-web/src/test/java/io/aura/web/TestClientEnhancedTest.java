package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class TestClientEnhancedTest {

    // --- query() convenience ---

    @Test
    void query_appendsToPath() {
        var app = Aura.create();
        app.routes(r -> r.get("/items", ctx -> ctx.json(Map.of("page", ctx.query("page"), "size", ctx.query("size")))));
        var client = TestClient.of(app);

        var resp = client.get("/items").query("page", "2").query("size", "10").execute();
        assertThat(resp.status()).isEqualTo(200);
        resp.expectJson("$.page", "2");
        resp.expectJson("$.size", "10");
    }

    // --- expectJson ---

    @Test
    void expectJson_matchesValue() {
        var app = Aura.create();
        app.routes(r -> r.get("/user", ctx -> ctx.json(Map.of("name", "Alice", "age", 30))));
        var client = TestClient.of(app);

        client.get("/user").execute()
                .expect(200)
                .expectJson("$.name", "Alice")
                .expectJson("$.age", 30);
    }

    @Test
    void expectJson_failsOnMismatch() {
        var app = Aura.create();
        app.routes(r -> r.get("/user", ctx -> ctx.json(Map.of("name", "Alice"))));
        var client = TestClient.of(app);

        var resp = client.get("/user").execute();
        assertThatThrownBy(() -> resp.expectJson("$.name", "Bob"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("$.name")
                .hasMessageContaining("Bob")
                .hasMessageContaining("Alice");
    }

    // --- TestSession ---

    @Test
    void session_manualToken_attachesHeader() {
        var app = Aura.create().jwt("test-secret");
        app.routes(r -> {
            r.before(Aura.requireAuth());
            r.get("/me", ctx -> ctx.json(Map.of("userId", ctx.userId())));
        });
        var client = TestClient.of(app);

        String token = app.signJwt("user-42");
        var session = client.session("Bearer " + token);
        session.get("/me").expect(200).expectJson("$.userId", "user-42");
    }

    @Test
    void session_login_extractsToken() {
        var app = Aura.create().jwt("secret");
        app.routes(r -> {
            r.post("/login", ctx -> ctx.json(Map.of("token", app.signJwt("123"))));
            r.get("/me", ctx -> {
                String auth = ctx.header("Authorization");
                if (auth == null) { ctx.status(401).json(Map.of("error", "Unauthorized")); return; }
                String token = auth.replace("Bearer ", "");
                String uid = app.verifyJwt(token);
                ctx.json(Map.of("userId", uid));
            });
        });
        var client = TestClient.of(app);

        var loginResp = client.post("/login").body("{}").execute();
        var session = client.session();
        session.login(loginResp);
        session.get("/me").expect(200).expectJson("$.userId", "123");
    }

    @Test
    void session_withoutToken_gets401() {
        var app = Aura.create().jwt("secret");
        app.routes(r -> {
            r.before(Aura.requireAuth());
            r.get("/me", ctx -> ctx.text("ok"));
        });
        var client = TestClient.of(app);

        var session = client.session();
        session.get("/me").expect(401);
    }

    // --- public MockContext ---

    @Test
    void mockContext_publiclyConstructible() {
        var app = Aura.create();
        var ctx = new MockContext(Map.of("id", "42"), Map.of(), Map.of(), null, app);
        assertThat(ctx.path("id")).isEqualTo("42");
        assertThat(ctx.statusCode()).isEqualTo(200);
    }
}