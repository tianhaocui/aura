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

        System.out.println("\nAll TestClient tests passed!");
    }
}
