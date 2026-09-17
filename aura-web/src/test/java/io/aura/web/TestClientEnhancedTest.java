package io.aura.web;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class TestClientEnhancedTest {

    // --- query() convenience ---

    @Test
    void query_appendsToPath() {
        Router router = new Router();
        router.get("/items", ctx -> ctx.json(Map.of("page", ctx.query("page"), "size", ctx.query("size"))));
        var client = TestApplications.client(router);

        var resp = client.get("/items").query("page", "2").query("size", "10").execute();
        assertThat(resp.status()).isEqualTo(200);
        resp.expectJson("$.page", "2");
        resp.expectJson("$.size", "10");
    }

    // --- expectJson ---

    @Test
    void expectJson_matchesValue() {
        Router router = new Router();
        router.get("/user", ctx -> ctx.json(Map.of("name", "Alice", "age", 30)));
        var client = TestApplications.client(router);

        client.get("/user").execute()
                .expect(200)
                .expectJson("$.name", "Alice")
                .expectJson("$.age", 30);
    }

    @Test
    void expectJson_failsOnMismatch() {
        Router router = new Router();
        router.get("/user", ctx -> ctx.json(Map.of("name", "Alice")));
        var client = TestApplications.client(router);

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
        Router router = new Router();
        router.get("/me", ctx -> {
            String authorization = ctx.header("Authorization");
            if (authorization == null) {
                ctx.status(401).json(Map.of("error", "Unauthorized"));
                return;
            }
            ctx.json(Map.of("authorization", authorization));
        });
        var client = TestApplications.client(router);

        var session = client.session("Bearer test-token");
        session.get("/me").expect(200).expectJson("$.authorization", "Bearer test-token");
    }

    @Test
    void session_login_extractsToken() {
        Router router = new Router();
        router.post("/login", ctx -> ctx.json(Map.of("token", "session-token")));
        router.get("/me", ctx -> {
            String auth = ctx.header("Authorization");
            if (auth == null) { ctx.status(401).json(Map.of("error", "Unauthorized")); return; }
            ctx.json(Map.of("token", auth.replace("Bearer ", "")));
        });
        var client = TestApplications.client(router);

        var loginResp = client.post("/login").body("{}").execute();
        var session = client.session();
        session.login(loginResp);
        session.get("/me").expect(200).expectJson("$.token", "session-token");
    }

    @Test
    void session_withoutToken_gets401() {
        Router router = new Router();
        router.get("/me", ctx -> {
            if (ctx.header("Authorization") == null) {
                ctx.status(401).json(Map.of("error", "Unauthorized"));
                return;
            }
            ctx.text("ok");
        });
        var client = TestApplications.client(router);

        var session = client.session();
        session.get("/me").expect(401);
    }

    // --- public MockContext ---

    @Test
    void mockContext_publiclyConstructible() {
        var ctx = new MockContext(Map.of("id", "42"), Map.of(), Map.of(), null,
                TestApplications.runtime());
        assertThat(ctx.path("id")).isEqualTo("42");
        assertThat(ctx.statusCode()).isEqualTo(200);
    }
}
