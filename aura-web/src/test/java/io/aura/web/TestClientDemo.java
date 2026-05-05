package io.aura.web;

import io.aura.Aura;

import java.util.List;
import java.util.Map;

public class TestClientDemo {

    record User(int id, String name) {}

    static class UserService {
        public User get(int id) { return new User(id, "user-" + id); }
        public List<User> list() { return List.of(new User(1, "Alice")); }
    }

    public static void main(String[] args) {
        var userService = new UserService();

        var app = Aura.create()
            .port(8080)
            .routes((Router r) -> {
                r.get("/health", ctx -> ctx.text("ok"));
                r.get("/user/{id}", userService, "get");
                r.get("/user", userService, "list");
                r.post("/echo", ctx -> ctx.json(ctx.body(Map.class)));
                r.get("/search", ctx -> ctx.json(Map.of(
                        "q", ctx.query("q", ""),
                        "page", ctx.query("page", "1"))));
                r.exception(IllegalArgumentException.class, (e, ctx) ->
                        ctx.status(400).json(Map.of("error", e.getMessage())));
                r.get("/validate", ctx -> { throw new IllegalArgumentException("bad input"); });
            });

        var test = TestClient.of(app);

        // Test health
        test.get("/health").expect(200).bodyContains("ok");
        System.out.println("GET /health → 200 ok ✓");

        // Test get user
        var resp = test.get("/user/42").expect(200);
        var user = resp.json(User.class);
        assert user.id() == 42 : "Expected id=42";
        assert "user-42".equals(user.name()) : "Expected name=user-42";
        System.out.println("GET /user/42 → 200 " + resp.body() + " ✓");

        // Test list
        test.get("/user").expect(200).bodyContains("Alice");
        System.out.println("GET /user → 200 [Alice] ✓");

        // Test 404
        test.get("/notfound").expect(404);
        System.out.println("GET /notfound → 404 ✓");

        // Test POST with body
        test.post("/echo").body(Map.of("msg", "hello")).expect(200).bodyContains("hello");
        System.out.println("POST /echo → 200 ✓");

        // Test query params
        test.get("/search?q=aura&page=3").expect(200).bodyContains("aura").bodyContains("3");
        System.out.println("GET /search?q=aura&page=3 → 200 ✓");

        // Test exception handler
        test.get("/validate").expect(400).bodyContains("bad input");
        System.out.println("GET /validate → 400 (exception handler) ✓");

        System.out.println("\nAll TestClient tests passed!");
    }
}
