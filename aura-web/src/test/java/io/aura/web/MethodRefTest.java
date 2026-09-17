package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.contract.RouteContribution;

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

        AuraApp app = TestApplications.app(
                new AuraConfig().port(9173),
                "method-ref-demo",
                new RouteContribution("GET", "/user/search",
                        new MethodRefHandler(userService, "search")),
                new RouteContribution("GET", "/user/{id}",
                        new MethodRefHandler(userService, "get")),
                new RouteContribution("POST", "/user",
                        new MethodRefHandler(userService, "create")),
                new RouteContribution("GET", "/health", (BaseHandler) ctx -> ctx.text("ok")));

        try {
            app.start();

            String r1 = httpGet("http://localhost:9173/user/42");
            System.out.println("Test 1 - GET /user/42: " + r1);
            assert r1.contains("42") && r1.contains("user-42") : "Expected id=42, got: " + r1;

            String r2 = httpPost("http://localhost:9173/user", "{\"name\":\"tom\",\"age\":25}");
            System.out.println("Test 2 - POST /user: " + r2);
            assert r2.contains("tom") : "Expected name=tom, got: " + r2;

            String r3 = httpGet("http://localhost:9173/user/search?name=alice&page=3");
            System.out.println("Test 3 - GET /user/search?name=alice&page=3: " + r3);
            assert r3.contains("alice") && r3.contains("3") : "Expected alice+3, got: " + r3;

            String r4 = httpGet("http://localhost:9173/health");
            System.out.println("Test 4 - GET /health: " + r4);
            assert "ok".equals(r4) : "Expected 'ok', got: " + r4;

            System.out.println("\nAll method-ref tests passed!");
        } finally {
            app.close();
        }
    }

    static String httpGet(String url) throws Exception {
        var conn = java.net.URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        try (var is = conn.getInputStream()) {
            return new String(is.readAllBytes());
        }
    }

    static String httpPost(String url, String body) throws Exception {
        var conn = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
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
