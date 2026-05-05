package io.aura.web;

import io.aura.Aura;
import io.aura.annotation.*;

import java.util.List;
import java.util.Map;

public class ServiceTest {

    record User(int id, String name) {}
    record CreateReq(String name) {}
    record Stats(int total, int active) {}

    @Path("/user")
    @Desc("User management")
    public static class UserService {

        @Desc("Get user by ID")
        public User get(@Desc("User ID") int id) {
            return new User(id, "user-" + id);
        }

        @Desc("List all users")
        public List<User> list() {
            return List.of(new User(1, "Alice"), new User(2, "Bob"));
        }

        @Desc("Create a user")
        public User create(CreateReq req) {
            return new User(99, req.name());
        }

        @Desc("Delete a user")
        public void delete(int id) {}

        @Get("/search")
        @Desc("Search users by keyword")
        public List<User> search(String keyword, int page) {
            return List.of(new User(1, keyword + "-result"));
        }

        @Get("/stats")
        @Desc("Get user statistics")
        public Stats stats() {
            return new Stats(100, 80);
        }

        @Post("/batch-import")
        @Desc("Batch import users")
        public Map<String, Integer> batchImport(List<User> users) {
            return Map.of("imported", users.size());
        }
    }

    public static void main(String[] args) throws Exception {
        Aura app = Aura.create()
            .port(9092)
            .prop("app.name", "Service Test")
            .service(new UserService())
            .routes((Router r) -> {
                r.get("/health", ctx -> ctx.text("ok"));
            });
        app.start();

        Thread.sleep(1000);

        // Test 1: CRUD convention - get
        String r1 = httpGet("http://localhost:9092/user/42");
        System.out.println("GET /user/42: " + r1);
        assert r1.contains("user-42") : "get failed: " + r1;

        // Test 2: CRUD convention - list
        String r2 = httpGet("http://localhost:9092/user");
        System.out.println("GET /user: " + r2);
        assert r2.contains("Alice") : "list failed: " + r2;

        // Test 3: CRUD convention - create
        String r3 = httpPost("http://localhost:9092/user", "{\"name\":\"Charlie\"}");
        System.out.println("POST /user: " + r3);
        assert r3.contains("Charlie") : "create failed: " + r3;

        // Test 4: Annotation route - search
        String r4 = httpGet("http://localhost:9092/user/search?keyword=test&page=1");
        System.out.println("GET /user/search: " + r4);
        assert r4.contains("test-result") : "search failed: " + r4;

        // Test 5: Annotation route - stats
        String r5 = httpGet("http://localhost:9092/user/stats");
        System.out.println("GET /user/stats: " + r5);
        assert r5.contains("100") : "stats failed: " + r5;

        // Test 6: routes() still works alongside service()
        String r6 = httpGet("http://localhost:9092/health");
        System.out.println("GET /health: " + r6);
        assert "ok".equals(r6) : "health failed: " + r6;

        // Test 7: schema includes service routes with @Desc
        String schema = httpGet("http://localhost:9092/__schema__");
        System.out.println("\nSchema:\n" + schema);
        assert schema.contains("User management") || schema.contains("Get user by ID") : "schema missing desc";
        assert schema.contains("Search users by keyword") : "schema missing search desc";
        assert schema.contains("/user/search") : "schema missing search path";
        assert schema.contains("/user/stats") : "schema missing stats path";

        System.out.println("\nAll tests passed!");
        app.stop();
    }

    static String httpGet(String url) throws Exception {
        var conn = new java.net.URL(url).openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        try (var is = conn.getInputStream()) {
            return new String(is.readAllBytes());
        } catch (java.io.IOException e) {
            try (var es = ((java.net.HttpURLConnection) conn).getErrorStream()) {
                return es != null ? new String(es.readAllBytes()) : e.getMessage();
            }
        }
    }

    static String httpPost(String url, String body) throws Exception {
        var conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        conn.getOutputStream().write(body.getBytes());
        try (var is = conn.getInputStream()) {
            return new String(is.readAllBytes());
        }
    }
}
