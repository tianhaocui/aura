package io.aura.web;

import io.aura.Aura;

public class MethodRefTest {

    public record User(int id, String name) {}
    public record CreateReq(String name, int age) {}

    public static class UserService {
        public User get(int id) {
            return new User(id, "user-" + id);
        }

        public User create(CreateReq req) {
            return new User(99, req.name());
        }

        public User search(String name, int page) {
            return new User(page, name);
        }
    }

    public static void main(String[] args) throws Exception {
        UserService userService = new UserService();

        Aura.create()
            .port(9173)
            .routes((Router r) -> {
                r.get("/user/search", userService, "search");
                r.get("/user/{id}", userService, "get");
                r.post("/user", userService, "create");

                r.get("/health", ctx -> ctx.text("ok"));
            })
            .start();

        Thread.sleep(1000);

        // Test 1: path param → int
        String r1 = httpGet("http://localhost:9173/user/42");
        System.out.println("Test 1 - GET /user/42: " + r1);
        assert r1.contains("42") && r1.contains("user-42") : "Expected id=42, got: " + r1;

        // Test 2: record from body
        String r2 = httpPost("http://localhost:9173/user", "{\"name\":\"tom\",\"age\":25}");
        System.out.println("Test 2 - POST /user: " + r2);
        assert r2.contains("tom") : "Expected name=tom, got: " + r2;

        // Test 3: query params → String + int
        String r3 = httpGet("http://localhost:9173/user/search?name=alice&page=3");
        System.out.println("Test 3 - GET /user/search?name=alice&page=3: " + r3);
        assert r3.contains("alice") && r3.contains("3") : "Expected alice+3, got: " + r3;

        // Test 4: lambda handler still works
        String r4 = httpGet("http://localhost:9173/health");
        System.out.println("Test 4 - GET /health: " + r4);
        assert "ok".equals(r4) : "Expected 'ok', got: " + r4;

        System.out.println("\nAll method-ref tests passed!");
        System.exit(0);
    }

    static String httpGet(String url) throws Exception {
        var conn = new java.net.URL(url).openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        try (var is = conn.getInputStream()) {
            return new String(is.readAllBytes());
        }
    }

    static String httpPost(String url, String body) throws Exception {
        var conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        conn.getOutputStream().write(body.getBytes());
        try (var is = conn.getInputStream()) {
            return new String(is.readAllBytes());
        }
    }
}
