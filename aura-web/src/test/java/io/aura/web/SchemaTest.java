package io.aura.web;

import com.alibaba.fastjson2.JSON;

import java.util.List;

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

    public static void main(String[] args) {
        var userService = new UserService();

        Router router = new Router();
        router.get("/health", ctx -> ctx.text("ok"))
                    .describe("Health check endpoint");

        router.get("/user/{id}", userService, "get")
                    .describe("Get user by ID")
                    .param("id", "User ID");

        router.post("/user", userService, "create")
                    .describe("Create a new user");

        router.get("/user/search", userService, "search")
                    .describe("Search users by name")
                    .param("name", "Search keyword")
                    .param("page", "Page number");
        TestClient client = TestApplications.client(router);

        // Test: schema endpoint
        String schema = JSON.toJSONString(client.schema());
        System.out.println("Schema response:");
        System.out.println(schema);

        // Basic assertions
        assert schema.contains("Get user by ID") : "Missing route description";
        assert schema.contains("User ID") : "Missing param description";
        assert schema.contains("Search keyword") : "Missing search param description";
        assert schema.contains("Health check endpoint") : "Missing health description";
        assert schema.contains("\"returnType\":\"User\"") : "Missing return type";
        assert schema.contains("\"source\":\"path\"") : "Missing path source";
        assert schema.contains("\"source\":\"body\"") : "Missing body source";
        assert schema.contains("\"source\":\"query\"") : "Missing query source";

        // Test: routes still work
        String user = client.get("/user/42").execute().body();
        System.out.println("\nGET /user/42: " + user);
        assert user.contains("user-42") : "Route broken after schema changes";

        System.out.println("\nAll tests passed!");
    }
}
