package io.aura.web;

import io.aura.Aura;

import java.util.List;
import java.util.Map;

public class SchemaTest {

    record User(int id, String name) {}

    static class UserService {
        public User get(int id) {
            return new User(id, "user-" + id);
        }

        public User create(User user) {
            return user;
        }

        public List<User> search(String name, int page) {
            return List.of(new User(1, name));
        }
    }

    public static void main(String[] args) throws Exception {
        var userService = new UserService();

        Aura app = Aura.create()
            .port(9091)
            .prop("app.name", "Schema Test App")
            .routes((Router r) -> {
                r.get("/health", ctx -> ctx.text("ok"))
                    .describe("Health check endpoint");

                r.get("/user/{id}", userService, "get")
                    .describe("Get user by ID")
                    .param("id", "User ID");

                r.post("/user", userService, "create")
                    .describe("Create a new user");

                r.get("/user/search", userService, "search")
                    .describe("Search users by name")
                    .param("name", "Search keyword")
                    .param("page", "Page number");
            });
        app.start();

        Thread.sleep(1000);

        // Test: schema endpoint
        String schema = httpGet("http://localhost:9091/__schema__");
        System.out.println("Schema response:");
        System.out.println(schema);

        // Basic assertions
        assert schema.contains("Schema Test App") : "Missing app name";
        assert schema.contains("Get user by ID") : "Missing route description";
        assert schema.contains("User ID") : "Missing param description";
        assert schema.contains("Search keyword") : "Missing search param description";
        assert schema.contains("Health check endpoint") : "Missing health description";
        assert schema.contains("\"returnType\":\"User\"") : "Missing return type";
        assert schema.contains("\"source\":\"path\"") : "Missing path source";
        assert schema.contains("\"source\":\"body\"") : "Missing body source";
        assert schema.contains("\"source\":\"query\"") : "Missing query source";

        // Test: routes still work
        String user = httpGet("http://localhost:9091/user/42");
        System.out.println("\nGET /user/42: " + user);
        assert user.contains("user-42") : "Route broken after schema changes";

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
}
