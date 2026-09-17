package io.aura.web;

import com.alibaba.fastjson2.JSON;
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
        Router router = new Router();
        ServiceRegistrar.register(new UserService(), router);
        router.get("/health", ctx -> ctx.text("ok"));
        TestClient client = TestApplications.client(router);

        // Test 1: CRUD convention - get
        String r1 = client.get("/user/42").execute().body();
        System.out.println("GET /user/42: " + r1);
        assert r1.contains("user-42") : "get failed: " + r1;

        // Test 2: CRUD convention - list
        String r2 = client.get("/user").execute().body();
        System.out.println("GET /user: " + r2);
        assert r2.contains("Alice") : "list failed: " + r2;

        // Test 3: CRUD convention - create
        String r3 = client.post("/user").body("{\"name\":\"Charlie\"}").execute().body();
        System.out.println("POST /user: " + r3);
        assert r3.contains("Charlie") : "create failed: " + r3;

        // Test 4: Annotation route - search
        String r4 = client.get("/user/search?keyword=test&page=1").execute().body();
        System.out.println("GET /user/search: " + r4);
        assert r4.contains("test-result") : "search failed: " + r4;

        // Test 5: Annotation route - stats
        String r5 = client.get("/user/stats").execute().body();
        System.out.println("GET /user/stats: " + r5);
        assert r5.contains("100") : "stats failed: " + r5;

        // Test 6: direct routes still work alongside reflected service routes
        String r6 = client.get("/health").execute().body();
        System.out.println("GET /health: " + r6);
        assert "ok".equals(r6) : "health failed: " + r6;

        // Test 7: schema includes service routes with @Desc
        String schema = JSON.toJSONString(client.schema());
        System.out.println("\nSchema:\n" + schema);
        assert schema.contains("User management") || schema.contains("Get user by ID") : "schema missing desc";
        assert schema.contains("Search users by keyword") : "schema missing search desc";
        assert schema.contains("/user/search") : "schema missing search path";
        assert schema.contains("/user/stats") : "schema missing stats path";

        System.out.println("\nAll tests passed!");
    }
}
