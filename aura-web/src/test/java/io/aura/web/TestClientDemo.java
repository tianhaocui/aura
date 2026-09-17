package io.aura.web;

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

        Router router = new Router();
        router.get("/health", ctx -> ctx.text("ok"));
        router.get("/user/{id}", userService, "get");
        router.get("/user", userService, "list");
        router.post("/echo", ctx -> ctx.json(ctx.body(Map.class)));
        router.get("/search", ctx -> ctx.json(Map.of(
                "q", ctx.query("q", ""),
                "page", ctx.query("page", "1"))));
        router.get("/validate", ctx -> { throw new IllegalArgumentException("bad input"); });

        var test = TestApplications.client(router);

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
